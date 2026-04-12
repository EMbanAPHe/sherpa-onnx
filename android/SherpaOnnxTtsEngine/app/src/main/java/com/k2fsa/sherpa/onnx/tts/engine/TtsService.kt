package com.k2fsa.sherpa.onnx.tts.engine

import PreferenceHelper
import android.content.Intent
import com.k2fsa.sherpa.onnx.GenerationConfig
import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import java.text.Normalizer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * Architecture overview
 * ─────────────────────
 * onSynthesizeText() splits the incoming text into clauses, then runs a
 * producer/consumer pair:
 *
 *   Generator thread  — calls generateWithConfigAndCallback() per clause.
 *                       Each C++ callback fires with a FloatArray of samples,
 *                       which are RMS-normalised and converted to PCM and placed
 *                       on the queue.
 *
 *   Consumer (caller thread) — drains the queue into audioAvailable(), which
 *                       the Android TTS framework passes to the audio output.
 *
 * The generator runs on a persistent single-thread Executor so there is no
 * per-sentence thread-creation overhead and synthesis calls are automatically
 * serialised if somehow called concurrently.
 *
 * Production features enabled by default (all toggleable in Settings):
 *   • Sentence audio cache  — LRU cache of up to 20 PCM chunks; repeat
 *                             sentences play instantly.
 *   • RMS normalisation     — each chunk is gain-adjusted to -21.9 dBFS so
 *                             volume is consistent and clipping is prevented.
 *   • SSML stripping        — removes XML/SSML markup before synthesis so
 *                             tags are not spoken literally.
 *
 * Always-on (no toggle needed):
 *   • Unicode NFKC normalisation — one-line text canonicalisation.
 *   • Single-thread Executor     — replaces per-call Thread allocation.
 */

class TtsService : TextToSpeechService() {

    // ── Queue item types ──────────────────────────────────────────────────────

    private sealed class QueueItem {
        class Data(val bytes: ByteArray, val length: Int) : QueueItem()
        object End  : QueueItem()
        class Error(val t: Throwable) : QueueItem()
    }

    // ── State ─────────────────────────────────────────────────────────────────

    @Volatile private var currentCancelled: AtomicBoolean? = null
    @Volatile private var currentQueue: LinkedBlockingQueue<QueueItem>? = null

