package com.k2fsa.sherpa.onnx.tts.engine

import PreferenceHelper
import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

const val MIN_TTS_SPEED = 0.2f
const val MAX_TTS_SPEED = 3.0f

object TtsEngine {
    var tts: OfflineTts? = null

    // ISO 639-3 language codes: eng, deu, zho, fra …
    var lang:  String? = null
    var lang2: String? = null

    val speedState:              MutableState<Float>   = mutableFloatStateOf(1.0F)
    val speakerIdState:          MutableState<Int>     = mutableIntStateOf(0)
    val useSystemRatePitchState: MutableState<Boolean> = mutableStateOf(false)

    var speed: Float
        get() = speedState.value
        set(value) { speedState.value = value }

    var speakerId: Int
        get() = speakerIdState.value
        set(value) { speakerIdState.value = value }

    var useSystemRatePitch: Boolean
        get() = useSystemRatePitchState.value
        set(value) { useSystemRatePitchState.value = value }

    private var modelDir:          String? = null
    private var modelName:         String? = null
    private var acousticModelName: String? = null
    private var vocoder:           String? = null
    private var voices:            String? = null
    private var ruleFsts:          String? = null
    private var ruleFars:          String? = null
    private var lexicon:           String? = null
    private var dataDir:           String? = null
    private var assets:            AssetManager? = null
    private var isKitten           = false

    init {
        // FIX (Issue 8): Read model config from BuildConfig fields, which are set
        // by build.gradle.kts reading TTSENGINE_* from gradle.properties.
        // CI workflows write gradle.properties before calling assembleDebug —
        // no more fragile sed injection into this file.
        //
        // A non-empty BuildConfig.TTSENGINE_MODEL_DIR (or TTSENGINE_VOICES for Kokoro)
        // activates the CI-injected model.  An empty value falls through to the
        // commented-out examples below (useful for local dev builds).

        val bcModelDir  = BuildConfig.TTSENGINE_MODEL_DIR.trim()
        val bcModelName = BuildConfig.TTSENGINE_MODEL_NAME.trim()
        val bcVoices    = BuildConfig.TTSENGINE_VOICES.trim()
        val bcDataDir   = BuildConfig.TTSENGINE_DATA_DIR.trim()
        val bcLang      = BuildConfig.TTSENGINE_LANG.trim()
        val bcIsKitten  = BuildConfig.TTSENGINE_IS_KITTEN

        if (bcModelDir.isNotEmpty()) {
            modelDir  = bcModelDir
            modelName = bcModelName.ifEmpty { null }
            voices    = bcVoices.ifEmpty { null }
            dataDir   = bcDataDir.ifEmpty { null }
            lang      = bcLang.ifEmpty { "eng" }
            isKitten  = bcIsKitten
            Log.i(TAG, "Model config from BuildConfig: dir=$modelDir name=$modelName voices=$voices")
        } else {
            // Reset all to null so the examples below are the only active config
            modelName         = null
            acousticModelName = null
            vocoder           = null
            voices            = null
            modelDir          = null
            ruleFsts          = null
            ruleFars          = null
            lexicon           = null
            dataDir           = null
            lang              = null
            lang2             = null

            // ── Uncomment exactly ONE example for a local/dev build ──────────

            // kokoro-en-v0_19 (11 English speakers, recommended for audiobooks)
            // modelDir  = "kokoro-en-v0_19"
            // modelName = "model.onnx"
            // voices    = "voices.bin"
            // dataDir   = "kokoro-en-v0_19/espeak-ng-data"
            // lang      = "eng"

            // kokoro-int8-en-v0_19 (faster, slightly lower quality)
            // modelDir  = "kokoro-int8-en-v0_19"
            // modelName = "model.int8.onnx"
            // voices    = "voices.bin"
            // dataDir   = "kokoro-int8-en-v0_19/espeak-ng-data"
            // lang      = "eng"

            // vits-piper-en_US-amy-medium
            // modelDir  = "vits-piper-en_US-amy-medium"
            // modelName = "en_US-amy-medium.onnx"
            // dataDir   = "vits-piper-en_US-amy-medium/espeak-ng-data"
            // lang      = "eng"

            // kitten-nano-en-v0_2-fp16
            // modelDir  = "kitten-nano-en-v0_2-fp16"
            // modelName = "model.fp16.onnx"
            // voices    = "voices.bin"
            // dataDir   = "kitten-nano-en-v0_2-fp16/espeak-ng-data"
            // lang      = "eng"
            // isKitten  = true
        }
    }

    fun createTts(context: Context) {
        Log.i(TAG, "Init Next-gen Kaldi TTS")
        if (tts == null) initTts(context)
    }

    private fun initTts(context: Context) {
        assets = context.assets

        if (dataDir != null) {
            val newDir = copyDataDir(context, dataDir!!)
            dataDir = "$newDir/$dataDir"
        }

        // FIX (Issue 5): load prefs exactly once (original fork called PreferenceHelper twice)
        val prefs = PreferenceHelper(context)
        speed              = prefs.getSpeed()
        speakerId          = prefs.getSid()
        useSystemRatePitch = prefs.getUseSystemRatePitch()

        val config = getOfflineTtsConfig(
            modelDir          = modelDir!!,
            modelName         = modelName         ?: "",
            acousticModelName = acousticModelName ?: "",
            vocoder           = vocoder           ?: "",
            voices            = voices            ?: "",
            lexicon           = lexicon           ?: "",
            dataDir           = dataDir           ?: "",
            dictDir           = "",
            ruleFsts          = ruleFsts          ?: "",
            ruleFars          = ruleFars          ?: "",
            isKitten          = isKitten,
        )

        tts = OfflineTts(assetManager = assets, config = config)
        Log.i(TAG, "TTS initialised. sampleRate=${tts!!.sampleRate()} speakers=${tts!!.numSpeakers()}")
    }

    private fun copyDataDir(context: Context, dataDir: String): String {
        Log.i(TAG, "Copying data dir: $dataDir")
        copyAssets(context, dataDir)
        return context.getExternalFilesDir(null)!!.absolutePath
    }

    private fun copyAssets(context: Context, path: String) {
        try {
            val list = context.assets.list(path)!!
            if (list.isEmpty()) {
                copyFile(context, path)
            } else {
                File("${context.getExternalFilesDir(null)}/$path").mkdirs()
                for (asset in list) {
                    copyAssets(context, if (path.isEmpty()) asset else "$path/$asset")
                }
            }
        } catch (ex: IOException) {
            Log.e(TAG, "Failed to copy $path: $ex")
        }
    }

    private fun copyFile(context: Context, filename: String) {
        try {
            val dest = "${context.getExternalFilesDir(null)}/$filename"
            context.assets.open(filename).use { ins ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (ins.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to copy $filename: $ex")
        }
    }
}
