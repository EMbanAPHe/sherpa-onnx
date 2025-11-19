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
https://developer.android.com/reference/java/util/Locale#getISO3Language()
https://developer.android.com/reference/java/util/Locale#getISO3Country()

eng, USA,
eng, USA, POSIX
eng,
eng, GBR
afr,
afr, NAM
afr, ZAF
agq
agq, CMR
aka,
aka, GHA
amh,
amh, ETH
ara,
ara, 001
ara, ARE
ara, BHR,
deu
deu, AUT
deu, BEL
deu, CHE
deu, ITA
deu, ITA
deu, LIE
deu, LUX
spa,
spa, 419
spa, ARG,
spa, BRA
fra,
fra, BEL,
fra, FRA,

E  Failed to check TTS data, no activity found for Intent
{ act=android.speech.tts.engine.CHECK_TTS_DATA pkg=com.k2fsa.sherpa.chapter5 })

E Failed to get default language from engine com.k2fsa.sherpa.chapter5
Engine failed voice data integrity check (null return)com.k2fsa.sherpa.chapter5
Failed to get default language from engine com.k2fsa.sherpa.chapter5

*/

class TtsService : TextToSpeechService() {
    // --- Performance patch: reduce GC and sentence gap ---
    private val tmpPcm16 = ThreadLocal.withInitial { ByteArray(256 * 1024) } // 256 KB scratch buffer

    private fun floatToPcm16InPlace(
        src: FloatArray,
        srcOffset: Int,
        dst: ByteArray,
        dstOffset: Int,
        frames: Int,
    ) {
        var j = dstOffset
        var i = srcOffset
        val end = srcOffset + frames
        while (i < end) {
            val s = (src[i] * 32767.0f).toInt().coerceIn(-32768, 32767)
            dst[j] = (s and 0xff).toByte()
            dst[j + 1] = ((s ushr 8) and 0xff).toByte()
            i += 1
            j += 2
        }
    }

    // --- Streaming infra: generator thread + queue ---
    private sealed class QueueItem {
        data class Data(val bytes: ByteArray, val length: Int) : QueueItem()
        object End : QueueItem()
        data class Error(val t: Throwable) : QueueItem()
    }

    // --- Silence compression between sentences ---
    private class SilenceTrimmer(
        private val sampleRate: Int,
        private val amplitudeThreshold: Int = 700, // treat quieter-than-this as "silence"
        private val keepMs: Int = 300             // keep up to 300ms of any silent run
    ) {
        // Number of samples to keep in each continuous silence region
        private val keepSamples: Int =
            ((sampleRate.toLong() * keepMs) / 1000L).toInt().coerceAtLeast(1)

        private var consecutiveSilentSamples: Int = 0

        /**
         * Filter the given PCM16 audio in-place, returning a trimmed copy.
         *
         * @param input raw PCM16 little-endian data
         * @param length number of valid bytes in [input]
         * @return new ByteArray with trimmed audio, or null if everything was dropped
         */
        fun filter(input: ByteArray, length: Int): ByteArray? {
            if (length <= 0) return null

            // Worst case output is same size as input
            val out = ByteArray(length)
            var outPos = 0

            var i = 0
            // Step two bytes at a time (one 16-bit sample)
            while (i + 1 < length) {
                val lo = input[i].toInt() and 0xff
                val hi = input[i + 1].toInt()   // sign-extended
                val sample = (hi shl 8) or lo   // signed 16-bit
                val absSample = if (sample < 0) -sample else sample

                val isSilent = absSample < amplitudeThreshold
                val keepSample: Boolean

                if (isSilent) {
                    consecutiveSilentSamples++
                    // Only keep the first [keepSamples] samples of this silent run
                    keepSample = consecutiveSilentSamples <= keepSamples
                } else {
                    // Speech / non-silence: always keep and reset silence counter
                    consecutiveSilentSamples = 0
                    keepSample = true
                }

                if (keepSample) {
                    out[outPos] = input[i]
                    out[outPos + 1] = input[i + 1]
                    outPos += 2
                }

                i += 2
            }

            return if (outPos == 0) {
                null
            } else {
                out.copyOf(outPos)
            }
        }
    }

