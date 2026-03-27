@file:OptIn(ExperimentalMaterial3Api::class)

package com.k2fsa.sherpa.onnx.tts.engine

import PreferenceHelper
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.k2fsa.sherpa.onnx.tts.engine.ui.theme.SherpaOnnxTtsEngineTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.TimeSource

const val TAG = "sherpa-onnx-tts-engine"

class MainActivity : ComponentActivity() {
    private val ttsViewModel: TtsViewModel by viewModels()

    private var mediaPlayer: MediaPlayer? = null
    private lateinit var track: AudioTrack
    private var stopped: Boolean = false

    // FIX (Issue 9): The fork changed capacity=128 to the default (rendezvous),
    // which causes the callback() to block until the player coroutine drains each item.
    // Under heavy load this creates synthesis stalls in the app's own test UI.
    // Restore capacity=128 and SupervisorJob so child coroutine failures don't
    // cancel the parent scope.
    private var samplesChannel = Channel<FloatArray>(capacity = 128)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "Start to initialize TTS")
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
                    Scaffold(topBar = {
                        val context = LocalContext.current
                        TopAppBar(
                            title = { Text("Next-gen Kaldi: TTS Engine") },
                            actions = {
                                TextButton(onClick = {
                                    try {
                                        context.startActivity(
                                            Intent("com.android.settings.TTS_SETTINGS")
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Unable to open system TTS settings", e)
                                        Toast.makeText(
                                            context,
                                            "Unable to open system TTS settings.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }) {
                                    Text("System TTS")
                                }
                            }
                        )
                    }) { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                // Speed slider
                                Text("Speed " + String.format("%.1f", TtsEngine.speed))
                                Slider(
                                    value        = TtsEngine.speedState.value,
                                    onValueChange = {
                                        TtsEngine.speed = it
                                        preferenceHelper.setSpeed(it)
                                    },
                                    valueRange = MIN_TTS_SPEED..MAX_TTS_SPEED,
                                    modifier   = Modifier.fillMaxWidth()
                                )

                                // System speed/pitch toggle
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                ) {
                                    Text(
                                        "Allow system setting speed/pitch",
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked         = TtsEngine.useSystemRatePitchState.value,
                                        onCheckedChange = { checked ->
                                            TtsEngine.useSystemRatePitch = checked
                                            preferenceHelper.setUseSystemRatePitch(checked)
                                        }
                                    )
                                }

                                val testTextContent = getSampleText(TtsEngine.lang ?: "")
                                var testText    by remember { mutableStateOf(testTextContent) }
                                var startEnabled by remember { mutableStateOf(true) }
                                var playEnabled  by remember { mutableStateOf(false) }
                                var rtfText      by remember { mutableStateOf("") }
                                val scrollState  = rememberScrollState(0)

                                val numSpeakers = TtsEngine.tts!!.numSpeakers()
                                if (numSpeakers > 1) {
                                    OutlinedTextField(
                                        value       = TtsEngine.speakerIdState.value.toString(),
                                        onValueChange = {
                                            TtsEngine.speakerId = if (it.isEmpty() || it.isBlank()) {
                                                0
                                            } else {
                                                try { it.toInt() } catch (ex: NumberFormatException) { 0 }
                                            }
                                            preferenceHelper.setSid(TtsEngine.speakerId)
                                        },
                                        label   = { Text("Speaker ID: (0-${numSpeakers - 1})") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 16.dp)
                                            .wrapContentHeight(),
                                    )
                                }

                                OutlinedTextField(
                                    value         = testText,
                                    onValueChange = { testText = it },
                                    label         = { Text("Please input your text here") },
                                    maxLines      = 10,
                                    modifier      = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 16.dp)
                                        .verticalScroll(scrollState)
                                        .wrapContentHeight(),
                                    singleLine = false,
                                )

                                Row {
                                    // Start button
                                    Button(
                                        enabled  = startEnabled,
                                        modifier = Modifier.padding(5.dp),
                                        onClick  = {
                                            if (testText.isBlank()) {
                                                Toast.makeText(
                                                    applicationContext,
                                                    "Please input some text to generate",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                startEnabled = false
                                                playEnabled  = false
                                                stopped      = false

                                                track.pause()
                                                track.flush()
                                                track.play()
                                                rtfText = ""

                                                // Consumer coroutine — plays samples as they arrive
                                                scope.launch {
                                                    for (samples in samplesChannel) {
                                                        if (samples.isEmpty()) break
                                                        track.write(
                                                            samples, 0, samples.size,
                                                            AudioTrack.WRITE_BLOCKING
                                                        )
                                                        if (stopped) break
                                                    }
                                                    // Drain any remaining items
                                                    while (!samplesChannel.isEmpty) {
                                                        samplesChannel.tryReceive().getOrNull()
                                                    }
                                                }

                                                // Generator coroutine — runs TTS inference
                                                CoroutineScope(Dispatchers.Default).launch {
                                                    val timeSource = TimeSource.Monotonic
                                                    val startTime  = timeSource.markNow()

                                                    val audio = TtsEngine.tts!!.generateWithCallback(
                                                        text     = testText,
                                                        sid      = TtsEngine.speakerId,
                                                        speed    = TtsEngine.speed,
                                                        callback = ::callback,
                                                    )

                                                    val elapsed       = startTime.elapsedNow().inWholeMilliseconds / 1000f
                                                    val audioDuration = audio.samples.size / TtsEngine.tts!!.sampleRate().toFloat()
                                                    val RTF = String.format(
                                                        "Threads: %d\nElapsed: %.3f s\nAudio: %.3f s\nRTF: %.3f",
                                                        TtsEngine.tts!!.config.model.numThreads,
                                                        elapsed, audioDuration, elapsed / audioDuration
                                                    )

                                                    // Signal the consumer that generation is done
                                                    scope.launch { samplesChannel.send(FloatArray(0)) }

                                                    val filename = application.filesDir.absolutePath + "/generated.wav"
                                                    val ok       = audio.samples.isNotEmpty() && audio.save(filename)
                                                    if (ok) {
                                                        withContext(Dispatchers.Main) {
                                                            startEnabled = true
                                                            playEnabled  = true
                                                            rtfText      = RTF
                                                        }
                                                    }
                                                }
                                            }
                                        }) { Text("Start") }

                                    // Play button (replays the saved WAV)
                                    Button(
                                        modifier = Modifier.padding(5.dp),
                                        enabled  = playEnabled,
                                        onClick  = {
                                            stopped = true
                                            track.pause()
                                            track.flush()
                                            onClickPlay()
                                        }) { Text("Play") }

                                    // Stop button
                                    Button(
                                        modifier = Modifier.padding(5.dp),
                                        onClick  = {
                                            onClickStop()
                                            startEnabled = true
                                        }) { Text("Stop") }
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
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun onClickPlay() {
        val filename = application.filesDir.absolutePath + "/generated.wav"
        stopMediaPlayer()
        mediaPlayer = MediaPlayer.create(applicationContext, Uri.fromFile(File(filename)))
        mediaPlayer?.start()
    }

    private fun onClickStop() {
        stopped = true
        track.pause()
        track.flush()
        stopMediaPlayer()
    }

    // Called from C++ (TTS callback) — uses trySend to avoid blocking the synthesis thread
    private fun callback(samples: FloatArray): Int {
        return if (!stopped) {
            val samplesCopy = samples.copyOf()
            scope.launch {
                val ok = samplesChannel.trySend(samplesCopy).isSuccess
                if (!ok) Log.w(TAG, "samplesChannel full, dropped ${samplesCopy.size} samples")
            }
            1
        } else {
            track.stop()
            Log.i(TAG, "callback: stopped, returning 0")
            0
        }
    }

    private fun initAudioTrack() {
        val sampleRate = TtsEngine.tts!!.sampleRate()
        val bufLength  = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        Log.i(TAG, "sampleRate: $sampleRate, bufLength: $bufLength")

        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        track = AudioTrack(attr, format, bufLength, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE)
        track.play()
    }
}
