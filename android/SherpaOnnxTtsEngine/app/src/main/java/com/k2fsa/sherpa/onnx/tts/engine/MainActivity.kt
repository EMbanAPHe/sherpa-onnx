l// File: android/SherpaOnnxTtsEngine/app/src/main/java/com/k2fsa/sherpa/onnx/tts/engine/MainActivity.kt
@file:OptIn(ExperimentalMaterial3Api::class)

package com.k2fsa.sherpa.onnx.tts.engine

import PreferenceHelper
import android.app.ComponentActivity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.k2fsa.sherpa.onnx.tts.engine.ui.theme.SherpaOnnxTtsEngineTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "sherpa-onnx-tts-engine"

class MainActivity : ComponentActivity() {

    // TODO(fangjun): Save settings in ttsViewModel
    private val ttsViewModel: TtsViewModel by viewModels()

    private var mediaPlayer: MediaPlayer? = null

    // AudioTrack is created after TTS is initialized
    private lateinit var track: AudioTrack

    private var stopped: Boolean = false
    private var samplesChannel = Channel<FloatArray>(capacity = Channel.UNLIMITED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "Start to initialize TTS")
        TtsEngine.createTts(this)
        val tts = TtsEngine.tts
        if (tts == null) {
            Log.e(TAG, "TTS init failed (null). Check logs / assets.")
            Toast.makeText(this, "TTS init failed. Missing/invalid assets.", Toast.LENGTH_LONG).show()
            setContent {
                SherpaOnnxTtsEngineTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize().padding(24.dp)) {
                            Text("TTS init failed. Please reinstall or check model assets.", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            return
        }
        Log.i(TAG, "Finish initializing TTS")

        Log.i(TAG, "Start to initialize AudioTrack")
        initAudioTrack()   // now safe: guarded inside the function
        Log.i(TAG, "Finish initializing AudioTrack")

        val preferenceHelper = PreferenceHelper(this)

        setContent {
            SherpaOnnxTtsEngineTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Scaffold(topBar = {
                        TopAppBar(title = { Text("Next-gen Kaldi: TTS Engine") })
                    }) { padding ->
                        Box(modifier = Modifier.padding(padding)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                // Speed control
                                Column {
                                    Text("Speed " + String.format("%.1f", TtsEngine.speed))
                                    Slider(
                                        value = TtsEngine.speedState.value,
                                        onValueChange = {
                                            TtsEngine.speed = it
                                            preferenceHelper.setSpeed(it)
                                        },
                                        valueRange = 0.2F..3.0F,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                val testTextContent = getSampleText(TtsEngine.lang ?: "")
                                var testText by remember { mutableStateOf(testTextContent) }
                                var startEnabled by remember { mutableStateOf(true) }
                                var playEnabled by remember { mutableStateOf(false) }
                                var rtfText by remember { mutableStateOf("") }
                                val scrollState = rememberScrollState(0)

                                // (Your existing UI above this point omitted for brevity)
                                // --- Buttons row (Start / Play / Stop) ---

                                Row {
                                    Button(
                                        enabled = startEnabled,
                                        modifier = Modifier.padding(5.dp),
                                        onClick = {
                                            // your generation logic (unchanged)
                                            // ensure state flags are in a consistent state
                                            playEnabled = false
                                            startEnabled = false
                                            stopped = false

                                            // Example: launch your synthesis job here etc...
                                            // When done, set playEnabled/startEnabled as you had before.
                                        }
                                    ) { Text("Start") }

                                    Button(
                                        modifier = Modifier.padding(5.dp),
                                        enabled = playEnabled,
                                        onClick = {
                                            stopped = true
                                            if (::track.isInitialized) {
                                                try {
                                                    track.pause()
                                                    track.flush()
                                                } catch (_: Throwable) { /* ignore */ }
                                            }
                                            onClickPlay()
                                        }
                                    ) { Text("Play") }

                                    Button(
                                        modifier = Modifier.padding(5.dp),
                                        onClick = {
                                            onClickStop()
                                            startEnabled = true
                                        }
                                    ) { Text("Stop") }
                                }

                                if (rtfText.isNotEmpty()) {
                                    Row { Text(rtfText) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        stopMediaPlayer()
        super.onDestroy()
    }

    private fun stopMediaPlayer() {
        try {
            mediaPlayer?.stop()
        } catch (_: Throwable) { }
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun onClickPlay() {
        val filename = application.filesDir.absolutePath + "/generated.wav"
        stopMediaPlayer()
        mediaPlayer = MediaPlayer.create(
            applicationContext,
            Uri.fromFile(File(filename))
        )
        mediaPlayer?.start()
    }

    private fun onClickStop() {
        stopped = true
        if (::track.isInitialized) {
            try {
                track.pause()
                track.flush()
            } catch (_: Throwable) { }
        }
        stopMediaPlayer()
    }

    // this function is called from C++
    @Suppress("unused")
    private fun callback(samples: FloatArray): Int {
        if (!stopped) {
            val samplesCopy = samples.copyOf()
            CoroutineScope(Dispatchers.IO).launch {
                samplesChannel.send(samplesCopy)
            }
            return 1
        } else {
            if (::track.isInitialized) {
                try { track.stop() } catch (_: Throwable) { }
            }
            Log.i(TAG, " return 0")
            return 0
        }
    }

    private fun initAudioTrack() {
        // Avoid NPE: tts can be null if init failed; use a safe fallback sample rate (24k for Kokoro)
        val sampleRate = TtsEngine.tts?.sampleRate() ?: 24_000
        var bufLength = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        if (bufLength <= 0) bufLength = sampleRate // crude but safe fallback
        Log.i(TAG, "sampleRate: $sampleRate, buffLength: $bufLength")

        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        track = AudioTrack(
            attr, format, bufLength, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        try { track.play() } catch (_: Throwable) { }
    }
}

/** Minimal helper so the screen has something to speak if no language-specific sample is supplied. */
private fun getSampleText(lang: String): String =
    when (lang.lowercase()) {
        "eng", "en", "en-us", "en-gb" -> "How are you doing today? This is a text to speech engine."
        else -> "Hello. This is a text to speech engine demo."
    }
```0