    @Volatile
    private var currentCancelled: AtomicBoolean? = null

    @Volatile
    private var currentGeneratorThread: Thread? = null

    // --- End patch ---

    override fun onCreate() {
        Log.i(TAG, "onCreate tts service")
        super.onCreate()

        // see https://github.com/Miserlou/Android-SDK-Samples/blob/master/TtsEngine/src/com/example/android/ttsengine/RobotSpeakTtsService.java#L68
        onLoadLanguage(TtsEngine.lang, "", "")
        if (TtsEngine.lang2 != null) {
            onLoadLanguage(TtsEngine.lang2, "", "")
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy tts service")
        super.onDestroy()
    }

    // https://developer.android.com/reference/kotlin/android/speech/tts/TextToSpeechService#onislanguageavailable
    override fun onIsLanguageAvailable(
        _lang: String?,
        _country: String?,
        _variant: String?
    ): Int {
        val requested = _lang ?: ""

        // Your primary engine language – provide a fallback if not set yet
        val primary = TtsEngine.lang ?: "eng" // or "en"

        // Normalize common variants Android might send
        val normalized = when {
            requested.equals("eng", ignoreCase = true) -> "eng"
            // Treat any "en", "en-AU", "en_US", etc. as primary
            requested.startsWith("en", ignoreCase = true) -> primary
            else -> requested
        }

        return if (
            normalized.equals(primary, ignoreCase = true) ||
            (TtsEngine.lang2 != null && normalized.equals(TtsEngine.lang2, ignoreCase = true))
        ) {
            TextToSpeech.LANG_AVAILABLE
        } else {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onGetLanguage(): Array<String> {
        // Fallback if lang hasn't been initialized yet
        val lang = TtsEngine.lang ?: "eng" // or "en" if you prefer the 2-letter form
        return arrayOf(lang, "", "")
    }

    // https://developer.android.com/reference/kotlin/android/speech/tts/TextToSpeechService#onLoadLanguage(kotlin.String,%20kotlin.String,%20kotlin.String)
    override fun onLoadLanguage(_lang: String?, _country: String?, _variant: String?): Int {
        Log.i(TAG, "onLoadLanguage: $_lang, $_country")
        val lang = _lang ?: ""

        return if (lang == TtsEngine.lang || lang == TtsEngine.lang2) {
            Log.i(TAG, "creating tts, lang :$lang")
            TtsEngine.createTts(application)
            TextToSpeech.LANG_AVAILABLE
        } else {
            Log.i(TAG, "lang $lang not supported, tts engine lang: ${TtsEngine.lang}, ${TtsEngine.lang2}")
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onStop() {
        Log.i(TAG, "onStop()")
        val cancelled = currentCancelled
        cancelled?.set(true)
        currentGeneratorThread?.interrupt()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) {
            return
        }

        val language = request.language
        val country = request.country
        val variant = request.variant
        val text = request.charSequenceText.toString()

        val ret = onIsLanguageAvailable(language, country, variant)
        if (ret == TextToSpeech.LANG_NOT_SUPPORTED) {
            callback.error()
            return
        }

        Log.i(TAG, "text: $text")

        val tts = TtsEngine.tts ?: run {
            // Try to initialize if it hasn't been created yet
            TtsEngine.createTts(applicationContext)

            val created = TtsEngine.tts
            if (created == null) {
                // If we still don't have a TTS instance, fail this request gracefully
                callback.error()
                return
            } else {
                created
            }
        }

        val sampleRate = tts.sampleRate()
        callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)

        if (text.isBlank()) {
            callback.done()
            return
        }

        val effectiveSpeed = if (TtsEngine.useSystemRatePitch) {
            val sysRate = request.speechRate    // 100 = normal
            (sysRate / 100.0f).coerceIn(0.2f, 3.0f)
        } else {
            TtsEngine.speed
        }

        Log.i(
            TAG,
            "text: $text, effectiveSpeed=$effectiveSpeed, speechRate=${request.speechRate}, useSystem=${TtsEngine.useSystemRatePitch}"
        )

        // Set up streaming infrastructure
        val queue = LinkedBlockingQueue<QueueItem>(32)
        val cancelled = AtomicBoolean(false)
        currentCancelled = cancelled
        
        // New: stateful trimmer for this utterance
        val silenceTrimmer = SilenceTrimmer(sampleRate)

        // Generator thread that runs Kokoro and converts float -> PCM16
        val generatorThread = Thread {
            try {
                tts.generateStreaming(
                    text = text,
                    sid = TtsEngine.speakerId,
                    speed = effectiveSpeed,
                    callback = { floatChunk ->
                        if (cancelled.get()) {
                            return@generateStreaming 0
                        }

                        val frames = floatChunk.size
                        if (frames == 0) {
                            return@generateStreaming 1
                        }

                        // Convert float samples to PCM16
                        val pcmBytes = ByteArray(frames * 2)
                        floatToPcm16InPlace(
                            src = floatChunk,
                            srcOffset = 0,
                            dst = pcmBytes,
                            dstOffset = 0,
                            frames = frames,
                        )

                        // Trim long silent runs
                        val filtered = silenceTrimmer.filter(pcmBytes, pcmBytes.size)
                        if (filtered == null || filtered.isEmpty()) {
                            // Entire chunk was dropped as excess silence
                            return@generateStreaming 1
                        }

                        try {
                            queue.put(QueueItem.Data(filtered, filtered.size))
                        } catch (ie: InterruptedException) {
                            if (cancelled.get()) {
                                return@generateStreaming 0
                            } else {
                                queue.offer(QueueItem.Error(ie))
                                return@generateStreaming 0
                            }
                        }

                        if (cancelled.get()) {
                            return@generateStreaming 0
                        }

                        1
                    }
                )

                if (!cancelled.get()) {
                    queue.offer(QueueItem.End)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error in generateStreaming", t)
                queue.offer(QueueItem.Error(t))
            }
        }.apply {
            name = "SherpaTtsGenerator"
            isDaemon = true
        }

        currentGeneratorThread = generatorThread
        generatorThread.start()

        try {
            while (true) {
                val item = queue.take()
                when (item) {
                    is QueueItem.Data -> {
                        if (cancelled.get()) {
                            break
                        }

                        val data = item.bytes
                        val total = item.length
                        if (total <= 0) {
                            // Nothing to play in this chunk
                            continue
                        }

                        // Respect Android's maximum buffer size. Fallback minimum for safety.
                        val maxBytes = callback.maxBufferSize.coerceAtLeast(4096)

                        var offset = 0
                        while (offset < total && !cancelled.get()) {
                            // Don't exceed maxBytes, and don't run past the end
                            val remaining = total - offset
                            var bytesThis = minOf(maxBytes, remaining)

                            // Ensure we send a whole number of PCM16 frames (2 bytes per frame)
                            if (bytesThis % 2 != 0) {
                                bytesThis -= 1
                            }
                            if (bytesThis <= 0) {
                                break
                            }

                            callback.audioAvailable(data, offset, bytesThis)
                            offset += bytesThis
                        }
                    }
                    is QueueItem.End -> {
                        // Normal EOS
                        break
                    }
                    is QueueItem.Error -> {
                        Log.e(TAG, "Generator thread error", item.t)
                        callback.error(TextToSpeech.ERROR_OUTPUT)
                        cancelled.set(true)
                        break
                    }
                }
            }

            if (!cancelled.get()) {
                callback.done()
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "onSynthesizeText interrupted", e)
            cancelled.set(true)
        } finally {
            cancelled.set(true)
            currentGeneratorThread = null
        }
    }

    private fun floatArrayToByteArray(audio: FloatArray): ByteArray {
        // byteArray is actually a ShortArray
        val byteArray = ByteArray(audio.size * 2)
        for (i in audio.indices) {
            val sample = (audio[i] * 32767).toInt()
            byteArray[2 * i] = sample.toByte()
            byteArray[2 * i + 1] = (sample shr 8).toByte()
        }
        return byteArray
    }
    
    companion object {
        private const val TAG = "TtsService"
    }
}
