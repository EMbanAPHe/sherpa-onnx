// sherpa-onnx/jni/offline-tts.cc
//
// Copyright (c)  2024  Xiaomi Corporation

#include "sherpa-onnx/csrc/offline-tts.h"

#include <string>
#include <vector>

#include "sherpa-onnx/csrc/macros.h"
#include "sherpa-onnx/csrc/text-utils.h"
#include "sherpa-onnx/csrc/wave-writer.h"
#include "sherpa-onnx/jni/common.h"

// ── Audio helpers (file-scope, NOT inside any namespace) ─────────────────────
// Must be outside namespace sherpa_onnx so JNIEXPORT functions can call them.

// Construct a Java GeneratedAudio(float[], int) object from C++ audio data.
static jobject CreateAudioObject(JNIEnv *env,
                                 const std::vector<float> &samples,
                                 int32_t sample_rate) {
  jfloatArray samples_arr = env->NewFloatArray(samples.size());
  env->SetFloatArrayRegion(samples_arr, 0, samples.size(), samples.data());

  jclass gen_audio_cls =
      env->FindClass("com/k2fsa/sherpa/onnx/GeneratedAudio");
  if (!gen_audio_cls) {
    env->DeleteLocalRef(samples_arr);
    return nullptr;
  }

  jmethodID ctor = env->GetMethodID(gen_audio_cls, "<init>", "([FI)V");
  if (!ctor) {
    env->DeleteLocalRef(samples_arr);
    env->DeleteLocalRef(gen_audio_cls);
    return nullptr;
  }

  jobject obj = env->NewObject(gen_audio_cls, ctor, samples_arr, sample_rate);
  env->DeleteLocalRef(samples_arr);
  env->DeleteLocalRef(gen_audio_cls);
  return obj;
}

// Invoke the Kotlin (FloatArray) -> Int lambda safely, cleaning up all refs.
static int32_t CallCallback(JNIEnv *env, jobject callback,
                            jfloatArray samples_arr) {
  if (!callback) return 1;

  jclass cls = env->GetObjectClass(callback);
  if (env->ExceptionCheck()) { env->DeleteLocalRef(cls); return 1; }

  jmethodID invoke_mid =
      env->GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;");
  if (env->ExceptionCheck() || !invoke_mid) {
    env->DeleteLocalRef(cls);
    return 1;
  }

  jobject result = env->CallObjectMethod(callback, invoke_mid, samples_arr);
  if (env->ExceptionCheck() || !result) {
    env->DeleteLocalRef(cls);
    return 1;
  }

  jclass integer_cls    = env->GetObjectClass(result);
  jmethodID int_val_mid = env->GetMethodID(integer_cls, "intValue", "()I");
  jint ret = env->CallIntMethod(result, int_val_mid);

  env->DeleteLocalRef(integer_cls);
  env->DeleteLocalRef(result);
  env->DeleteLocalRef(cls);
  return ret;
}

