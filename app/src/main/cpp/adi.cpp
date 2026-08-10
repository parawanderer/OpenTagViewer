// Opens Apple's ADI libraries and calls into them.
//
// Why this is native rather than Java: the functions we need are plain C exports with
// obfuscated names (kq56gsgHG6 and friends), not JNI entry points, so Java's `native` keyword
// cannot bind to them. They have to be resolved with dlsym and called through function
// pointers. The names change between APK builds, so every resolution failure has to be
// reported rather than assumed away.
//
// At this stage this is a probe: enough to answer whether Android will load these libraries
// out of app storage at all, and whether ADI initialises. The full provisioning flow is not
// here yet.

#include <jni.h>

#include <dlfcn.h>
#include <android/log.h>

#include <string>
#include <vector>

#define LOG_TAG "adi"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/// Copies a Java string out into a std::string, so the JNI reference can be released before
/// any call that might block or throw.
std::string to_utf8(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars == nullptr ? std::string{} : std::string{chars};
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return out;
}

}  // namespace

extern "C" {

/**
 * dlopen() one library by absolute path.
 *
 * RTLD_NOW so unresolved symbols surface here, with a name, instead of as a crash at the
 * first call. RTLD_GLOBAL so libraries opened later can resolve against this one - the ADI
 * libraries depend on each other and none of them are on the default search path.
 *
 * @return null on success, or dlerror()'s message. The message is the whole point: it is what
 *         distinguishes "W^X refused to execute a file in app storage" from "a dependency is
 *         missing", and those two answers lead to very different amounts of work.
 */
JNIEXPORT jstring JNICALL
Java_dev_wander_android_opentagviewer_anisette_NativeAdi_open(
        JNIEnv *env, jclass, jstring path_in, jlongArray handle_out) {
    const std::string path = to_utf8(env, path_in);

    dlerror();  // clear any stale error before the call
    void *handle = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);

    if (handle == nullptr) {
        const char *error = dlerror();
        const std::string message = error == nullptr ? "dlopen returned null with no error"
                                                     : error;
        LOGE("dlopen(%s) failed: %s", path.c_str(), message.c_str());
        return env->NewStringUTF(message.c_str());
    }

    LOGI("dlopen(%s) ok", path.c_str());
    const jlong as_long = reinterpret_cast<jlong>(handle);
    env->SetLongArrayRegion(handle_out, 0, 1, &as_long);
    return nullptr;
}

/**
 * Resolve one symbol.
 *
 * @return the address, or 0 if this build of the library does not export that name.
 */
JNIEXPORT jlong JNICALL
Java_dev_wander_android_opentagviewer_anisette_NativeAdi_resolve(
        JNIEnv *env, jclass, jlong handle, jstring symbol_in) {
    const std::string symbol = to_utf8(env, symbol_in);

    dlerror();
    void *address = dlsym(reinterpret_cast<void *>(handle), symbol.c_str());

    if (address == nullptr) {
        const char *error = dlerror();
        LOGE("dlsym(%s) failed: %s", symbol.c_str(), error == nullptr ? "not found" : error);
    }
    return reinterpret_cast<jlong>(address);
}

/**
 * Call an ADI function of the shape {@code int(const char *)}.
 *
 * Both ADILoadLibraryWithPath (where libCoreADI.so lives) and ADISetProvisioningPath (where
 * ADI may persist its own state) have this signature, so one entry point covers both. The
 * function is passed as an already-resolved address rather than looked up here, because the
 * symbol names differ between APK builds and belong in one place - AdiFunction.
 *
 * @return ADI's own return code - 0 is success, anything else is an ADI error number.
 */
JNIEXPORT jint JNICALL
Java_dev_wander_android_opentagviewer_anisette_NativeAdi_callWithPath(
        JNIEnv *env, jclass, jlong function, jstring path_in) {
    const std::string path = to_utf8(env, path_in);

    using PathFunction = int (*)(const char *);
    const int result = reinterpret_cast<PathFunction>(function)(path.c_str());

    LOGI("call(%s) = %d", path.c_str(), result);
    return result;
}

