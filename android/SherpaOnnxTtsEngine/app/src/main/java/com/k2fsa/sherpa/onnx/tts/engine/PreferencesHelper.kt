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

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Speed ─────────────────────────────────────────────────────────────────

    fun setSpeed(value: Float) =
        sharedPreferences.edit().putFloat(SPEED_KEY, value).apply()

    fun getSpeed(): Float =
        sharedPreferences.getFloat(SPEED_KEY, 1.0f)

    // ── System speed pass-through ────────────────────────────────────────────
    // When true: use speechRate from the calling app (e.g. VAR speed setting).
    // When false: use the in-app speed slider value.

    fun setUseSystemSpeed(value: Boolean) =
        sharedPreferences.edit().putBoolean(USE_SYSTEM_SPEED_KEY, value).apply()

    fun getUseSystemSpeed(): Boolean =
        sharedPreferences.getBoolean(USE_SYSTEM_SPEED_KEY, true)

    // ── Pitch ─────────────────────────────────────────────────────────────────
    // Applied to the app's own AudioTrack via PlaybackParams (API 23+).
    // Range: 0.5 (half pitch) → 2.0 (double pitch).  Default 1.0 = normal.
    // Note: pitch cannot be applied to VAR's audio output; it controls the
    // in-app test playback only.

    fun setPitch(value: Float) =
        sharedPreferences.edit().putFloat(PITCH_KEY, value).apply()

    fun getPitch(): Float =
        sharedPreferences.getFloat(PITCH_KEY, 1.0f)

    // ── System pitch pass-through ────────────────────────────────────────────
    // When true: use speechPitchRate from the calling app for test playback.
    // When false: use the in-app pitch slider value for test playback.

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
        sharedPreferences.getFloat(SILENCE_SCALE_KEY, 0.2f)

    // ── Clause split threshold ────────────────────────────────────────────────
    // Minimum words in a clause before splitting on soft punctuation (, ; : — …).
    // Lower = faster first audio but more prosody breaks; higher = fewer breaks.
    // Range 3–7. Default 4.

    fun setMinClauseWords(value: Int) =
        sharedPreferences.edit().putInt(MIN_CLAUSE_WORDS_KEY, value).apply()

    fun getMinClauseWords(): Int =
        sharedPreferences.getInt(MIN_CLAUSE_WORDS_KEY, 4)
}
