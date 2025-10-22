package com.k2fsa.sherpa.onnx.tts.engine

import PreferenceHelper
import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "TtsEngine"

object TtsEngine {
    var tts: OfflineTts? = null

    // https://en.wikipedia.org/wiki/ISO_639-3
    var lang: String? = null
    var lang2: String? = null

    val speedState: MutableState<Float> = mutableFloatStateOf(1.0F)
    val speakerIdState: MutableState<Int> = mutableIntStateOf(0)

    var speed: Float
        get() = speedState.value
        set(value) { speedState.value = value }

    var speakerId: Int
        get() = speakerIdState.value
        set(value) { speakerIdState.value = value }

    // Model configuration fields (nullable to allow CI/runtime injection)
    private var modelDir: String? = null
    private var modelName: String? = null
    private var acousticModelName: String? = null // Matcha
    private var vocoder: String? = null          // Matcha
    private var voices: String? = null           // Kokoro
    private var ruleFsts: String? = null
    private var ruleFars: String? = null
    private var lexicon: String? = null
    private var dataDir: String? = null
    private var assets: AssetManager? = null
    private var isKitten = false

    init {
        // CI-friendly defaults: nothing hard-coded; we'll auto-detect below
        modelName = null
        acousticModelName = null
        vocoder = null
        voices = null
        modelDir = null
        ruleFsts = null
        ruleFars = null
        lexicon = null
        dataDir = null
        lang = null
        lang2 = null
        // isKitten = false
        // If you want to pin a specific model, you can set the fields above.
        // Otherwise, we auto-detect "assets/model/" laid down by the CI workflow.
    }

    fun createTts(context: Context) {
        Log.i(TAG, "Init Next-gen Kaldi TTS")
        if (tts == null) {
            initTts(context)
        }
    }

    /**
     * Be robust:
     * - Auto-detect the model directory (prefer "assets/model/").
     * - Avoid !! on nullable fields.
     * - Validate required files to prevent NPEs.
     */
    private fun initTts(context: Context) {
        try {
            assets = context.assets
            val am = assets!!

            // 1) Detect model directory if not set (our CI puts files in assets/model/)
            val finalModelDir = modelDir ?: findModelDir(am)
            if (finalModelDir == null) {
                Log.e(TAG, "No model directory found in assets. Expected 'model/model.onnx'.")
                return
            }

            // 2) Default filenames for Kokoro-style packs
            val finalModelName = modelName ?: "model.onnx"
            val hasVoices = assetExists(am, "$finalModelDir/voices.bin")
            val finalVoices = voices ?: if (hasVoices) "voices.bin" else ""

            // Optional lexicons (English)
            val lexUs = if (assetExists(am, "$finalModelDir/lexicon-us-en.txt")) "$finalModelDir/lexicon-us-en.txt" else ""
            val lexGb = if (assetExists(am, "$finalModelDir/lexicon-gb-en.txt")) "$finalModelDir/lexicon-gb-en.txt" else ""
            // getOfflineTtsConfig accepts either empty string or comma-separated list
            val finalLexicon = when {
                lexicon != null -> lexicon!!
                lexUs.isNotEmpty() && lexGb.isNotEmpty() -> "$lexUs,$lexGb"
                lexUs.isNotEmpty() -> lexUs
                lexGb.isNotEmpty() -> lexGb
                else -> ""
            }

            // 3) If an embedded espeak-ng-data exists, copy it to external files and point dataDir there
            val embeddedEspeak = "$finalModelDir/espeak-ng-data"
            val finalDataDir = when {
                dataDir != null -> {
                    val newRoot = copyDataDir(context, dataDir!!)
                    "$newRoot/${dataDir!!}"
                }
                assetExistsDir(am, embeddedEspeak) -> {
                    val newRoot = copyDataDir(context, embeddedEspeak)
                    "$newRoot/$embeddedEspeak"
                }
                else -> ""
            }

            // 4) Build config
            val config = getOfflineTtsConfig(
                modelDir = finalModelDir,
                modelName = finalModelName,
                acousticModelName = acousticModelName ?: "",
                vocoder = vocoder ?: "",
                voices = finalVoices,
                lexicon = finalLexicon,
                dataDir = finalDataDir,
                dictDir = "",
                ruleFsts = ruleFsts ?: "",
                ruleFars = ruleFars ?: "",
                isKitten = isKitten,
            )

            // 5) Load user prefs
            speed = PreferenceHelper(context).getSpeed()
            speakerId = PreferenceHelper(context).getSid()

            // 6) Create engine (use AssetManager for assets-backed loading)
            tts = OfflineTts(assetManager = am, config = config)
            Log.i(TAG, "TTS initialized (dir=$finalModelDir, model=$finalModelName)")
        } catch (t: Throwable) {
            Log.e(TAG, "TTS init failed: ${t.message}", t)
            tts = null
        }
    }

    // ---------- Helpers ----------

    private fun findModelDir(am: AssetManager): String? {
        // Prefer CI layout: assets/model/model.onnx
        return when {
            assetExists(am, "model/model.onnx") -> "model"
            assetExists(am, "kokoro-en-v0_19/model.onnx") -> "kokoro-en-v0_19"
            assetExists(am, "kokoro-multi-lang-v1_0/model.onnx") -> "kokoro-multi-lang-v1_0"
            assetExists(am, "kitten-nano-en-v0_1-fp16/model.fp16.onnx") -> "kitten-nano-en-v0_1-fp16"
            else -> null
        }
    }

    private fun assetExists(am: AssetManager, path: String): Boolean =
        try {
            am.open(path).close()
            true
        } catch (_: Exception) {
            false
        }

    // Coarse directory existence check: try listing it
    private fun assetExistsDir(am: AssetManager, path: String): Boolean =
        try {
            am.list(path)?.isNotEmpty() == true
        } catch (_: Exception) {
            false
        }

    private fun copyDataDir(context: Context, dataDir: String): String {
        Log.i(TAG, "Copying data dir from assets: $dataDir")
        copyAssets(context, dataDir)
        val newDataDir = context.getExternalFilesDir(null)!!.absolutePath
        Log.i(TAG, "newDataDir: $newDataDir")
        return newDataDir
    }

    private fun copyAssets(context: Context, path: String) {
        try {
            val list = context.assets.list(path)
            if (list == null || list.isEmpty()) {
                copyFile(context, path)
            } else {
                val fullPath = "${context.getExternalFilesDir(null)}/$path"
                File(fullPath).mkdirs()
                for (name in list) {
                    val next = if (path.isEmpty()) name else "$path/$name"
                    copyAssets(context, next)
                }
            }
        } catch (ex: IOException) {
            Log.e(TAG, "Failed to copy $path. $ex")
        }
    }

    private fun copyFile(context: Context, filename: String) {
        try {
            context.assets.open(filename).use { ins ->
                val outPath = "${context.getExternalFilesDir(null)}/$filename"
                File(outPath).parentFile?.mkdirs()
                FileOutputStream(outPath).use { outs ->
                    val buf = ByteArray(8 * 1024)
                    var read: Int
                    while (ins.read(buf).also { read = it } != -1) {
                        outs.write(buf, 0, read)
                    }
                    outs.flush()
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to copy $filename, $ex")
        }
    }
}
```0