/** ADISetAndroidID: {@code int(const char *identifier, uint length)}. */
JNIEXPORT jint JNICALL
Java_dev_wander_android_opentagviewer_anisette_NativeAdi_setAndroidId(
        JNIEnv *env, jclass, jlong function, jbyteArray identifier_in) {
    const jsize length = env->GetArrayLength(identifier_in);
    std::vector<jbyte> identifier(length);
    env->GetByteArrayRegion(identifier_in, 0, length, identifier.data());

    using SetAndroidId = int (*)(const char *, unsigned int);
    return reinterpret_cast<SetAndroidId>(function)(
            reinterpret_cast<const char *>(identifier.data()),
            static_cast<unsigned int>(length));
}

/**
 * ADIGetLoginCode: {@code int(ulong dsId)}.
 *
 * Doubles as "is this machine provisioned" - 0 means yes, -45061 means not yet. Any other
 * value is a real error, and that distinction is the caller's to make.
 */
JNIEXPORT jint JNICALL
Java_dev_wander_android_opentagviewer_anisette_NativeAdi_getLoginCode(
        JNIEnv *, jclass, jlong function, jlong ds_id) {
    using GetLoginCode = int (*)(unsigned long long);
    return reinterpret_cast<GetLoginCode>(function)(static_cast<unsigned long long>(ds_id));
}

/** ADIProvisioningDestroy: {@code int(uint session)}. Abandons an unfinished session. */
JNIEXPORT jint JNICALL
Java_dev_wander_android_opentagviewer_anisette_NativeAdi_provisioningDestroy(
        JNIEnv *, jclass, jlong function, jint session) {
    using ProvisioningDestroy = int (*)(unsigned int);
    return reinterpret_cast<ProvisioningDestroy>(function)(static_cast<unsigned int>(session));
}

/**
 * ADIProvisioningStart: {@code int(ulong, ubyte*, uint, ubyte**, uint*, uint*)}.
 *
 * Takes the server's provisioning intermediate metadata and produces the client's, plus a
 * session id that ADIProvisioningEnd needs.
 *
 * ADI allocates the output buffer, so it is copied into a Java array and handed straight back
 * to ADIDispose here rather than leaving ownership of a raw pointer on the Java side. That is
 * why the dispose function is passed in too.
 *
 * @param out receives {session, adi error code}. Must have room for two ints.
 * @return the client metadata, or null if ADI returned an error.
 */
JNIEXPORT jbyteArray JNICALL
Java_dev_wander_android_opentagviewer_anisette_NativeAdi_provisioningStart(
        JNIEnv *env, jclass, jlong function, jlong dispose, jlong ds_id,
        jbyteArray spim_in, jintArray out) {
    const jsize spim_length = env->GetArrayLength(spim_in);
    std::vector<jbyte> spim(spim_length);
    env->GetByteArrayRegion(spim_in, 0, spim_length, spim.data());

    unsigned char *cpim = nullptr;
    unsigned int cpim_length = 0;
    unsigned int session = 0;

    using ProvisioningStart = int (*)(unsigned long long, unsigned char *, unsigned int,
                                      unsigned char **, unsigned int *, unsigned int *);
    const int result = reinterpret_cast<ProvisioningStart>(function)(
            static_cast<unsigned long long>(ds_id),
            reinterpret_cast<unsigned char *>(spim.data()),
            static_cast<unsigned int>(spim_length),
            &cpim, &cpim_length, &session);

    jint results[2] = {static_cast<jint>(session), result};
    env->SetIntArrayRegion(out, 0, 2, results);

    if (result != 0 || cpim == nullptr) {
        LOGE("ADIProvisioningStart returned %d", result);
        return nullptr;
    }

    jbyteArray copy = env->NewByteArray(static_cast<jsize>(cpim_length));
    env->SetByteArrayRegion(copy, 0, static_cast<jsize>(cpim_length),
                            reinterpret_cast<const jbyte *>(cpim));

    using Dispose = int (*)(void *);
    reinterpret_cast<Dispose>(dispose)(cpim);

    LOGI("ADIProvisioningStart ok: session %u, %u bytes of client metadata",
         session, cpim_length);
    return copy;
}

