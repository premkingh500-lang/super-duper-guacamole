// MagicMobile core: hooks the game's frame present (GLES / Vulkan detection) and draws a test marker.
#include <jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <atomic>
#include <thread>
#include <chrono>
#include "shadowhook.h"

#define TAG "MagicMobile"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static std::atomic<bool> g_started{false};
static std::atomic<bool> g_egl_hooked{false};
static std::atomic<bool> g_vk_hooked{false};
static std::atomic<unsigned long long> g_frames{0};

typedef EGLBoolean (*SwapFn)(EGLDisplay, EGLSurface);
typedef int (*VkPresentFn)(void*, const void*);

static void drawMarker() {
    GLboolean sc = glIsEnabled(GL_SCISSOR_TEST);
    GLint box[4]; glGetIntegerv(GL_SCISSOR_BOX, box);
    GLfloat cc[4]; glGetFloatv(GL_COLOR_CLEAR_VALUE, cc);
    glEnable(GL_SCISSOR_TEST);
    glScissor(0, 0, 96, 96);
    glClearColor(1.f, 0.f, 1.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    glClearColor(cc[0], cc[1], cc[2], cc[3]);
    glScissor(box[0], box[1], box[2], box[3]);
    if (!sc) glDisable(GL_SCISSOR_TEST);
}

static EGLBoolean my_swap(EGLDisplay d, EGLSurface s) {
    SHADOWHOOK_STACK_SCOPE();
    unsigned long long n = ++g_frames;
    if (n == 1) {
        LOGI("first eglSwapBuffers: GL_VERSION=%s GL_RENDERER=%s",
             (const char*)glGetString(GL_VERSION), (const char*)glGetString(GL_RENDERER));
    }
    if (n % 600 == 0) LOGI("frames presented: %llu", n);
    drawMarker();
    return SHADOWHOOK_CALL_PREV(my_swap, d, s);
}

static int my_vk_present(void* queue, const void* info) {
    SHADOWHOOK_STACK_SCOPE();
    unsigned long long n = ++g_frames;
    if (n == 1) LOGI("first vkQueuePresentKHR: game uses VULKAN");
    return SHADOWHOOK_CALL_PREV(my_vk_present, queue, info);
}

static void hookThread() {
    shadowhook_init(SHADOWHOOK_MODE_SHARED, false);
    LOGI("hook thread started");
    for (int i = 0; i < 600 && !(g_egl_hooked && g_vk_hooked); i++) {
        if (!g_egl_hooked) {
            void* st = shadowhook_hook_sym_name("libEGL.so", "eglSwapBuffers", (void*)my_swap, nullptr);
            if (st) { g_egl_hooked = true; LOGI("hooked eglSwapBuffers"); }
        }
        if (!g_vk_hooked) {
            void* st = shadowhook_hook_sym_name("libvulkan.so", "vkQueuePresentKHR", (void*)my_vk_present, nullptr);
            if (st) { g_vk_hooked = true; LOGI("hooked vkQueuePresentKHR"); }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
    }
    LOGI("hook thread done: egl=%d vk=%d", (int)g_egl_hooked, (int)g_vk_hooked);
}

static void startOnce() {
    bool exp = false;
    if (g_started.compare_exchange_strong(exp, true)) std::thread(hookThread).detach();
}

__attribute__((constructor)) static void onLoad() { LOGI("libmagicmobile loaded"); startOnce(); }

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) { startOnce(); return JNI_VERSION_1_6; }
