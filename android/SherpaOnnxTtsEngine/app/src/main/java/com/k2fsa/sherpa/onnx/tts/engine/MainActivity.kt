@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.k2fsa.sherpa.onnx.tts.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

    // https://developer.android.com/reference/kotlin/android/media/AudioTrack
    private lateinit var track: AudioTrack

    private var stopped: Boolean = false
    private val samplesChannel = Channel<FloatArray>(capacity = Channel.UNLIMITED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "Start to initialize TTS")
        // NOTE: TtsEngine must have a non-null modelDir configured in its init path.
        TtsEngine.createTts(this)
        Log.i(TAG, "Finish initializing TTS")

        Log.i(TAG, "Start to initialize AudioTrack")
        initAudioTrack()
        Log.i(TAG, "Finish initializing AudioTrack")

        val preferenceHelper = PreferenceHelper(this)

        setContent {
            SherpaOnnxTtsEngineTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Scaffold(
                        topBar = { TopAppBar(title = { Text("Next-gen Kaldi: TTS Engine") }) }
                    ) { pad ->
                        Box(modifier = Modifier.padding(pad)) {
                            Column(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                // Speed slider
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

                                Spacer(Modifier.height(8.dp))

                                // Buttons row
                                Row {
                                    Button(
                                        modifier = Modifier.padding(5.dp),
                                        enabled = startEnabled,
                                        onClick = {
                                            startEnabled = false
                                            playEnabled = false
                                            rtfText = ""
                                            stopped = false

                                            // Synthesize to a wav file. Adjust if your OfflineTts API differs.
                                            CoroutineScope(Dispatchers.Default).launch {
                                                runCatching {
                                                    val out = File(filesDir, "generated.wav").absolutePath
                                                    TtsEngine.tts?.let { tts ->
                                                        tts.generate(
                                                            text = testText,
                                                            sid = TtsEngine.speakerId,
                                                            speed = TtsEngine.speed,
                                                            outputWavePath = out
                                                        )
                                                    } ?: error("TTS engine not initialized")
                                                }.onSuccess {
                                                    playEnabled = true
                                                    rtfText = "RTF: n/a"
                                                }.onFailure { e ->
                                                    Log.e(TAG, "TTS generation failed", e)
                                                    startEnabled = true
                                                }
                                            }
                                        }
                                    ) {
                                        Text("Start")
                                    }

                                    Button(
                                        modifier = Modifier.padding(5.dp),
                                        enabled = playEnabled,
                                        onClick = {
                                            stopped = true
                                            if (this@MainActivity::track.isInitialized) {
                                                track.pause()
                                                track.flush()
                                            }
                                            onClickPlay()
                                        }
                                    ) {
                                        Text("Play")
                                    }

                                    Button(
                                        modifier = Modifier.padding(5.dp),
                                        onClick = {
                                            onClickStop()
                                            startEnabled = true
                                        }
                                    ) {
                                        Text("Stop")
                                    }
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
        if (this::track.isInitialized) {
            try {
                track.release()
            } catch (e: Throwable) {
                Log.w(TAG, "AudioTrack release failed", e)
            }
        }
        super.onDestroy()
    }

    private fun stopMediaPlayer() {
        mediaPlayer?.run {
            try {
                stop()
            } catch (_: Throwable) {
                // ignore; safe-stop
            }
            try {
                release()
            } catch (_: Throwable) {
                // ignore
            }
        }
        mediaPlayer = null
    }

    private fun onClickPlay() {
        val filename = File(filesDir, "generated.wav").absolutePath
        stopMediaPlayer()
        mediaPlayer = MediaPlayer.create(
            applicationContext,
            Uri.fromFile(File(filename))
        )
        mediaPlayer?.start()
    }

    private fun onClickStop() {
        stopped = true
        if (this::track.isInitialized) {
            try {
                track.pause()
                track.flush()
            } catch (e: Throwable) {
                Log.w(TAG, "AudioTrack pause/flush failed", e)
            }
        }
        stopMediaPlayer()
    }

    // Example callback if you later wire streaming synthesis
    @Suppress("UNUSED_PARAMETER")
    private fun callback(samples: FloatArray): Int {
        return if (!stopped) {
            val copy = samples.copyOf()
            CoroutineScope(Dispatchers.IO).launch {
                samplesChannel.send(copy)
            }
            1
        } else {
            if (this::track.isInitialized) {
                try {
                    track.stop()
                } catch (e: Throwable) {
                    Log.w(TAG, "AudioTrack stop failed", e)
                }
            }
            Log.i(TAG, " return 0")
            0
        }
    }

    private fun initAudioTrack() {
        val sr = TtsEngine.tts?.sampleRate() ?: 22050
        val buf = AudioTrack.getMinBufferSize(
            sr,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        Log.i(TAG, "sampleRate: $sr, buffLength: $buf")

        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sr)
            .build()

        track = AudioTrack(
            attr,
            format,
            buf,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track.play()
    }
}
