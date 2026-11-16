/*
 * JNI bridge: pime-core <-> PimeCore.java.
 *
 * The shell contains no input-method logic. It creates one core, loads plugin
 * packages the host placed on disk, builds/activates a generation, and turns
 * HAL sink callbacks into Java calls. Everything the sink does not handle is
 * passed back to the editor by the Java layer.
 */
#include <jni.h>

#include <pime/core.h>
#include <pime/hal.h>
#include <pime/plugin.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <string_view>
#include <vector>

namespace {

JavaVM* g_vm = nullptr;
jobject g_bridge = nullptr;
jclass g_bridge_class = nullptr;

jmethodID g_mid_composition = nullptr;
jmethodID g_mid_commit = nullptr;
jmethodID g_mid_candidates = nullptr;
jmethodID g_mid_hide = nullptr;
jmethodID g_mid_beep = nullptr;
jmethodID g_mid_notify = nullptr;

class Env {
public:
    Env() {
        if (!g_vm) return;
        JNIEnv* env = nullptr;
        if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6)
                == JNI_EDETACHED) {
            if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached_ = true;
                env_ = env;
            }
        } else {
            env_ = env;
        }
    }
    ~Env() {
        if (attached_ && g_vm) g_vm->DetachCurrentThread();
    }
    Env(const Env&) = delete;
    Env& operator=(const Env&) = delete;

    JNIEnv* operator->() const { return env_; }
    JNIEnv* get() const { return env_; }

private:
    JNIEnv* env_ = nullptr;
    bool attached_ = false;
};

void append_utf8(std::string* out, uint32_t cp) {
    if (cp < 0x80) {
        out->push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out->push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out->push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out->push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out->push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out->push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out->push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out->push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out->push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out->push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

std::string from_jstring(JNIEnv* env, jstring value) {
    std::string out;
    if (!value) return out;
    const jsize len = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (!chars) return out;
    out.reserve(static_cast<size_t>(len));
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            }
        }
        append_utf8(&out, cp);
    }
    env->ReleaseStringChars(value, chars);
    return out;
}

// Decodes UTF-8 into UTF-16 code units, stopping after `byte_limit` input
// bytes. Used to translate the ABI's byte cursor into a Java string index.
uint32_t utf16_units(const char* text, uint32_t len, uint32_t byte_limit) {
    if (!text) return 0;
    if (byte_limit > len) byte_limit = len;
    uint32_t units = 0;
    uint32_t i = 0;
    while (i < byte_limit) {
        const unsigned char c = static_cast<unsigned char>(text[i]);
        uint32_t cp = 0xFFFD;
        uint32_t n = 1;
        if (c < 0x80) {
            cp = c;
        } else if ((c >> 5) == 0x6) {
            cp = c & 0x1F;
            n = 2;
        } else if ((c >> 4) == 0xE) {
            cp = c & 0x0F;
            n = 3;
        } else if ((c >> 3) == 0x1E) {
            cp = c & 0x07;
            n = 4;
        }
        if (i + n > len) break;
        bool valid = true;
        for (uint32_t k = 1; k < n; ++k) {
            const unsigned char cc = static_cast<unsigned char>(text[i + k]);
            if ((cc & 0xC0) != 0x80) {
                valid = false;
                break;
            }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!valid) {
            ++i;
        } else {
            i += n;
        }
        units += cp >= 0x10000 ? 2 : 1;
    }
    return units;
}

jstring to_jstring(JNIEnv* env, const char* text, uint32_t len) {
    if (!text) return env->NewString(nullptr, 0);
    std::u16string out;
    out.reserve(len);
    uint32_t i = 0;
    while (i < len) {
        const unsigned char c = static_cast<unsigned char>(text[i]);
        uint32_t cp = 0xFFFD;
        uint32_t n = 0;
        if (c < 0x80) {
            cp = c;
            n = 1;
        } else if ((c >> 5) == 0x6) {
            cp = c & 0x1F;
            n = 2;
        } else if ((c >> 4) == 0xE) {
            cp = c & 0x0F;
            n = 3;
        } else if ((c >> 3) == 0x1E) {
            cp = c & 0x07;
            n = 4;
        }
        if (n == 0 || i + n > len) {
            out.push_back(static_cast<char16_t>(0xFFFD));
            ++i;
            continue;
        }
        bool valid = true;
        for (uint32_t k = 1; k < n; ++k) {
            const unsigned char cc = static_cast<unsigned char>(text[i + k]);
            if ((cc & 0xC0) != 0x80) {
                valid = false;
                break;
            }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!valid) {
            out.push_back(static_cast<char16_t>(0xFFFD));
            ++i;
            continue;
        }
        i += n;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back(static_cast<char16_t>(cp));
        }
    }
    return env->NewString(reinterpret_cast<const jchar*>(out.data()),
                          static_cast<jsize>(out.size()));
}