namespace sherpa_onnx {

// ── GetGenerationConfig ───────────────────────────────────────────────────────
// Reads the three fields our GenerationConfig struct supports.
// (The Kotlin GenerationConfig has more fields — referenceAudio, num_steps etc.
//  — but our fork's C++ struct only supports sid, speed, silenceScale.
//  Unknown fields are intentionally ignored.)

static GenerationConfig GetGenerationConfig(JNIEnv *env, jobject config_obj) {
  GenerationConfig ans;

  if (!config_obj) {
    SHERPA_ONNX_LOGE("GenerationConfig is null");
    return ans;
  }

  jclass cls = env->GetObjectClass(config_obj);

  SHERPA_ONNX_JNI_READ_FLOAT(ans.silence_scale, silenceScale, cls, config_obj);
  SHERPA_ONNX_JNI_READ_FLOAT(ans.speed,         speed,        cls, config_obj);
  SHERPA_ONNX_JNI_READ_INT(ans.sid,             sid,          cls, config_obj);

  env->DeleteLocalRef(cls);
  return ans;
}

// ── GetOfflineTtsConfig ───────────────────────────────────────────────────────

static OfflineTtsConfig GetOfflineTtsConfig(JNIEnv *env, jobject config,
                                            bool *ok) {
  OfflineTtsConfig ans;

  jclass cls = env->GetObjectClass(config);
  jfieldID fid;

  fid = env->GetFieldID(cls, "model",
                        "Lcom/k2fsa/sherpa/onnx/OfflineTtsModelConfig;");
  jobject model = env->GetObjectField(config, fid);
  jclass model_config_cls = env->GetObjectClass(model);

  // vits
  fid = env->GetFieldID(model_config_cls, "vits",
                        "Lcom/k2fsa/sherpa/onnx/OfflineTtsVitsModelConfig;");
  jobject vits = env->GetObjectField(model, fid);
  jclass vits_cls = env->GetObjectClass(vits);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.vits.model,        model,      vits_cls, vits);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.vits.lexicon,      lexicon,    vits_cls, vits);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.vits.tokens,       tokens,     vits_cls, vits);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.vits.data_dir,     dataDir,    vits_cls, vits);
  SHERPA_ONNX_JNI_READ_FLOAT(ans.model.vits.noise_scale,   noiseScale, vits_cls, vits);
  SHERPA_ONNX_JNI_READ_FLOAT(ans.model.vits.noise_scale_w, noiseScaleW,vits_cls, vits);
  SHERPA_ONNX_JNI_READ_FLOAT(ans.model.vits.length_scale,  lengthScale,vits_cls, vits);

  // matcha
  fid = env->GetFieldID(model_config_cls, "matcha",
                        "Lcom/k2fsa/sherpa/onnx/OfflineTtsMatchaModelConfig;");
  jobject matcha = env->GetObjectField(model, fid);
  jclass matcha_cls = env->GetObjectClass(matcha);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.matcha.acoustic_model, acousticModel, matcha_cls, matcha);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.matcha.vocoder,        vocoder,       matcha_cls, matcha);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.matcha.lexicon,        lexicon,       matcha_cls, matcha);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.matcha.tokens,         tokens,        matcha_cls, matcha);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.matcha.data_dir,       dataDir,       matcha_cls, matcha);
  SHERPA_ONNX_JNI_READ_FLOAT(ans.model.matcha.noise_scale,     noiseScale,    matcha_cls, matcha);
  SHERPA_ONNX_JNI_READ_FLOAT(ans.model.matcha.length_scale,    lengthScale,   matcha_cls, matcha);

  // kokoro
  fid = env->GetFieldID(model_config_cls, "kokoro",
                        "Lcom/k2fsa/sherpa/onnx/OfflineTtsKokoroModelConfig;");
  jobject kokoro = env->GetObjectField(model, fid);
  jclass kokoro_cls = env->GetObjectClass(kokoro);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.kokoro.model,       model,       kokoro_cls, kokoro);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.kokoro.voices,      voices,      kokoro_cls, kokoro);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.kokoro.tokens,      tokens,      kokoro_cls, kokoro);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.kokoro.lexicon,     lexicon,     kokoro_cls, kokoro);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.kokoro.lang,        lang,        kokoro_cls, kokoro);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.kokoro.data_dir,    dataDir,     kokoro_cls, kokoro);
  SHERPA_ONNX_JNI_READ_FLOAT(ans.model.kokoro.length_scale, lengthScale, kokoro_cls, kokoro);

  // kitten
  fid = env->GetFieldID(model_config_cls, "kitten",
                        "Lcom/k2fsa/sherpa/onnx/OfflineTtsKittenModelConfig;");
  jobject kitten = env->GetObjectField(model, fid);
  jclass kitten_cls = env->GetObjectClass(kitten);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.kitten.model,       model,       kitten_cls, kitten);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.kitten.voices,      voices,      kitten_cls, kitten);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.kitten.tokens,      tokens,      kitten_cls, kitten);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.kitten.data_dir,    dataDir,     kitten_cls, kitten);
  SHERPA_ONNX_JNI_READ_FLOAT(ans.model.kitten.length_scale, lengthScale, kitten_cls, kitten);

  SHERPA_ONNX_JNI_READ_INT(ans.model.num_threads, numThreads, model_config_cls, model);
  SHERPA_ONNX_JNI_READ_BOOL(ans.model.debug,      debug,      model_config_cls, model);
  SHERPA_ONNX_JNI_READ_STRING(ans.model.provider, provider,   model_config_cls, model);

  SHERPA_ONNX_JNI_READ_STRING(ans.rule_fsts,      ruleFsts,        cls, config);
  SHERPA_ONNX_JNI_READ_STRING(ans.rule_fars,      ruleFars,        cls, config);
  SHERPA_ONNX_JNI_READ_INT(ans.max_num_sentences, maxNumSentences, cls, config);
  SHERPA_ONNX_JNI_READ_FLOAT(ans.silence_scale,   silenceScale,    cls, config);

  *ok = true;
  return ans;
}

}  // namespace sherpa_onnx