    // Single-thread executor: replaces new Thread{} per synthesis call.
    // Daemon threads don't block process exit.
    private val generatorExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SherpaTtsGenerator").also { it.isDaemon = true }
    }

    // Preferences — initialised once in onCreate.
    private lateinit var prefs: PreferenceHelper

    // LRU audio cache lives in TtsEngine so reinitialize() can clear it.
    // Access always via synchronized(TtsEngine.audioCache).
    private val audioCache get() = TtsEngine.audioCache

    // ── Text pre-processing ───────────────────────────────────────────────────

    /**
     * Strip SSML/XML tags from [text].
     * Replaces each tag with a space; then collapses multiple spaces.
     * Safe for plain text (regex only matches < followed by content then >).
     */
    private fun stripSsml(text: String): String {
        val stripped = text.replace(Regex("<[^>]+>"), " ")
        return stripped.replace(Regex("\\s{2,}"), " ").trim()
    }

    /**
     * Apply Unicode NFKC normalisation and optional SSML stripping.
     * NFKC converts ligatures, smart quotes, full-width chars, etc. so
     * espeak-ng sees canonical ASCII/Unicode text.
     */
    private fun normaliseText(raw: String, ssmlStrip: Boolean): String {
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        return if (ssmlStrip && nfkc.contains('<')) stripSsml(nfkc) else nfkc
    }

    /**
     * Split [text] into clause-sized chunks so Kokoro never infers more than
     * ~[minClauseWords] words at once.
     *
     * Rules:
     *  1. Always split on sentence-ending punctuation (. ! ?) — Kokoro's
     *     internal espeak pipeline splits here anyway.
     *  2. Split on clause-level punctuation (, ; : — …) ONLY when the
     *     accumulated word count >= [minClauseWords].
     *  3. Never produce an empty chunk.
     *  4. Punctuation is kept at the END of its chunk.
     */
    private fun splitIntoClauses(text: String, minClauseWords: Int = MIN_CLAUSE_WORDS): List<String> {
        val hardSplit = setOf('.', '!', '?')
        val softSplit = setOf(',', ';', ':', '—', '…')
        val chunks    = mutableListOf<String>()
        val current   = StringBuilder()
        var wordCount = 1
        var i         = 0

        while (i < text.length) {
            val ch = text[i]
            if (ch == ' ' || ch == '\t' || ch == '\n') wordCount++
            current.append(ch)

            val isHard    = ch in hardSplit
            val isSoft    = ch in softSplit
            val nextSpace = (i + 1 >= text.length) || text[i + 1].isWhitespace()

            if ((isHard && nextSpace) ||
                (isSoft && nextSpace && wordCount >= minClauseWords)) {
                val chunk = current.toString().trim()
                if (chunk.isNotEmpty()) chunks.add(chunk)
                current.clear()
                wordCount = 1
            }
            i++
        }

        val remainder = current.toString().trim()
        if (remainder.isNotEmpty()) {
            if (chunks.isNotEmpty() && wordCount < minClauseWords)
                chunks[chunks.lastIndex] = chunks.last() + " " + remainder
            else
                chunks.add(remainder)
        }

        return if (chunks.isEmpty()) listOf(text) else chunks
    }

    // ── Audio processing ──────────────────────────────────────────────────────

    /**
     * RMS-normalise [samples] to [TARGET_RMS_DBFS].
     * Skips near-silent chunks (avoids dividing by ~zero).
     * Caps gain at [MAX_GAIN] to prevent over-amplifying noise.
     * Returns the same array modified in-place for zero allocation.
     */
    private fun rmsNormalise(samples: FloatArray): FloatArray {
        var sumSq = 0.0
        for (s in samples) sumSq += s.toDouble() * s.toDouble()
        val rms = sqrt(sumSq / samples.size).toFloat()

        if (rms < RMS_FLOOR) return samples          // near-silent: skip

        val gain = (TARGET_RMS / rms).coerceAtMost(MAX_GAIN)
        if (kotlin.math.abs(gain - 1.0f) < 0.01f) return samples  // already close: skip

        for (i in samples.indices) samples[i] = (samples[i] * gain).coerceIn(-1.0f, 1.0f)
        return samples
    }

    /**
     * Convert [floatSamples] to little-endian PCM-16 ByteArray via NIO.
     * Clips each sample to [-1.0, 1.0] before conversion.
     */
    private fun floatToPcm16(floatSamples: FloatArray): ByteArray {
        val pcm  = ByteArray(floatSamples.size * 2)
        val buf  = java.nio.ByteBuffer.wrap(pcm)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        for (s in floatSamples) {
            buf.put((s.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort())
        }
        return pcm
    }

    /** Cache key encodes all parameters that affect the audio output. */
    private fun cacheKey(text: String, speed: Float, sid: Int, silenceScale: Float) =
        "${text.trim()}|${String.format("%.2f", speed)}|$sid|${String.format("%.3f", silenceScale)}"

    // ── TextToSpeechService overrides ─────────────────────────────────────────

    override fun onCreate() {
        Log.i(TAG, "onCreate tts service")
        super.onCreate()
        prefs = PreferenceHelper(applicationContext)
        onLoadLanguage(TtsEngine.lang, "", "")
        if (TtsEngine.lang2 != null) onLoadLanguage(TtsEngine.lang2, "", "")

        // Warm up ONNX Runtime on a daemon thread so the first real sentence
        // doesn't pay the JIT compilation cost.
        Thread {
            try {
                TtsEngine.tts?.let { engine ->
                    Log.i(TAG, "Warm-up inference starting")
                    engine.generateWithCallback(
                        text     = WARMUP_TEXT,
                        sid      = 0,
                        speed    = 1.0f,
                        callback = { _ -> 0 }
                    )
                    Log.i(TAG, "Warm-up inference complete")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Warm-up failed (non-fatal): ${t.message}")
            }
        }.apply { isDaemon = true; name = "SherpaTtsWarmup" }.start()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy tts service")
        generatorExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onIsLanguageAvailable(
        _lang: String?, _country: String?, _variant: String?
    ): Int {
        val requested  = _lang ?: ""
        val primary    = TtsEngine.lang ?: "eng"
        val normalised = when {
            requested.equals("eng", ignoreCase = true) -> "eng"
            requested.startsWith("en", ignoreCase = true) -> primary
            else -> requested
        }
        return if (normalised.equals(primary, ignoreCase = true) ||
                   (TtsEngine.lang2 != null &&
                    normalised.equals(TtsEngine.lang2, ignoreCase = true)))
            TextToSpeech.LANG_AVAILABLE
        else
            TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> =
        arrayOf(TtsEngine.lang ?: "eng", "", "")

    override fun onLoadLanguage(
        _lang: String?, _country: String?, _variant: String?
    ): Int {
        Log.i(TAG, "onLoadLanguage: $_lang, $_country")
        val lang = _lang ?: ""
        return if (lang == TtsEngine.lang || lang == TtsEngine.lang2) {
            TtsEngine.createTts(application)
            TextToSpeech.LANG_AVAILABLE
        } else {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onStop() {
        Log.i(TAG, "onStop()")
        currentCancelled?.set(true)
        currentQueue?.let { q -> q.clear(); q.offer(QueueItem.End) }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        // ── Read all preferences up-front (cheap SharedPrefs map lookups) ──
        val cacheEnabled   = prefs.getAudioCacheEnabled()
        val rmsEnabled     = prefs.getRmsNormaliseEnabled()
        val ssmlEnabled    = prefs.getSsmlStripEnabled()
        val silenceScale   = prefs.getSilenceScale()
        val minClauseWords = prefs.getMinClauseWords()

        // ── Text pre-processing ───────────────────────────────────────────────
        val rawText = request.charSequenceText.toString()
        val text    = normaliseText(rawText, ssmlEnabled)

        if (onIsLanguageAvailable(request.language, request.country, request.variant)
                == TextToSpeech.LANG_NOT_SUPPORTED) {
            callback.error(); return
        }

        val tts = TtsEngine.tts ?: run {
            TtsEngine.createTts(applicationContext)
            TtsEngine.tts ?: run { callback.error(); return }
        }

        callback.start(tts.sampleRate(), AudioFormat.ENCODING_PCM_16BIT, 1)
        if (text.isBlank()) { callback.done(); return }

        // ── Speed ─────────────────────────────────────────────────────────────
        val effectiveSpeed = run {
            val rate = runCatching { request.speechRate }.getOrDefault(-1)
            if (TtsEngine.useSystemSpeed && rate > 0)
                (rate / 100.0f).coerceIn(MIN_TTS_SPEED, MAX_TTS_SPEED)
            else
                TtsEngine.speed
        }

        val sid    = TtsEngine.speakerId
        val clauses = splitIntoClauses(text, minClauseWords)
        Log.i(TAG, "onSynthesizeText: ${clauses.size} clause(s), speed=$effectiveSpeed " +
                   "cache=$cacheEnabled rms=$rmsEnabled ssml=$ssmlEnabled")

        val cancelled = AtomicBoolean(false)
        val queue     = LinkedBlockingQueue<QueueItem>(32)
        currentCancelled = cancelled
        currentQueue     = queue

        // ── Generator (runs on persistent single-thread executor) ─────────────
        generatorExecutor.submit {
            try {
                for (clause in clauses) {
                    if (cancelled.get()) break

                    val key = if (cacheEnabled)
                        cacheKey(clause, effectiveSpeed, sid, silenceScale)
                    else null

                    // Cache hit: enqueue cached PCM directly, skip synthesis
                    val cached: ByteArray? = key?.let {
                        synchronized(audioCache) { audioCache[it] }
                    }
                    if (cached != null) {
                        Log.i(TAG, "Cache hit (${clause.length} chars)")
                        try { queue.put(QueueItem.Data(cached, cached.size)) }
                        catch (_: InterruptedException) { break }
                        continue
                    }

                    // Cache miss: synthesise
                    val chunkPcm = mutableListOf<ByteArray>()

                    tts.generateWithConfigAndCallback(
                        text   = clause,
                        config = GenerationConfig(
                            sid          = sid,
                            speed        = effectiveSpeed,
                            silenceScale = silenceScale,
                        ),
                    ) { floatSamples ->
                        if (cancelled.get()) return@generateWithConfigAndCallback 0
                        if (floatSamples.isEmpty()) return@generateWithConfigAndCallback 1

                        val samples = if (rmsEnabled) rmsNormalise(floatSamples.copyOf())
                                      else floatSamples
                        val pcm = floatToPcm16(samples)
                        chunkPcm.add(pcm)

                        try { queue.put(QueueItem.Data(pcm, pcm.size)) }
                        catch (_: InterruptedException) {
                            return@generateWithConfigAndCallback 0
                        }
                        if (cancelled.get()) 0 else 1
                    }

                    // Merge all PCM chunks for this clause and cache them
                    if (key != null && chunkPcm.isNotEmpty() && !cancelled.get()) {
                        val total = chunkPcm.sumOf { it.size }
                        val merged = ByteArray(total)
                        var pos = 0
                        for (c in chunkPcm) { c.copyInto(merged, pos); pos += c.size }
                        synchronized(audioCache) { audioCache[key] = merged }
                    }
                }

                if (!cancelled.get()) queue.offer(QueueItem.End)

            } catch (t: Throwable) {
                Log.e(TAG, "Generator error", t)
                queue.offer(QueueItem.Error(t))
            }
        }

        // ── Consumer (runs on TTS framework thread) ───────────────────────────
        try {
            while (true) {
                when (val item = queue.take()) {
                    is QueueItem.End  -> break
                    is QueueItem.Data -> {
                        if (cancelled.get()) break
                        val maxBuf = callback.maxBufferSize.coerceAtLeast(4096)
                        var offset = 0
                        while (offset < item.length && !cancelled.get()) {
                            var chunk = minOf(maxBuf, item.length - offset)
                            if (chunk % 2 != 0) chunk--
                            if (chunk <= 0) break
                            callback.audioAvailable(item.bytes, offset, chunk)
                            offset += chunk
                        }
                    }
                    is QueueItem.Error -> {
                        Log.e(TAG, "Generator thread error", item.t)
                        callback.error(TextToSpeech.ERROR_OUTPUT)
                        cancelled.set(true)
                        break
                    }
                }
            }
            if (!cancelled.get()) callback.done()
        } catch (e: InterruptedException) {
            Log.w(TAG, "Consumer interrupted", e)
            cancelled.set(true)
        } finally {
            cancelled.set(true)
            currentCancelled = null
            currentQueue     = null
        }
    }


    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG              = "TtsService"
        private const val MIN_CLAUSE_WORDS = 6
        private const val WARMUP_TEXT      = "The quick brown fox."
        // RMS normalisation parameters
        private const val TARGET_RMS = 0.08f   // -21.9 dBFS
        private const val RMS_FLOOR  = 0.001f  // skip near-silent chunks
        private const val MAX_GAIN   = 4.0f    // cap at +12 dB to avoid noise amplification
    }
}