bool clear_exception(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return true;
    }
    return false;
}

pime_status sink_composition(void*, const pime_composition* composition) {
    Env env;
    if (!env.get() || !g_bridge || !composition) return PIME_OK;
    JNIEnv* jni = env.get();
    jstring text = to_jstring(jni, composition->text, composition->text_len);
    if (!text) return PIME_INVALID;
    const uint32_t cursor = utf16_units(composition->text, composition->text_len,
                                        composition->cursor);
    /* Syllable segments stay as UTF-16 offsets so the Java layer can draw the
     * preedit with its own separator without re-parsing pinyin. */
    const uint32_t count = composition->segment_count;
    jintArray starts = nullptr;
    jintArray ends = nullptr;
    if (count > 0 && composition->segments) {
        std::vector<jint> starts_buf(count);
        std::vector<jint> ends_buf(count);
        for (uint32_t i = 0; i < count; ++i) {
            starts_buf[i] = static_cast<jint>(utf16_units(
                    composition->text, composition->text_len,
                    composition->segments[i].start));
            ends_buf[i] = static_cast<jint>(utf16_units(
                    composition->text, composition->text_len,
                    composition->segments[i].end));
        }
        starts = jni->NewIntArray(static_cast<jsize>(count));
        ends = jni->NewIntArray(static_cast<jsize>(count));
        if (!starts || !ends) {
            if (starts) jni->DeleteLocalRef(starts);
            if (ends) jni->DeleteLocalRef(ends);
            jni->DeleteLocalRef(text);
            return PIME_INVALID;
        }
        jni->SetIntArrayRegion(starts, 0, static_cast<jsize>(count),
                               starts_buf.data());
        jni->SetIntArrayRegion(ends, 0, static_cast<jsize>(count),
                               ends_buf.data());
    }
    jni->CallVoidMethod(g_bridge, g_mid_composition, text,
                        static_cast<jint>(cursor), starts, ends);
    if (starts) jni->DeleteLocalRef(starts);
    if (ends) jni->DeleteLocalRef(ends);
    jni->DeleteLocalRef(text);
    return clear_exception(jni) ? PIME_INVALID : PIME_OK;
}

pime_status sink_commit(void*, const pime_text* text) {
    Env env;
    if (!env.get() || !g_bridge || !text) return PIME_OK;
    jstring value = to_jstring(env.get(), text->text, text->text_len);
    if (!value) return PIME_INVALID;
    env->CallVoidMethod(g_bridge, g_mid_commit, value);
    env->DeleteLocalRef(value);
    return clear_exception(env.get()) ? PIME_INVALID : PIME_OK;
}

pime_status sink_candidates(void*, const pime_candidate_list* list) {
    Env env;
    if (!env.get() || !g_bridge || !list) return PIME_OK;
    jclass string_class = env->FindClass("java/lang/String");
    if (!string_class) return PIME_INVALID;
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(list->count),
                                             string_class, nullptr);
    env->DeleteLocalRef(string_class);
    if (!array) return PIME_INVALID;
    for (uint32_t i = 0; i < list->count; ++i) {
        jstring item = to_jstring(env.get(), list->items[i].text,
                                  list->items[i].text_len);
        if (!item) {
            env->DeleteLocalRef(array);
            return PIME_INVALID;
        }
        env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
        env->DeleteLocalRef(item);
    }
    env->CallVoidMethod(g_bridge, g_mid_candidates, array);
    env->DeleteLocalRef(array);
    return clear_exception(env.get()) ? PIME_INVALID : PIME_OK;
}

pime_status sink_hide(void*) {
    Env env;
    if (!env.get() || !g_bridge) return PIME_OK;
    env->CallVoidMethod(g_bridge, g_mid_hide);
    return clear_exception(env.get()) ? PIME_INVALID : PIME_OK;
}

pime_status sink_beep(void*) {
    Env env;
    if (!env.get() || !g_bridge) return PIME_OK;
    env->CallVoidMethod(g_bridge, g_mid_beep);
    return clear_exception(env.get()) ? PIME_INVALID : PIME_OK;
}

pime_status sink_notify(void*, pime_status code) {
    Env env;
    if (!env.get() || !g_bridge) return PIME_OK;
    env->CallVoidMethod(g_bridge, g_mid_notify, static_cast<jint>(code));
    return clear_exception(env.get()) ? PIME_INVALID : PIME_OK;
}

