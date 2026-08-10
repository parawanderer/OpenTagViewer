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
Java_dev_wander_android_opentagviewer_poc_NativeAdiProbe_open(
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
Java_dev_wander_android_opentagviewer_poc_NativeAdiProbe_resolve(
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
 * Call ADILoadLibraryWithPath, which is how libstoreservicescore is told which directory
 * holds libCoreADI.so. It opens that one itself.
 *
 * The function is passed as an already-resolved address rather than looked up here, because
 * its name differs between APK builds and belongs in one place.
 *
 * @return ADI's own return code - 0 is success, anything else is an ADI error number.
 */
JNIEXPORT jint JNICALL
Java_dev_wander_android_opentagviewer_poc_NativeAdiProbe_loadLibraryWithPath(
        JNIEnv *env, jclass, jlong function, jstring path_in) {
    const std::string path = to_utf8(env, path_in);

    using ADILoadLibraryWithPath = int (*)(const char *);
    const auto call = reinterpret_cast<ADILoadLibraryWithPath>(function);

    const int result = call(path.c_str());
    LOGI("ADILoadLibraryWithPath(%s) = %d", path.c_str(), result);
    return result;
}

}  // extern "C"
