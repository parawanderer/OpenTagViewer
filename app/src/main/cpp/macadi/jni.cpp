// JNI face of the emulated Mac: dev.wander.android.opentagviewer.anisette.MacCoreAdi.
//
// A handle is a CoreAdi* passed to Java as a long. Java owns its lifetime and uses it from one
// thread at a time, under its own lock. Every native failure becomes an IllegalStateException
// carrying the C++ message, except "not provisioned", which has its own type because it is the
// one failure with a remedy.

#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include "core_adi.h"
#include "error.h"

namespace {

constexpr const char *STATE_EXCEPTION = "java/lang/IllegalStateException";
constexpr const char *NOT_PROVISIONED =
        "dev/wander/android/opentagviewer/anisette/MacCoreAdi$NotProvisionedException";

macadi::CoreAdi *adi(jlong handle) { return reinterpret_cast<macadi::CoreAdi *>(handle); }

std::vector<uint8_t> bytes(JNIEnv *env, jbyteArray array) {
    if (array == nullptr) return {};
    const jsize n = env->GetArrayLength(array);
    std::vector<uint8_t> out(static_cast<size_t>(n));
    if (n > 0) env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray array(JNIEnv *env, const std::vector<uint8_t> &data) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(data.size()));
    if (out != nullptr && !data.empty()) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(data.size()),
                                reinterpret_cast<const jbyte *>(data.data()));
    }
    return out;
}

void raise(JNIEnv *env, const char *type, const char *message) {
    jclass cls = env->FindClass(type);
    if (cls != nullptr) env->ThrowNew(cls, message);
}

// Runs `body`, turning any C++ exception into a Java one. Nothing may unwind past a JNI frame.
template <typename T, typename F>
T guarded(JNIEnv *env, T onError, F body) {
    try {
        return body();
    } catch (const macadi::NotProvisioned &e) {
        raise(env, NOT_PROVISIONED, e.what());
    } catch (const std::exception &e) {
        raise(env, STATE_EXCEPTION, e.what());
    } catch (...) {
        raise(env, STATE_EXCEPTION, "unknown native failure");
    }
    return onError;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_wander_android_opentagviewer_anisette_MacCoreAdi_nativeOpen(
        JNIEnv *env, jclass, jbyteArray image, jlong dispatch) {
    return guarded<jlong>(env, 0, [&]() {
        auto opened = std::make_unique<macadi::CoreAdi>(bytes(env, image),
                                                        static_cast<uint64_t>(dispatch));
        return reinterpret_cast<jlong>(opened.release());
    });
}

JNIEXPORT void JNICALL
Java_dev_wander_android_opentagviewer_anisette_MacCoreAdi_nativeClose(JNIEnv *, jclass,
                                                                      jlong handle) {
    delete adi(handle);
}

JNIEXPORT void JNICALL
Java_dev_wander_android_opentagviewer_anisette_MacCoreAdi_nativeSetHostUuid(
        JNIEnv *env, jclass, jlong handle, jbyteArray uuid) {
    guarded<int>(env, 0, [&]() {
        const std::vector<uint8_t> raw = bytes(env, uuid);
        if (raw.size() != 16) throw macadi::Error("a host UUID is 16 bytes");
        adi(handle)->setHostUuid(raw.data());
        return 0;
    });
}

JNIEXPORT void JNICALL
Java_dev_wander_android_opentagviewer_anisette_MacCoreAdi_nativeSetFrozenTime(
        JNIEnv *, jclass, jlong handle, jlong secs) {
    adi(handle)->setFrozenTime(secs);
}

JNIEXPORT void JNICALL
Java_dev_wander_android_opentagviewer_anisette_MacCoreAdi_nativeStorePut(
        JNIEnv *env, jclass, jlong handle, jstring name, jbyteArray data) {
    guarded<int>(env, 0, [&]() {
        const char *chars = env->GetStringUTFChars(name, nullptr);
        const std::string n = chars == nullptr ? std::string() : chars;
        if (chars != nullptr) env->ReleaseStringUTFChars(name, chars);
        adi(handle)->storePut(n, bytes(env, data));
        return 0;
    });
}

// The store as alternating {name bytes (UTF-8), contents} entries.
JNIEXPORT jobjectArray JNICALL
Java_dev_wander_android_opentagviewer_anisette_MacCoreAdi_nativeStoreFiles(JNIEnv *env, jclass,
                                                                           jlong handle) {
    return guarded<jobjectArray>(env, nullptr, [&]() -> jobjectArray {
        const auto files = adi(handle)->storeFiles();
        jclass bytesClass = env->FindClass("[B");
        jobjectArray out = env->NewObjectArray(static_cast<jsize>(files.size() * 2), bytesClass, nullptr);
        if (out == nullptr) return nullptr;
        for (size_t i = 0; i < files.size(); i++) {
            const std::vector<uint8_t> name(files[i].first.begin(), files[i].first.end());
            jbyteArray n = array(env, name);
            jbyteArray c = array(env, files[i].second);
            env->SetObjectArrayElement(out, static_cast<jsize>(i * 2), n);
            env->SetObjectArrayElement(out, static_cast<jsize>(i * 2 + 1), c);
            env->DeleteLocalRef(n);
            env->DeleteLocalRef(c);
        }
        return out;
    });
}

// {mid, otp}; routing goes into routingOut[0].
JNIEXPORT jobjectArray JNICALL
Java_dev_wander_android_opentagviewer_anisette_MacCoreAdi_nativeHeaders(
        JNIEnv *env, jclass, jlong handle, jlong dsId, jlongArray routingOut) {
    return guarded<jobjectArray>(env, nullptr, [&]() -> jobjectArray {
        const macadi::Headers h = adi(handle)->headers(dsId);
        const jlong routing = static_cast<jlong>(h.routing);
        env->SetLongArrayRegion(routingOut, 0, 1, &routing);
        jobjectArray out = env->NewObjectArray(2, env->FindClass("[B"), nullptr);
        if (out == nullptr) return nullptr;
        env->SetObjectArrayElement(out, 0, array(env, h.mid));
        env->SetObjectArrayElement(out, 1, array(env, h.otp));
        return out;
    });
}

// The cpim; the session goes into sessionOut[0].
JNIEXPORT jbyteArray JNICALL
Java_dev_wander_android_opentagviewer_anisette_MacCoreAdi_nativeProvisioningBegin(
        JNIEnv *env, jclass, jlong handle, jlong dsId, jbyteArray spim, jintArray sessionOut) {
    return guarded<jbyteArray>(env, nullptr, [&]() {
        const auto started = adi(handle)->provisioningBegin(dsId, bytes(env, spim));
        const jint session = static_cast<jint>(started.second);
        env->SetIntArrayRegion(sessionOut, 0, 1, &session);
        return array(env, started.first);
    });
}

JNIEXPORT void JNICALL
Java_dev_wander_android_opentagviewer_anisette_MacCoreAdi_nativeProvisioningFinish(
        JNIEnv *env, jclass, jlong handle, jlong dsId, jint session, jbyteArray ptm,
        jbyteArray tk, jlong routing) {
    guarded<int>(env, 0, [&]() {
        adi(handle)->provisioningFinish(dsId, static_cast<uint32_t>(session), bytes(env, ptm),
                                        bytes(env, tk), static_cast<uint64_t>(routing));
        return 0;
    });
}

}  // extern "C"
