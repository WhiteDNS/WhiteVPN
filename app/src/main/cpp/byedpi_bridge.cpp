#include <jni.h>

#include <cstdlib>
#include <cstring>
#include <getopt.h>
#include <mutex>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include "jni_helper.h"

extern "C" {
#include "params.h"

extern struct params params;
extern int server_fd;

int main(int argc, char **argv);
void clear_params(char *line, char **argv);
}

static std::mutex g_proxy_mutex;
static bool g_proxy_running;
static jobject g_protect_interface;
static jmethodID g_protect_method;

static struct params default_params() {
    struct params value {};
    value.await_int = 10;
    value.ipv6 = true;
    value.resolve = true;
    value.udp = true;
    value.max_open = 512;
    value.bfsize = 16384;
    value.baddr.in6.sin6_family = AF_INET6;
    value.laddr.in.sin_family = AF_INET;
    value.debug = 0;
    return value;
}

static void reset_params() {
    clear_params(nullptr, nullptr);
    params = default_params();
    server_fd = -1;
    optind = 1;
    optarg = nullptr;
    optopt = 0;
}

static char **copy_args(JNIEnv *env, jobjectArray args, int *argc_out) {
    const auto argc = env->GetArrayLength(args);
    auto argv = static_cast<char **>(calloc(argc + 1, sizeof(char *)));
    if (argv == nullptr) return nullptr;

    for (jsize i = 0; i < argc; ++i) {
        const auto value = reinterpret_cast<jstring>(env->GetObjectArrayElement(args, i));
        const char *utf = value == nullptr ? "" : env->GetStringUTFChars(value, nullptr);
        argv[i] = strdup(utf == nullptr ? "" : utf);
        if (value != nullptr && utf != nullptr) {
            env->ReleaseStringUTFChars(value, utf);
        }
        if (value != nullptr) {
            env->DeleteLocalRef(value);
        }
        if (argv[i] == nullptr) {
            for (jsize j = 0; j < i; ++j) free(argv[j]);
            free(argv);
            return nullptr;
        }
    }
    argv[argc] = nullptr;
    *argc_out = argc;
    return argv;
}

static void free_args(char **argv, int argc) {
    if (argv == nullptr) return;
    for (int i = 0; i < argc; ++i) free(argv[i]);
    free(argv);
}

extern "C" int whitedns_byedpi_protect_fd(int fd) {
    jobject protect_interface = nullptr;
    jmethodID protect_method = nullptr;
    {
        const std::lock_guard<std::mutex> lock(g_proxy_mutex);
        protect_interface = g_protect_interface;
        protect_method = g_protect_method;
    }
    if (protect_interface == nullptr || protect_method == nullptr) return 0;

    ATTACH_JNI();
    env->CallVoidMethod(protect_interface, protect_method, fd);
    if (!env->ExceptionCheck()) return 0;
    env->ExceptionClear();
    return -1;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_whitedns_vpn_ByeDpiProxy_jniStartProxy(JNIEnv *env, jobject thiz, jobjectArray args, jobject protect) {
    if (args == nullptr || protect == nullptr) return -1;

    const auto protect_class = env->GetObjectClass(protect);
    const auto protect_method = env->GetMethodID(protect_class, "protect", "(I)V");
    env->DeleteLocalRef(protect_class);
    if (protect_method == nullptr) return -1;

    int argc = 0;
    char **argv = copy_args(env, args, &argc);
    if (argv == nullptr) return -1;

    {
        const std::lock_guard<std::mutex> lock(g_proxy_mutex);
        if (g_proxy_running) {
            free_args(argv, argc);
            return -2;
        }
        g_proxy_running = true;
        g_protect_interface = env->NewGlobalRef(protect);
        g_protect_method = protect_method;
    }

    reset_params();
    const int result = main(argc, argv);
    free_args(argv, argc);

    jobject protect_interface = nullptr;
    {
        const std::lock_guard<std::mutex> lock(g_proxy_mutex);
        g_proxy_running = false;
        protect_interface = g_protect_interface;
        g_protect_interface = nullptr;
        g_protect_method = nullptr;
    }
    if (protect_interface != nullptr) {
        env->DeleteGlobalRef(protect_interface);
    }
    return result;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_whitedns_vpn_ByeDpiProxy_jniStopProxy(JNIEnv *env, jobject thiz) {
    bool running = false;
    {
        const std::lock_guard<std::mutex> lock(g_proxy_mutex);
        running = g_proxy_running;
        g_proxy_running = false;
    }
    if (!running) return 0;
    if (server_fd > 0) {
        shutdown(server_fd, SHUT_RDWR);
        close(server_fd);
        server_fd = -1;
    }
    return 0;
}
