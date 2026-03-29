@file:OptIn(ExperimentalMaterial3Api::class)

package com.k2fsa.sherpa.onnx.tts.engine

import PreferenceHelper
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val THREAD_OPTIONS = listOf(
    1 to "1 thread  (single big core)",
    2 to "2 threads (both big cores — often fastest)",
    4 to "4 threads (default)",
)

private val PROVIDER_OPTIONS = listOf(
    "cpu"     to "CPU  (safe default)",
    "xnnpack" to "XNNPack  (NEON vectorised, ~10-30% faster)",
    "nnapi"   to "NNAPI  (NPU/GPU — requires android-27 build)",
)

class MainActivity : ComponentActivity() {
    private val ttsViewModel: TtsViewModel by viewModels()

    private var mediaPlayer: MediaPlayer? = null
    private lateinit var track: AudioTrack
    private var stopped: Boolean = false

    private var samplesChannel = Channel<FloatArray>(capacity = 128)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        TtsEngine.createTts(this)
        initAudioTrack()

        val preferenceHelper = PreferenceHelper(this)
        applyPlaybackParams(preferenceHelper)

        setContent {
            SherpaOnnxTtsEngineTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = MaterialTheme.colorScheme.background,
                ) {
                    Scaffold(topBar = {
                        val context = LocalContext.current
                        TopAppBar(
                            title   = { Text("Next-gen Kaldi: TTS Engine") },
                            actions = {
                                TextButton(onClick = {
                                    try {
                                        context.startActivity(
                                            Intent("com.android.settings.TTS_SETTINGS")
                                        )
                                    } catch (e: Exception) {
                                        Toast.makeText(context,
                                            "Unable to open system TTS settings.",
                                            Toast.LENGTH_SHORT).show()
                                    }
                                }) { Text("System TTS") }
                            }
                        )
                    }) { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding)) {
                            val scrollState = rememberScrollState()
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState)
                                    .padding(16.dp)
                            ) {

                                // ── Speed slider ──────────────────────────────
                                var speed by remember { mutableStateOf(preferenceHelper.getSpeed()) }
                                Text("Speed  ${String.format("%.1f", speed)}")
                                Slider(
                                    value         = speed,
                                    onValueChange = {
                                        speed = it
                                        TtsEngine.speed = it
                                        preferenceHelper.setSpeed(it)
                                    },
                                    valueRange = MIN_TTS_SPEED..MAX_TTS_SPEED,
                                    modifier   = Modifier.fillMaxWidth(),
                                )

                                // ── System speed toggle ───────────────────────
                                var useSystemSpeed by remember {
                                    mutableStateOf(preferenceHelper.getUseSystemSpeed())
                                }
                                Row(
                                    modifier          = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Use system speed (VAR / app speed setting)",
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked         = useSystemSpeed,
                                        onCheckedChange = { checked ->
                                            useSystemSpeed = checked
                                            TtsEngine.useSystemSpeed = checked
                                            preferenceHelper.setUseSystemSpeed(checked)
                                        },
                                    )
                                }

                                Spacer(Modifier.height(12.dp))
                                Divider()
                                Spacer(Modifier.height(12.dp))

                                // ── Pitch slider ──────────────────────────────
                                // Pitch is applied to the app's AudioTrack via PlaybackParams.
                                // It affects the in-app test player only, not VAR's output.
                                var pitch by remember { mutableStateOf(preferenceHelper.getPitch()) }
                                Text("Pitch  ${String.format("%.2f", pitch)}")
                                Slider(
                                    value         = pitch,
                                    onValueChange = {
                                        pitch = it
                                        preferenceHelper.setPitch(it)
                                        applyPlaybackParams(preferenceHelper)
                                    },
                                    valueRange = 0.5f..2.0f,
                                    steps      = 5,
                                    modifier   = Modifier.fillMaxWidth(),
                                )

                                // ── System pitch toggle ───────────────────────
                                var useSystemPitch by remember {
                                    mutableStateOf(preferenceHelper.getUseSystemPitch())
                                }
                                Row(
                                    modifier          = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Use system pitch",
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked         = useSystemPitch,
                                        onCheckedChange = { checked ->
                                            useSystemPitch = checked
                                            preferenceHelper.setUseSystemPitch(checked)
                                            applyPlaybackParams(preferenceHelper)
                                        },
                                    )
                                }
                                Text(
                                    "Pitch applies to the in-app test player only, not to VAR or other TTS apps.",
                                    fontSize = 11.sp,
                                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )

                                Spacer(Modifier.height(12.dp))
                                Divider()
                                Spacer(Modifier.height(12.dp))

                                // ── Speaker ID (multi-speaker models only) ─────
                                val numSpeakers = TtsEngine.tts!!.numSpeakers()
                                if (numSpeakers > 1) {
                                    OutlinedTextField(
                                        value         = TtsEngine.speakerIdState.value.toString(),
                                        onValueChange = {
                                            TtsEngine.speakerId =
                                                if (it.isBlank()) 0
                                                else try { it.toInt() } catch (_: NumberFormatException) { 0 }
                                            preferenceHelper.setSid(TtsEngine.speakerId)
                                        },
                                        label           = { Text("Speaker ID  (0-${numSpeakers - 1})") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier        = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 12.dp)
                                            .wrapContentHeight(),
                                    )
                                }

                                // ── Test text ─────────────────────────────────
                                val testTextContent = getSampleText(TtsEngine.lang ?: "")
                                var testText     by remember { mutableStateOf(testTextContent) }
                                var startEnabled by remember { mutableStateOf(true) }
                                var playEnabled  by remember { mutableStateOf(false) }
                                var rtfText      by remember { mutableStateOf("") }

                                OutlinedTextField(
                                    value         = testText,
                                    onValueChange = { testText = it },
                                    label         = { Text("Test text") },
                                    maxLines      = 10,
                                    modifier      = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp)
                                        .wrapContentHeight(),
                                    singleLine    = false,
                                )

                                // ── Start / Play / Stop ───────────────────────
                                Row {
                                    Button(
                                        enabled  = startEnabled,
                                        modifier = Modifier.padding(5.dp),
                                        onClick  = {
                                            if (testText.isBlank()) {
                                                Toast.makeText(applicationContext,
                                                    "Please enter some text first",
                                                    Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                            startEnabled = false
                                            playEnabled  = false
                                            stopped      = false
                                            track.pause(); track.flush(); track.play()
                                            rtfText = ""

                                            scope.launch {
                                                for (samples in samplesChannel) {
                                                    if (samples.isEmpty()) break
                                                    track.write(samples, 0, samples.size,
                                                        AudioTrack.WRITE_BLOCKING)
                                                    if (stopped) break
                                                }
                                                while (!samplesChannel.isEmpty) {
                                                    samplesChannel.tryReceive().getOrNull()
                                                }
                                            }

                                            CoroutineScope(Dispatchers.Default).launch {
                                                val t0    = TimeSource.Monotonic.markNow()
                                                val audio = TtsEngine.tts!!.generateWithCallback(
                                                    text     = testText,
                                                    sid      = TtsEngine.speakerId,
                                                    speed    = TtsEngine.speed,
                                                    callback = ::callback,
                                                )
                                                val elapsed  = t0.elapsedNow().inWholeMilliseconds / 1000f
                                                val duration = audio.samples.size /
                                                    TtsEngine.tts!!.sampleRate().toFloat()
                                                val cfg = TtsEngine.tts!!.config.model
                                                val rtf = String.format(
                                                    "Provider: %s   Threads: %d\n" +
                                                    "Elapsed: %.3f s   Audio: %.3f s   RTF: %.3f",
                                                    cfg.provider.uppercase(), cfg.numThreads,
                                                    elapsed, duration, elapsed / duration,
                                                )
                                                scope.launch { samplesChannel.send(FloatArray(0)) }

                                                val wav = application.filesDir.absolutePath + "/generated.wav"
                                                val ok  = audio.samples.isNotEmpty() && audio.save(wav)
                                                if (ok) withContext(Dispatchers.Main) {
                                                    startEnabled = true
                                                    playEnabled  = true
                                                    rtfText      = rtf
                                                }
                                            }
                                        },
                                    ) { Text("Start") }

                                    Button(
                                        modifier = Modifier.padding(5.dp),
                                        enabled  = playEnabled,
                                        onClick  = {
                                            stopped = true
                                            track.pause(); track.flush()
                                            onClickPlay()
                                        },
                                    ) { Text("Play") }

                                    Button(
                                        modifier = Modifier.padding(5.dp),
                                        onClick  = { onClickStop(); startEnabled = true },
                                    ) { Text("Stop") }
                                }

                                if (rtfText.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(rtfText, fontSize = 13.sp,
                                        modifier = Modifier.padding(bottom = 8.dp))
                                }

                                Spacer(Modifier.height(12.dp))
                                Divider()
                                Spacer(Modifier.height(12.dp))

                                // ── Performance (bottom section) ───────────────
                                Text(
                                    "Performance",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize   = 14.sp,
                                    color      = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(8.dp))

                                // Thread count dropdown
                                var threadExpanded by remember { mutableStateOf(false) }
                                var selectedThreads by remember {
                                    mutableStateOf(preferenceHelper.getNumThreads())
                                }
                                val threadLabel = THREAD_OPTIONS
                                    .firstOrNull { it.first == selectedThreads }?.second
                                    ?: "$selectedThreads threads"

                                ExposedDropdownMenuBox(
                                    expanded         = threadExpanded,
                                    onExpandedChange = { threadExpanded = it },
                                    modifier         = Modifier.fillMaxWidth(),
                                ) {
                                    OutlinedTextField(
                                        value            = threadLabel,
                                        onValueChange    = {},
                                        readOnly         = true,
                                        label            = { Text("Thread count") },
                                        trailingIcon     = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(threadExpanded)
                                        },
                                        modifier         = Modifier.fillMaxWidth().menuAnchor(),
                                    )
                                    ExposedDropdownMenu(
                                        expanded         = threadExpanded,
                                        onDismissRequest = { threadExpanded = false },
                                    ) {
                                        THREAD_OPTIONS.forEach { (n, label) ->
                                            DropdownMenuItem(
                                                text    = { Text(label) },
                                                onClick = {
                                                    selectedThreads = n
                                                    preferenceHelper.setNumThreads(n)
                                                    threadExpanded = false
                                                },
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                // Provider dropdown
                                var providerExpanded by remember { mutableStateOf(false) }
                                var selectedProvider by remember {
                                    mutableStateOf(preferenceHelper.getProvider())
                                }
                                val providerLabel = PROVIDER_OPTIONS
                                    .firstOrNull { it.first == selectedProvider }?.second
                                    ?: selectedProvider

                                ExposedDropdownMenuBox(
                                    expanded         = providerExpanded,
                                    onExpandedChange = { providerExpanded = it },
                                    modifier         = Modifier.fillMaxWidth(),
                                ) {
                                    OutlinedTextField(
                                        value            = providerLabel,
                                        onValueChange    = {},
                                        readOnly         = true,
                                        label            = { Text("Execution provider") },
                                        trailingIcon     = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(providerExpanded)
                                        },
                                        modifier         = Modifier.fillMaxWidth().menuAnchor(),
                                    )
                                    ExposedDropdownMenu(
                                        expanded         = providerExpanded,
                                        onDismissRequest = { providerExpanded = false },
                                    ) {
                                        PROVIDER_OPTIONS.forEach { (key, label) ->
                                            DropdownMenuItem(
                                                text    = { Text(label) },
                                                onClick = {
                                                    selectedProvider = key
                                                    preferenceHelper.setProvider(key)
                                                    providerExpanded = false
                                                },
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                // Silence scale
                                var silenceScale by remember {
                                    mutableStateOf(preferenceHelper.getSilenceScale())
                                }
                                Text(
                                    "Silence scale  ${String.format("%.2f", silenceScale)}",
                                    fontSize = 13.sp,
                                )
                                Slider(
                                    value         = silenceScale,
                                    onValueChange = {
                                        silenceScale = it
                                        preferenceHelper.setSilenceScale(it)
                                    },
                                    valueRange = 0.05f..0.30f,
                                    steps      = 4,
                                    modifier   = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    "Controls silence at clause boundaries. Lower = tighter gaps. Tap Apply to take effect.",
                                    fontSize  = 11.sp,
                                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                Spacer(Modifier.height(8.dp))

                                // Apply & Reinitialise
                                var reinitialising by remember { mutableStateOf(false) }
                                Row(
                                    modifier          = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Button(
                                        enabled  = !reinitialising,
                                        modifier = Modifier.weight(1f),
                                        onClick  = {
                                            reinitialising = true
                                            stopped = true
                                            track.pause(); track.flush()
                                            CoroutineScope(Dispatchers.IO).launch {
                                                TtsEngine.reinitialize(applicationContext)
                                                withContext(Dispatchers.Main) {
                                                    track.release()
                                                    initAudioTrack()
                                                    applyPlaybackParams(preferenceHelper)
                                                    reinitialising = false
                                                    Toast.makeText(
                                                        applicationContext,
                                                        "Engine reinitialised: " +
                                                        "${selectedThreads}T / ${selectedProvider.uppercase()}",
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }
                                        },
                                    ) { Text("Apply & Reinitialise Engine") }
                                    if (reinitialising) {
                                        Spacer(Modifier.padding(start = 8.dp))
                                        CircularProgressIndicator(
                                            modifier = Modifier.padding(start = 8.dp))
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Tap Apply after changing threads, provider, or silence scale.",
                                    fontSize = 11.sp,
                                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        stopMediaPlayer()
        if (::track.isInitialized) track.release()
        super.onDestroy()
    }

    /**
     * Apply pitch to the app's own AudioTrack via PlaybackParams (API 23+).
     * Reads the current pitch preference; if system pitch is enabled this is
     * still the stored value (we store system pitch passthrough as a preference
     * flag but apply it when the AudioTrack is live).
     */
    private fun applyPlaybackParams(prefs: PreferenceHelper) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && ::track.isInitialized) {
            val pitchValue = prefs.getPitch()
            try {
                track.playbackParams = PlaybackParams()
                    .setPitch(pitchValue)
                    .setSpeed(1.0f)
                    .allowDefaults()
            } catch (e: Exception) {
                Log.w(TAG, "PlaybackParams failed: ${e.message}")
            }
        }
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
        track.pause(); track.flush()
        stopMediaPlayer()
    }

    private fun callback(samples: FloatArray): Int {
        return if (!stopped) {
            val copy = samples.copyOf()
            scope.launch {
                if (!samplesChannel.trySend(copy).isSuccess)
                    Log.w(TAG, "samplesChannel full, dropped chunk")
            }
            1
        } else {
            track.stop()
            0
        }
    }

    private fun initAudioTrack() {
        val sampleRate = TtsEngine.tts!!.sampleRate()
        val bufLength  = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)

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
