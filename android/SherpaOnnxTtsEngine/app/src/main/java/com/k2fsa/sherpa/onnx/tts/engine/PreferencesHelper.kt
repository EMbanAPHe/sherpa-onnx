import android.content.Context
import android.content.SharedPreferences

class PreferenceHelper(context: Context) {

    private val PREFS_NAME                = "com.k2fsa.sherpa.onnx.tts.engine"
    private val SPEED_KEY                 = "speed"
    private val SID_KEY                   = "speaker_id"
    private val USE_SYSTEM_SPEED_KEY      = "use_system_speed"
    private val PITCH_KEY                 = "pitch"
    private val USE_SYSTEM_PITCH_KEY      = "use_system_pitch"
    private val NUM_THREADS_KEY           = "num_threads"
    private val PROVIDER_KEY              = "provider"
    private val SILENCE_SCALE_KEY         = "silence_scale"
    private val MIN_CLAUSE_WORDS_KEY      = "min_clause_words"
    private val AUDIO_CACHE_KEY           = "audio_cache_enabled"
    private val RMS_NORMALISE_KEY         = "rms_normalise_enabled"
    private val SSML_STRIP_KEY            = "ssml_strip_enabled"

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Speed ─────────────────────────────────────────────────────────────────

    fun setSpeed(value: Float) =
        sharedPreferences.edit().putFloat(SPEED_KEY, value).apply()

    fun getSpeed(): Float =
        sharedPreferences.getFloat(SPEED_KEY, 1.0f)

    // ── System speed pass-through ────────────────────────────────────────────

    fun setUseSystemSpeed(value: Boolean) =
        sharedPreferences.edit().putBoolean(USE_SYSTEM_SPEED_KEY, value).apply()

    fun getUseSystemSpeed(): Boolean =
        sharedPreferences.getBoolean(USE_SYSTEM_SPEED_KEY, true)

    // ── Pitch ─────────────────────────────────────────────────────────────────

    fun setPitch(value: Float) =
        sharedPreferences.edit().putFloat(PITCH_KEY, value).apply()

    fun getPitch(): Float =
        sharedPreferences.getFloat(PITCH_KEY, 1.0f)

    // ── System pitch pass-through ────────────────────────────────────────────

    fun setUseSystemPitch(value: Boolean) =
        sharedPreferences.edit().putBoolean(USE_SYSTEM_PITCH_KEY, value).apply()

    fun getUseSystemPitch(): Boolean =
        sharedPreferences.getBoolean(USE_SYSTEM_PITCH_KEY, true)

    // ── Speaker ID ────────────────────────────────────────────────────────────

    fun setSid(value: Int) =
        sharedPreferences.edit().putInt(SID_KEY, value).apply()

    fun getSid(): Int =
        sharedPreferences.getInt(SID_KEY, 0)

    // ── Thread count ──────────────────────────────────────────────────────────

    fun setNumThreads(value: Int) =
        sharedPreferences.edit().putInt(NUM_THREADS_KEY, value).apply()

    fun getNumThreads(): Int =
        sharedPreferences.getInt(NUM_THREADS_KEY, 4)

    // ── Execution provider ────────────────────────────────────────────────────

    fun setProvider(value: String) =
        sharedPreferences.edit().putString(PROVIDER_KEY, value).apply()

    fun getProvider(): String =
        sharedPreferences.getString(PROVIDER_KEY, "cpu") ?: "cpu"

    // ── Silence scale ─────────────────────────────────────────────────────────

    fun setSilenceScale(value: Float) =
        sharedPreferences.edit().putFloat(SILENCE_SCALE_KEY, value).apply()

    fun getSilenceScale(): Float =
        sharedPreferences.getFloat(SILENCE_SCALE_KEY, 0.05f)

    // ── Clause split threshold ────────────────────────────────────────────────

    fun setMinClauseWords(value: Int) =
        sharedPreferences.edit().putInt(MIN_CLAUSE_WORDS_KEY, value).apply()

    fun getMinClauseWords(): Int =
        sharedPreferences.getInt(MIN_CLAUSE_WORDS_KEY, 6)

    // ── Sentence audio cache ──────────────────────────────────────────────────
    // When enabled, synthesised sentences are cached in memory (up to 20 entries,
    // ~1-2 MB).  Cache hits play instantly with zero synthesis time.

    fun setAudioCacheEnabled(value: Boolean) =
        sharedPreferences.edit().putBoolean(AUDIO_CACHE_KEY, value).apply()

    fun getAudioCacheEnabled(): Boolean =
        sharedPreferences.getBoolean(AUDIO_CACHE_KEY, true)

    // ── RMS audio normalisation ───────────────────────────────────────────────
    // Normalises each audio chunk to a consistent loudness level before playback.
    // Prevents clipping on loud outputs and equalises volume across sentences.

    fun setRmsNormaliseEnabled(value: Boolean) =
        sharedPreferences.edit().putBoolean(RMS_NORMALISE_KEY, value).apply()

    fun getRmsNormaliseEnabled(): Boolean =
        sharedPreferences.getBoolean(RMS_NORMALISE_KEY, true)

    // ── SSML tag stripping ────────────────────────────────────────────────────
    // Removes XML/SSML markup tags from input text before synthesis so they are
    // not spoken literally.

    fun setSsmlStripEnabled(value: Boolean) =
        sharedPreferences.edit().putBoolean(SSML_STRIP_KEY, value).apply()

    fun getSsmlStripEnabled(): Boolean =
        sharedPreferences.getBoolean(SSML_STRIP_KEY, true)
}
