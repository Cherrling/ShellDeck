// SPDX-License-Identifier: GPL-3.0-only
#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define JNI_FN(name) Java_cc_cherr_shelldeck_mosh_NativePty_##name
static void fail(JNIEnv *env) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "PTY operation failed");
}
static char **strings(JNIEnv *env, jobjectArray array) {
    jsize n = (*env)->GetArrayLength(env, array);
    char **values = calloc((size_t)n + 1, sizeof(char *));
    if (!values) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring value = (*env)->GetObjectArrayElement(env, array, i);
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        if (utf) { values[i] = strdup(utf); (*env)->ReleaseStringUTFChars(env, value, utf); }
        (*env)->DeleteLocalRef(env, value);
        if (!values[i]) { for (jsize j = 0; j < i; j++) free(values[j]); free(values); return NULL; }
    }
    return values;
}
static void clear_strings(char **values) {
    if (!values) return;
    for (int i = 0; values[i]; i++) { volatile char *v = values[i]; size_t n = strlen(values[i]); while (n--) *v++ = 0; free(values[i]); }
    free(values);
}
JNIEXPORT jintArray JNICALL JNI_FN(spawn)(JNIEnv *env, jobject self, jobjectArray args, jobjectArray environment, jint rows, jint cols) {
    (void)self;
    char **argv = strings(env, args), **envp = strings(env, environment);
    if (!argv || !envp) { clear_strings(argv); clear_strings(envp); fail(env); return NULL; }
    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC), slave = -1;
    char path[128];
    if (master < 0 || grantpt(master) || unlockpt(master) || ptsname_r(master, path, sizeof(path))) goto error;
    slave = open(path, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (slave < 0) goto error;
    struct winsize size = {.ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols};
    if (ioctl(slave, TIOCSWINSZ, &size)) goto error;
    struct rlimit limit;
    if (getrlimit(RLIMIT_NOFILE, &limit)) goto error;
    pid_t pid = fork();
    if (pid < 0) goto error;
    if (pid == 0) {
        // No allocation/JNI/logging after fork in the multithreaded Android process.
        if (setsid() < 0 || ioctl(slave, TIOCSCTTY, 0) < 0) _exit(126);
        for (int i = 0; i < 3; i++) if (dup2(slave, i) < 0) _exit(126);
#ifdef __NR_close_range
        if (syscall(__NR_close_range, 3u, ~0u, 0u) != 0)
#endif
            for (rlim_t fd = 3; fd < limit.rlim_cur; fd++) close((int)fd);
        sigset_t empty; sigemptyset(&empty); sigprocmask(SIG_SETMASK, &empty, NULL);
        struct sigaction action = {.sa_handler = SIG_DFL}; sigemptyset(&action.sa_mask);
        for (int s = 1; s < NSIG; s++) sigaction(s, &action, NULL);
        execve(argv[0], argv, envp);
        _exit(127);
    }
    close(slave);
    clear_strings(argv); clear_strings(envp);
    jint values[] = {master, pid};
    jintArray result = (*env)->NewIntArray(env, 2);
    if (!result) { kill(pid, SIGKILL); while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {} close(master); return NULL; }
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
error:
    if (slave >= 0) close(slave);
    if (master >= 0) close(master);
    clear_strings(argv); clear_strings(envp); fail(env); return NULL;
}
JNIEXPORT void JNICALL JNI_FN(resize)(JNIEnv *env, jobject self, jint fd, jint rows, jint cols) {
    (void)self;
    struct winsize size = {.ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols};
    if (ioctl(fd, TIOCSWINSZ, &size)) fail(env);
}
JNIEXPORT void JNICALL JNI_FN(awaitExit)(JNIEnv *env, jobject self, jint pid) {
    (void)self;
    siginfo_t info;
    int result;
    do { result = waitid(P_PID, (id_t)pid, &info, WEXITED | WNOWAIT); } while (result < 0 && errno == EINTR);
    if (result < 0) fail(env);
}
JNIEXPORT jint JNICALL JNI_FN(reap)(JNIEnv *env, jobject self, jint pid) {
    (void)self;
    int status, result;
    do { result = waitpid(pid, &status, 0); } while (result < 0 && errno == EINTR);
    if (result < 0) { fail(env); return 1; }
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}
JNIEXPORT void JNICALL JNI_FN(signal)(JNIEnv *env, jobject self, jint pid, jint signal) {
    (void)self;
    if (pid > 0 && kill(pid, signal) && errno != ESRCH) fail(env);
}
