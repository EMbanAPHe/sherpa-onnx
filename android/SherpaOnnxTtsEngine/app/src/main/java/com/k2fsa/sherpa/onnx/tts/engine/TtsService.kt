package com.k2fsa.sherpa.onnx.tts.engine

import android.content.Intent
import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Language codes Android TTS sends (ISO 639-3 / ISO 3166-1 alpha-3):
 *   eng, USA  |  eng, GBR  |  deu  |  fra  |  ...
 *
 * This service works by overlapping inference with playback:
 *   - The synthesis callback fires once per sentence (Kokoro's internal batching).
 *   - Each sentence's PCM16 bytes are placed on a bounded queue.
 *   - The synthesis thread keeps running the NEXT sentence while the consumer
 *     thread drains audio bytes to Android's callback.audioAvailable().
 *
 * This reduces the audible gap between sentences because sentence N+1 is being
 * inferred while sentence N is being played.
 *
 * NOTE: We use generateWithCallback (available in sherpa-onnx v1.12.15 JitPack AAR).
 *       The fork's generateStreaming was removed because it referenced a JNI function
 *       that is never present in the published AAR — it would crash at runtime.
 */

class TtsService : TextToSpeechService() {

    // Streaming infra — shared across the generator callback and the consumer loop
    private sealed class QueueItem {
        class Data(val bytes: ByteArray, val length: Int) : QueueItem()
        object End : QueueItem()
        class Error(val t: Throwable) : QueueItem()
    }

    @Volatile private var currentCancelled: AtomicBoolean? = null
    @Volatile private var currentQueue: LinkedBlockingQueue<QueueItem>? = null

    // ────────────────────────────────────────────────────────────────────────
    override fun onCreate() {
        Log.i(TAG, "onCreate tts service")
        super.onCreate()
        onLoadLanguage(TtsEngine.lang, "", "")
        if (TtsEngine.lang2 != null) onLoadLanguage(TtsEngine.lang2, "", "")
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy tts service")
        super.onDestroy()
    }

    override fun onIsLanguageAvailable(
        _lang: String?, _country: String?, _variant: String?
    ): Int {
        val requested = _lang ?: ""
        val primary   = TtsEngine.lang ?: "eng"

        // Normalise: Android may send "en", "eng", "en-US", "en_US", etc.
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
            Log.i(TAG, "creating tts for lang: $lang")
            TtsEngine.createTts(application)
            TextToSpeech.LANG_AVAILABLE
        } else {
            Log.i(TAG, "lang $lang not supported; engine: ${TtsEngine.lang}, ${TtsEngine.lang2}")
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onStop() {
        Log.i(TAG, "onStop()")
        // Signal the generator callback to return 0 (stop)
        currentCancelled?.set(true)
        // Drain the queue so the consumer loop unblocks and exits
        currentQueue?.let { q ->
            q.clear()
            q.offer(QueueItem.End)   // wake up any blocking take()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        val language = request.language
        val country  = request.country
        val variant  = request.variant
        val text     = request.charSequenceText.toString()

        if (onIsLanguageAvailable(language, country, variant) == TextToSpeech.LANG_NOT_SUPPORTED) {
            callback.error()
            return
        }

        Log.i(TAG, "onSynthesizeText: \"$text\"")

        // Lazily initialise TTS if not already done
        val tts = TtsEngine.tts ?: run {
            TtsEngine.createTts(applicationContext)
            TtsEngine.tts ?: run {
                callback.error()
                return
            }
        }

        val sampleRate = tts.sampleRate()
        callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)

        if (text.isBlank()) {
            callback.done()
            return
        }

        // Effective speed: honour the "allow system rate/pitch" toggle
        val effectiveSpeed = if (TtsEngine.useSystemRatePitch) {
            (request.speechRate / 100.0f).coerceIn(0.2f, 3.0f)
        } else {
            TtsEngine.speed
        }

        Log.i(TAG, "speed=$effectiveSpeed useSystem=${TtsEngine.useSystemRatePitch}")

        // Per-request cancel flag and bounded queue (32 sentences ≈ several paragraphs)
        val cancelled = AtomicBoolean(false)
        val queue     = LinkedBlockingQueue<QueueItem>(32)
        currentCancelled = cancelled
        currentQueue     = queue

        // ── Generator: runs inside the sherpa-onnx callback (called once per sentence)
        // generateWithCallback IS present in the stock v1.12.15 JitPack AAR.
        // It fires the callback after each sentence's inference completes, then
        // accumulates all samples into the returned GeneratedAudio (which we ignore).
        val generatorThread = Thread {
            try {
                tts.generateWithCallback(
                    text     = text,
                    sid      = TtsEngine.speakerId,
                    speed    = effectiveSpeed,
                ) { floatSamples ->
                    if (cancelled.get()) return@generateWithCallback 0

                    val frames = floatSamples.size
                    if (frames == 0) return@generateWithCallback 1

                    // Convert float→PCM16 in-line (no separate heap allocation per call
                    // beyond the required output ByteArray, which is unavoidable)
                    val pcmBytes = ByteArray(frames * 2)
                    var j = 0
                    for (i in 0 until frames) {
                        val s = (floatSamples[i] * 32767.0f).toInt().coerceIn(-32768, 32767)
                        pcmBytes[j]     = (s and 0xff).toByte()
                        pcmBytes[j + 1] = ((s ushr 8) and 0xff).toByte()
                        j += 2
                    }

                    try {
                        queue.put(QueueItem.Data(pcmBytes, pcmBytes.size))
                    } catch (ie: InterruptedException) {
                        return@generateWithCallback 0
                    }

                    if (cancelled.get()) 0 else 1
                }

                if (!cancelled.get()) queue.offer(QueueItem.End)

            } catch (t: Throwable) {
                Log.e(TAG, "Generator error", t)
                queue.offer(QueueItem.Error(t))
            }
        }.apply {
            name     = "SherpaTtsGenerator"
            isDaemon = true
        }

        generatorThread.start()

        // ── Consumer: drains the queue into Android's audioAvailable()
        try {
            while (true) {
                val item = queue.take()
                when (item) {
                    is QueueItem.End -> break

                    is QueueItem.Data -> {
                        if (cancelled.get()) break
                        val maxBuf = callback.maxBufferSize.coerceAtLeast(4096)
                        var offset = 0
                        val total  = item.length
                        while (offset < total && !cancelled.get()) {
                            var chunk = minOf(maxBuf, total - offset)
                            if (chunk % 2 != 0) chunk--   // keep whole PCM16 frames
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

    companion object {
        private const val TAG = "TtsService"
    }
}
