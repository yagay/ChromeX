#include "download_conflict_hook.h"

#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_yagay_chromex_NativeDownloadConflictBridge_nativeInstall(
        JNIEnv* env, jclass, jstring version) {
    const char* raw = version == nullptr ? nullptr : env->GetStringUTFChars(version, nullptr);
    chromex::HookResult result = chromex::InstallDownloadConflictHook(raw);
    if (version != nullptr && raw != nullptr) env->ReleaseStringUTFChars(version, raw);
    return env->NewStringUTF(result.detail.c_str());
}