// ── JNI exported functions ────────────────────────────────────────────────────

SHERPA_ONNX_EXTERN_C
JNIEXPORT jlong JNICALL Java_com_k2fsa_sherpa_onnx_OfflineTts_newFromAsset(
    JNIEnv *env, jobject /*obj*/, jobject asset_manager, jobject _config) {
#if __ANDROID_API__ >= 9
  AAssetManager *mgr = AAssetManager_fromJava(env, asset_manager);
  if (!mgr) {
    SHERPA_ONNX_LOGE("Failed to get asset manager: %p", mgr);
    return 0;
  }
#endif

  bool ok = false;
  auto config = sherpa_onnx::GetOfflineTtsConfig(env, _config, &ok);
  if (!ok) {
    SHERPA_ONNX_LOGE("Please read the error message carefully");
    return 0;
  }

  if (config.model.debug) {
#if __ANDROID_API__
    auto str_vec = sherpa_onnx::SplitString(config.ToString(), 128);
    for (const auto &s : str_vec) SHERPA_ONNX_LOGE("%s", s.c_str());
#else
    SHERPA_ONNX_LOGE("%s", config.ToString().c_str());
#endif
  }

  auto tts = new sherpa_onnx::OfflineTts(
#if __ANDROID_API__ >= 9
      mgr,
#endif
      config);
  return reinterpret_cast<jlong>(tts);
}

SHERPA_ONNX_EXTERN_C
JNIEXPORT jlong JNICALL Java_com_k2fsa_sherpa_onnx_OfflineTts_newFromFile(
    JNIEnv *env, jobject /*obj*/, jobject _config) {
  return SafeJNI(
      env, "OfflineTts_newFromFile",
      [&]() -> jlong {
        bool ok = false;
        auto config = sherpa_onnx::GetOfflineTtsConfig(env, _config, &ok);
        if (!ok) { SHERPA_ONNX_LOGE("Please read the error message carefully"); return 0; }
        if (!config.Validate()) { SHERPA_ONNX_LOGE("Errors found in config!"); return 0; }
        auto tts = new sherpa_onnx::OfflineTts(config);
        return reinterpret_cast<jlong>(tts);
      },
      (jlong)0);
}

SHERPA_ONNX_EXTERN_C
JNIEXPORT void JNICALL Java_com_k2fsa_sherpa_onnx_OfflineTts_delete(
    JNIEnv * /*env*/, jobject /*obj*/, jlong ptr) {
  delete reinterpret_cast<sherpa_onnx::OfflineTts *>(ptr);
}

SHERPA_ONNX_EXTERN_C
JNIEXPORT jint JNICALL Java_com_k2fsa_sherpa_onnx_OfflineTts_getSampleRate(
    JNIEnv * /*env*/, jobject /*obj*/, jlong ptr) {
  return reinterpret_cast<sherpa_onnx::OfflineTts *>(ptr)->SampleRate();
}

SHERPA_ONNX_EXTERN_C
JNIEXPORT jint JNICALL Java_com_k2fsa_sherpa_onnx_OfflineTts_getNumSpeakers(
    JNIEnv * /*env*/, jobject /*obj*/, jlong ptr) {
  return reinterpret_cast<sherpa_onnx::OfflineTts *>(ptr)->NumSpeakers();
}

