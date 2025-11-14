package com.k2fsa.sherpa.onnx.tts.engine

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log

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

    override fun onStop() {}

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

        // Note that AudioFormat.ENCODING_PCM_FLOAT requires API level >= 24
        // callback.start(tts.sampleRate(), AudioFormat.ENCODING_PCM_FLOAT, 1)

        callback.start(tts.sampleRate(), AudioFormat.ENCODING_PCM_16BIT, 1)

        if (text.isBlank() || text.isEmpty()) {
            callback.done()
            return
        }

        val ttsCallback: (FloatArray) -> Int = fun(chunk: FloatArray): Int {
    val scratch = tmpPcm16.get()
    val maxBytes = callback.maxBufferSize.coerceAtLeast(4096)

    var srcOff = 0
    val totalFrames = chunk.size
    val maxFramesPerWrite = (maxBytes / 2).coerceAtMost(scratch.size / 2)

    while (srcOff < totalFrames) {
        val framesThis = minOf(maxFramesPerWrite, totalFrames - srcOff)

        floatToPcm16InPlace(
            src = chunk,
            srcOffset = srcOff,
            dst = scratch,
            dstOffset = 0,
            frames = framesThis,
        )

        callback.audioAvailable(scratch, 0, framesThis * 2)
        srcOff += framesThis
    }
    return 1
}


        Log.i(TAG, "text: $text")
        tts.generateStreaming(
            text = text,
            sid = TtsEngine.speakerId,
            speed = TtsEngine.speed,
            callback = ttsCallback,
        )

        callback.done()
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
}
