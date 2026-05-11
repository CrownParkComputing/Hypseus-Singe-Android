#include <jni.h>

#include <cerrno>
#include <cstring>
#include <strings.h>
#include <string>
#include <unistd.h>
#include <vector>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

#include <SDL_system.h>

#include "../hypseus.h"

namespace {

#if defined(__ANDROID__)
constexpr const char *kAndroidLogTag = "HypseusJNI";
#endif

static std::string jstring_to_utf8(JNIEnv *env, jstring value)
{
    if (!value) {
        return std::string();
    }

    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) {
        return std::string();
    }

    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

static bool has_cli_switch(const std::vector<std::string> &args, const char *sw)
{
    for (const std::string &arg : args) {
        if (strcasecmp(arg.c_str(), sw) == 0) {
            return true;
        }
    }
    return false;
}

static std::string default_android_storage_dir()
{
#if defined(__ANDROID__)
    const char *internal = SDL_AndroidGetInternalStoragePath();
    if (internal && internal[0] != '\0') {
        return std::string(internal);
    }
#endif
    return std::string();
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_org_hypseus_singe_NativeBridge_nativeRun(
    JNIEnv *env,
    jclass,
    jobjectArray args,
    jstring homeDir,
    jstring dataDir)
{
    std::vector<std::string> user_args;
    user_args.reserve(32);

    if (args) {
        const jsize argc = env->GetArrayLength(args);
        for (jsize i = 0; i < argc; ++i) {
            jstring arg = static_cast<jstring>(env->GetObjectArrayElement(args, i));
            user_args.emplace_back(jstring_to_utf8(env, arg));
            env->DeleteLocalRef(arg);
        }
    }

    std::vector<std::string> owned_args;
    owned_args.reserve(user_args.size() + 8);
    owned_args.emplace_back("hypseus");

    // Add user args first so that <game> and <ldp> are at argv[1] and argv[2]
    owned_args.insert(owned_args.end(), user_args.begin(), user_args.end());

    std::string home = jstring_to_utf8(env, homeDir);
    std::string data = jstring_to_utf8(env, dataDir);

    const bool has_home_switch = has_cli_switch(user_args, "-homedir");
    const bool has_data_switch = has_cli_switch(user_args, "-datadir");

    const std::string default_dir = default_android_storage_dir();
    if (home.empty()) {
        home = default_dir;
    }
    if (data.empty()) {
        data = default_dir;
    }

    if (!has_home_switch && !home.empty()) {
        owned_args.emplace_back("-homedir");
        owned_args.emplace_back(home);
    }

    if (!has_data_switch && !data.empty()) {
        owned_args.emplace_back("-datadir");
        owned_args.emplace_back(data);
    }

    std::vector<char *> argv;
    argv.reserve(owned_args.size());
    for (std::string &arg : owned_args) {
        argv.push_back(const_cast<char *>(arg.c_str()));
    }

    const int argc = static_cast<int>(argv.size());

#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, kAndroidLogTag, "nativeRun start: argc=%d, home=%s, data=%s", argc,
                        home.empty() ? "(empty)" : home.c_str(),
                        data.empty() ? "(empty)" : data.c_str());
    for (int i = 0; i < argc; ++i) {
        __android_log_print(ANDROID_LOG_INFO, kAndroidLogTag, "argv[%d]=%s", i, argv[i] ? argv[i] : "(null)");
    }
#endif

    if (!home.empty() && chdir(home.c_str()) != 0) {
#if defined(__ANDROID__)
        __android_log_print(ANDROID_LOG_WARN, kAndroidLogTag, "chdir(%s) failed: %s", home.c_str(), std::strerror(errno));
#endif
    }

    const int rc = hypseus_run(argc, argv.data(), false);

#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, kAndroidLogTag, "nativeRun end: rc=%d", rc);
#endif

    return rc;
}