SHERPA_ONNX_EXTERN_C
JNIEXPORT jobject JNICALL
Java_com_k2fsa_sherpa_onnx_OfflineTts_generateImpl(JNIEnv *env, jobject /*obj*/,
                                                    jlong ptr, jstring text,
                                                    jint sid, jfloat speed) {
  const char *p_text = env->GetStringUTFChars(text, nullptr);

  sherpa_onnx::GenerationConfig config;
  config.sid   = sid;
  config.speed = speed;

  auto audio = reinterpret_cast<sherpa_onnx::OfflineTts *>(ptr)->Generate(
      p_text, config);
  env->ReleaseStringUTFChars(text, p_text);
  return CreateAudioObject(env, audio.samples, audio.sample_rate);
}

SHERPA_ONNX_EXTERN_C
JNIEXPORT jobject JNICALL
Java_com_k2fsa_sherpa_onnx_OfflineTts_generateWithCallbackImpl(
    JNIEnv *env, jobject /*obj*/, jlong ptr, jstring text, jint sid,
    jfloat speed, jobject callback) {
  const char *p_text = env->GetStringUTFChars(text, nullptr);

  sherpa_onnx::GenerationConfig config;
  config.sid   = sid;
  config.speed = speed;

  auto tts = reinterpret_cast<sherpa_onnx::OfflineTts *>(ptr);
  sherpa_onnx::GeneratedAudio audio;

  if (callback) {
    std::function<int32_t(const float *, int32_t, float)> cb_wrapper =
        [env, callback](const float *samples, int32_t n, float) -> int32_t {
      jfloatArray arr = env->NewFloatArray(n);
      env->SetFloatArrayRegion(arr, 0, n, samples);
      int32_t ret = CallCallback(env, callback, arr);
      env->DeleteLocalRef(arr);
      return ret;
    };
    audio = tts->Generate(p_text, config, cb_wrapper);
  } else {
    audio = tts->Generate(p_text, config, nullptr);
  }

  env->ReleaseStringUTFChars(text, p_text);
  return CreateAudioObject(env, audio.samples, audio.sample_rate);
}

SHERPA_ONNX_EXTERN_C
JNIEXPORT jobject JNICALL
Java_com_k2fsa_sherpa_onnx_OfflineTts_generateWithConfigImpl(
    JNIEnv *env, jobject /*obj*/, jlong ptr, jstring text,
    jobject _gen_config, jobject callback) {
  const char *p_text = env->GetStringUTFChars(text, nullptr);
  auto gen_config = sherpa_onnx::GetGenerationConfig(env, _gen_config);
  auto tts = reinterpret_cast<sherpa_onnx::OfflineTts *>(ptr);
  sherpa_onnx::GeneratedAudio audio;

  if (callback) {
    std::function<int32_t(const float *, int32_t, float)> cb_wrapper =
        [env, callback](const float *samples, int32_t n, float) -> int32_t {
      jfloatArray arr = env->NewFloatArray(n);
      env->SetFloatArrayRegion(arr, 0, n, samples);
      int32_t ret = CallCallback(env, callback, arr);
      env->DeleteLocalRef(arr);
      return ret;
    };
    audio = tts->Generate(p_text, gen_config, cb_wrapper);
  } else {
    audio = tts->Generate(p_text, gen_config, nullptr);
  }

  env->ReleaseStringUTFChars(text, p_text);
  return CreateAudioObject(env, audio.samples, audio.sample_rate);
}

SHERPA_ONNX_EXTERN_C
JNIEXPORT jboolean JNICALL Java_com_k2fsa_sherpa_onnx_GeneratedAudio_saveImpl(
    JNIEnv *env, jobject /*obj*/, jstring filename, jfloatArray samples,
    jint sample_rate) {
  const char *p_filename = env->GetStringUTFChars(filename, nullptr);
  jfloat *p = env->GetFloatArrayElements(samples, nullptr);
  jsize n   = env->GetArrayLength(samples);
  bool ok   = sherpa_onnx::WriteWave(p_filename, sample_rate, p, n);
  env->ReleaseStringUTFChars(filename, p_filename);
  env->ReleaseFloatArrayElements(samples, p, JNI_ABORT);
  return ok;
}