/** ADIProvisioningEnd: {@code int(uint session, ubyte *ptm, uint, ubyte *tk, uint)}. */
JNIEXPORT jint JNICALL
Java_dev_wander_android_opentagviewer_anisette_NativeAdi_provisioningEnd(
        JNIEnv *env, jclass, jlong function, jint session,
        jbyteArray ptm_in, jbyteArray tk_in) {
    const jsize ptm_length = env->GetArrayLength(ptm_in);
    std::vector<jbyte> ptm(ptm_length);
    env->GetByteArrayRegion(ptm_in, 0, ptm_length, ptm.data());

    const jsize tk_length = env->GetArrayLength(tk_in);
    std::vector<jbyte> tk(tk_length);
    env->GetByteArrayRegion(tk_in, 0, tk_length, tk.data());

    using ProvisioningEnd = int (*)(unsigned int, unsigned char *, unsigned int,
                                    unsigned char *, unsigned int);
    const int result = reinterpret_cast<ProvisioningEnd>(function)(
            static_cast<unsigned int>(session),
            reinterpret_cast<unsigned char *>(ptm.data()), static_cast<unsigned int>(ptm_length),
            reinterpret_cast<unsigned char *>(tk.data()), static_cast<unsigned int>(tk_length));

    LOGI("ADIProvisioningEnd(session %d) = %d", session, result);
    return result;
}

/**
 * ADIOTPRequest: {@code int(ulong, ubyte**, uint*, ubyte**, uint*)}.
 *
 * The one-time password, produced fresh for every login once the machine is provisioned. ADI
 * allocates both outputs, so both are copied out and handed back to ADIDispose here.
 *
 * Beware the order: the machine identifier is the first pair, the password the second. Both
 * are ubyte**, so getting it backwards is silent.
 *
 * @return {machineIdentifier, oneTimePassword}, or null if ADI returned an error
 */
JNIEXPORT jobjectArray JNICALL
Java_dev_wander_android_opentagviewer_anisette_NativeAdi_otpRequest(
        JNIEnv *env, jclass, jlong function, jlong dispose, jlong ds_id, jintArray out) {
    unsigned char *machine_id = nullptr;
    unsigned int machine_id_length = 0;
    unsigned char *otp = nullptr;
    unsigned int otp_length = 0;

    using OtpRequest = int (*)(unsigned long long, unsigned char **, unsigned int *,
                               unsigned char **, unsigned int *);
    const int result = reinterpret_cast<OtpRequest>(function)(
            static_cast<unsigned long long>(ds_id),
            &machine_id, &machine_id_length,
            &otp, &otp_length);

    env->SetIntArrayRegion(out, 0, 1, &result);

    if (result != 0 || machine_id == nullptr || otp == nullptr) {
        LOGE("ADIOTPRequest returned %d", result);
        return nullptr;
    }

    jbyteArray machine_id_copy = env->NewByteArray(static_cast<jsize>(machine_id_length));
    env->SetByteArrayRegion(machine_id_copy, 0, static_cast<jsize>(machine_id_length),
                            reinterpret_cast<const jbyte *>(machine_id));

    jbyteArray otp_copy = env->NewByteArray(static_cast<jsize>(otp_length));
    env->SetByteArrayRegion(otp_copy, 0, static_cast<jsize>(otp_length),
                            reinterpret_cast<const jbyte *>(otp));

    using Dispose = int (*)(void *);
    reinterpret_cast<Dispose>(dispose)(machine_id);
    reinterpret_cast<Dispose>(dispose)(otp);

    jobjectArray pair = env->NewObjectArray(
            2, env->FindClass("[B"), nullptr);
    env->SetObjectArrayElement(pair, 0, machine_id_copy);
    env->SetObjectArrayElement(pair, 1, otp_copy);

    LOGI("ADIOTPRequest ok: %u byte machine id, %u byte password",
         machine_id_length, otp_length);
    return pair;
}

}  // extern "C"
