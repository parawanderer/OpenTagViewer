// Stands in for the NDK's <android/log.h> when the stubs are built for the host.
//
// The stubs log every call. That matters on a device and is noise here, where the tests call every
// stub on purpose, so logging does nothing - but still evaluates to an int, as the real one does.

#pragma once

enum { ANDROID_LOG_DEBUG = 3, ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };

#define __android_log_print(...) 0