void release_globals(JNIEnv* env) {
    if (g_bridge_class) {
        env->DeleteGlobalRef(g_bridge_class);
        g_bridge_class = nullptr;
    }
    if (g_bridge) {
        env->DeleteGlobalRef(g_bridge);
        g_bridge = nullptr;
    }
    g_mid_composition = nullptr;
    g_mid_commit = nullptr;
    g_mid_candidates = nullptr;
    g_mid_hide = nullptr;
    g_mid_beep = nullptr;
    g_mid_notify = nullptr;
}

pime_session* session_of(jlong handle) {
    return reinterpret_cast<pime_session*>(static_cast<intptr_t>(handle));
}

pime_core* core_of(jlong handle) {
    return reinterpret_cast<pime_core*>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_pimeorg_pime_PimeCore_nativeStart(JNIEnv* env, jobject thiz,
                                       jstring plugin_dir, jstring data_dir,
                                       jstring trust_dir, jint flags) {
    if (g_bridge) return 0;

    const std::string plugin_path = from_jstring(env, plugin_dir);
    const std::string data_path = from_jstring(env, data_dir);
    const std::string trust_path = from_jstring(env, trust_dir);

    g_bridge = env->NewGlobalRef(thiz);
    if (!g_bridge) return 0;
    g_bridge_class = static_cast<jclass>(env->NewGlobalRef(env->GetObjectClass(thiz)));
    if (!g_bridge_class) {
        release_globals(env);
        return 0;
    }
    g_mid_composition = env->GetMethodID(g_bridge_class, "onNativeComposition",
                                         "(Ljava/lang/String;I[I[I)V");
    g_mid_commit = env->GetMethodID(g_bridge_class, "onNativeCommit",
                                    "(Ljava/lang/String;)V");
    g_mid_candidates = env->GetMethodID(g_bridge_class, "onNativeCandidates",
                                        "([Ljava/lang/String;)V");
    g_mid_hide = env->GetMethodID(g_bridge_class, "onNativeHideCandidates", "()V");
    g_mid_beep = env->GetMethodID(g_bridge_class, "onNativeBeep", "()V");
    g_mid_notify = env->GetMethodID(g_bridge_class, "onNativeNotify", "(I)V");
    if (clear_exception(env) || !g_mid_composition || !g_mid_commit ||
        !g_mid_candidates || !g_mid_hide || !g_mid_beep || !g_mid_notify) {
        release_globals(env);
        return 0;
    }

    pime_host_sink sink{};
    sink.size = sizeof(pime_host_sink);
    sink.version = PIME_ABI_MAJOR;
    sink.set_composition = &sink_composition;
    sink.commit = &sink_commit;
    sink.show_candidates = &sink_candidates;
    sink.hide_candidates = &sink_hide;
    sink.beep = &sink_beep;
    sink.notify = &sink_notify;

    pime_core_config config{};
    config.size = sizeof(pime_core_config);
    config.version = PIME_ABI_MAJOR;
    config.plugin_dir = plugin_path.data();
    config.plugin_dir_len = static_cast<uint32_t>(plugin_path.size());
    config.data_dir = data_path.data();
    config.data_dir_len = static_cast<uint32_t>(data_path.size());
    config.trust_store = trust_path.data();
    config.trust_store_len = static_cast<uint32_t>(trust_path.size());
    config.log_level = PIME_LOG_INFO;
    config.flags = static_cast<uint32_t>(flags);
    config.memory_budget_mb = 64;

    pime_core* core = pime_core_create(&config, &sink, g_bridge);
    if (!core) {
        release_globals(env);
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(core));
}

JNIEXPORT void JNICALL
Java_com_pimeorg_pime_PimeCore_nativeStop(JNIEnv* env, jobject, jlong core) {
    if (pime_core* instance = core_of(core)) pime_core_destroy(instance);
    release_globals(env);
}

JNIEXPORT jint JNICALL
Java_com_pimeorg_pime_PimeCore_nativePluginLoad(JNIEnv* env, jobject, jlong core,
                                            jstring path) {
    pime_core* instance = core_of(core);
    if (!instance) return PIME_INVALID;
    const std::string value = from_jstring(env, path);
    return static_cast<jint>(
        pime_plugin_load(instance, value.data(), static_cast<uint32_t>(value.size())));
}

JNIEXPORT jint JNICALL
Java_com_pimeorg_pime_PimeCore_nativePermission(JNIEnv* env, jobject, jlong core,
                                            jstring capability,
                                            jboolean granted) {
    pime_core* instance = core_of(core);
    if (!instance) return PIME_INVALID;
    const std::string value = from_jstring(env, capability);
    return static_cast<jint>(pime_core_permission(
        instance, value.data(), static_cast<uint32_t>(value.size()),
        granted ? 1 : 0));
}

JNIEXPORT jlong JNICALL
Java_com_pimeorg_pime_PimeCore_nativeBuildGeneration(JNIEnv* env, jobject, jlong core,
                                                 jbyteArray config) {
    pime_core* instance = core_of(core);
    if (!instance || !config) return 0;
    const jsize len = env->GetArrayLength(config);
    jbyte* bytes = env->GetByteArrayElements(config, nullptr);
    if (!bytes) return 0;
    uint64_t generation = 0;
    const pime_status status = pime_generation_build(
        instance, reinterpret_cast<const uint8_t*>(bytes),
        static_cast<uint32_t>(len), &generation);
    env->ReleaseByteArrayElements(config, bytes, JNI_ABORT);
    return status == PIME_OK ? static_cast<jlong>(generation) : 0;
}

JNIEXPORT jint JNICALL
Java_com_pimeorg_pime_PimeCore_nativeActivateGeneration(JNIEnv*, jobject, jlong core,
                                                    jlong generation) {
    pime_core* instance = core_of(core);
    if (!instance || generation == 0) return PIME_INVALID;
    return static_cast<jint>(
        pime_generation_activate(instance, static_cast<uint64_t>(generation)));
}

JNIEXPORT jint JNICALL
Java_com_pimeorg_pime_PimeCore_nativeRetireGeneration(JNIEnv*, jobject, jlong core,
                                                   jlong generation) {
    pime_core* instance = core_of(core);
    if (!instance || generation == 0) return PIME_INVALID;
    return static_cast<jint>(
        pime_generation_retire(instance, static_cast<uint64_t>(generation)));
}

JNIEXPORT jlong JNICALL
Java_com_pimeorg_pime_PimeCore_nativeSessionCreate(JNIEnv*, jobject, jlong core) {
    pime_core* instance = core_of(core);
    if (!instance) return 0;
    pime_session_config config{};
    config.size = sizeof(pime_session_config);
    config.version = PIME_ABI_MAJOR;
    pime_session* session = pime_session_create(instance, &config);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
}

JNIEXPORT void JNICALL
Java_com_pimeorg_pime_PimeCore_nativeSessionDestroy(JNIEnv*, jobject, jlong session) {
    if (pime_session* value = session_of(session)) pime_session_destroy(value);
}

JNIEXPORT jint JNICALL
Java_com_pimeorg_pime_PimeCore_nativeKey(JNIEnv* env, jobject, jlong session,
                                     jint keycode, jint modifiers, jint action,
                                     jstring text) {
    pime_session* value = session_of(session);
    if (!value) return -PIME_INVALID;
    const std::string utf8 = from_jstring(env, text);
    pime_key_event event{};
    event.size = sizeof(pime_key_event);
    event.version = PIME_ABI_MAJOR;
    event.keycode = static_cast<uint32_t>(keycode);
    event.modifiers = static_cast<uint32_t>(modifiers);
    event.action = static_cast<uint32_t>(action);
    event.text = utf8.data();
    event.text_len = static_cast<uint32_t>(utf8.size());
    int handled = 0;
    const pime_status status = pime_core_key(value, &event, &handled);
    if (status != PIME_OK) return -static_cast<jint>(status);
    return handled ? 1 : 0;
}

JNIEXPORT jint JNICALL
Java_com_pimeorg_pime_PimeCore_nativeText(JNIEnv* env, jobject, jlong session,
                                      jstring text) {
    pime_session* value = session_of(session);
    if (!value) return -PIME_INVALID;
    const std::string utf8 = from_jstring(env, text);
    pime_text event{};
    event.size = sizeof(pime_text);
    event.version = PIME_ABI_MAJOR;
    event.text = utf8.data();
    event.text_len = static_cast<uint32_t>(utf8.size());
    return static_cast<jint>(pime_core_text(value, &event));
}

JNIEXPORT jint JNICALL
Java_com_pimeorg_pime_PimeCore_nativeCandidate(JNIEnv*, jobject, jlong session,
                                           jint index) {
    pime_session* value = session_of(session);
    if (!value) return PIME_INVALID;
    pime_candidate_event event{};
    event.size = sizeof(pime_candidate_event);
    event.version = PIME_ABI_MAJOR;
    event.index = static_cast<uint32_t>(index);
    return static_cast<jint>(pime_core_candidate(value, &event));
}

JNIEXPORT jint JNICALL
Java_com_pimeorg_pime_PimeCore_nativeContext(JNIEnv* env, jobject, jlong session,
                                         jstring app_id, jint field_type,
                                         jint input_type, jstring locale,
                                         jint page_size, jint host_caps) {
    pime_session* value = session_of(session);
    if (!value) return PIME_INVALID;
    const std::string app = from_jstring(env, app_id);
    const std::string loc = from_jstring(env, locale);
    pime_context context{};
    context.size = sizeof(pime_context);
    context.version = PIME_ABI_MAJOR;
    context.app_id = app.data();
    context.app_id_len = static_cast<uint32_t>(app.size());
    context.field_type = static_cast<uint32_t>(field_type);
    context.input_type = static_cast<uint32_t>(input_type);
    context.locale = loc.data();
    context.locale_len = static_cast<uint32_t>(loc.size());
    context.page_size = static_cast<uint32_t>(page_size);
    context.host_caps = static_cast<uint32_t>(host_caps);
    return static_cast<jint>(pime_core_context(value, &context));
}

JNIEXPORT jint JNICALL
Java_com_pimeorg_pime_PimeCore_nativeFocus(JNIEnv*, jobject, jlong session,
                                       jboolean focused) {
    pime_session* value = session_of(session);
    if (!value) return PIME_INVALID;
    return static_cast<jint>(pime_core_focus(value, focused ? 1 : 0));
}

JNIEXPORT jint JNICALL
Java_com_pimeorg_pime_PimeCore_nativeSecure(JNIEnv*, jobject, jlong session,
                                        jboolean secure) {
    pime_session* value = session_of(session);
    if (!value) return PIME_INVALID;
    return static_cast<jint>(pime_core_secure(value, secure ? 1 : 0));
}

JNIEXPORT jint JNICALL
Java_com_pimeorg_pime_PimeCore_nativeReset(JNIEnv*, jobject, jlong session) {
    pime_session* value = session_of(session);
    if (!value) return PIME_INVALID;
    return static_cast<jint>(pime_core_reset(value));
}

JNIEXPORT jint JNICALL
Java_com_pimeorg_pime_PimeCore_nativeSurrounding(JNIEnv* env, jobject,
                                                 jlong session,
                                                 jstring before,
                                                 jstring after) {
    pime_session* value = session_of(session);
    if (!value) return PIME_INVALID;
    const std::string before_utf8 = from_jstring(env, before);
    const std::string after_utf8 = from_jstring(env, after);
    pime_text before_text{};
    before_text.size = sizeof(pime_text);
    before_text.version = PIME_ABI_MAJOR;
    before_text.text = before_utf8.data();
    before_text.text_len = static_cast<uint32_t>(before_utf8.size());
    pime_text after_text{};
    after_text.size = sizeof(pime_text);
    after_text.version = PIME_ABI_MAJOR;
    after_text.text = after_utf8.data();
    after_text.text_len = static_cast<uint32_t>(after_utf8.size());
    return static_cast<jint>(
        pime_core_surrounding(value, &before_text, &after_text));
}

JNIEXPORT void JNICALL
Java_com_pimeorg_pime_PimeCore_nativePump(JNIEnv*, jobject, jlong core) {
    if (pime_core* instance = core_of(core)) pime_core_pump(instance);
}

JNIEXPORT void JNICALL
Java_com_pimeorg_pime_PimeCore_nativePause(JNIEnv*, jobject, jlong core, jboolean paused) {
    pime_core* instance = core_of(core);
    if (!instance) return;
    if (paused) {
        pime_core_pause(instance);
    } else {
        pime_core_resume(instance);
    }
}

JNIEXPORT jstring JNICALL
Java_com_pimeorg_pime_PimeCore_nativeStatusString(JNIEnv* env, jobject, jint status) {
    const char* text = pime_status_string(static_cast<pime_status>(status));
    return to_jstring(env, text, static_cast<uint32_t>(std::strlen(text)));
}

JNIEXPORT jstring JNICALL
Java_com_pimeorg_pime_PimeCore_nativeVersion(JNIEnv* env, jobject) {
    uint32_t major = 0;
    uint32_t minor = 0;
    uint32_t patch = 0;
    pime_core_version(&major, &minor, &patch);
    char buffer[64];
    std::snprintf(buffer, sizeof(buffer), "%u.%u.%u", major, minor, patch);
    return to_jstring(env, buffer, static_cast<uint32_t>(std::strlen(buffer)));
}

}  // extern "C"
