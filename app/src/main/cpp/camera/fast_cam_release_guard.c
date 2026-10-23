#include "fast_cam_ipc.h"

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>

typedef ssize_t (*send_fn)(int, const void*, size_t, int);
typedef ssize_t (*sendmsg_fn)(int, const struct msghdr*, int);
typedef void (*term_handler_fn)(int);
typedef term_handler_fn (*signal_fn)(int, term_handler_fn);

enum { FAST_CAM_RELEASE_TIMEOUT_MS = 3000 };
// After a stop request every wait ends this long after the request.
enum { FAST_CAM_RELEASE_STOP_GRACE_MS = 5000 };
// Outside SIGTERMs come from stoppers that SIGKILL after 1-3 s; the cameras must close first.
enum { FAST_CAM_RELEASE_SIGNAL_STOP_GRACE_MS = 200 };
// Waits re-check the stop deadline at least this often.
enum { FAST_CAM_RELEASE_POLL_SLICE_MS = 100 };

enum {
    RELEASE_CONFIRMED = 0,
    RELEASE_CONSUMER_GONE = 1,
    RELEASE_FAILED = -1,
};

/*
 * Durable record of the guard failure that stopped the producer. Must match
 * DiLink5QCarCamBackend.RELEASE_GUARD_REASON_PATH, which logs and consumes
 * the file when it classifies the child's unexpected exit. The daemon log
 * gets truncated in place (log_2MEH8B86 lost the initiating exit-125 cause
 * that way); this file survives truncation, and the stderr copy reaches the
 * daemon's [FastCamProc] output drainer while it is still attached.
 *
 * The directory is the shared scratch root. Head units whose shell cannot
 * create under /data/local/tmp relocate it; the Java side publishes the live
 * root in OVERDRIVE_SCRATCH (ScratchRoot.applyEnv on the ProcessBuilder), so
 * the guard resolves the same directory as its consumer. Unset → legacy.
 */
#define REASON_FILE_NAME "/overdrive_dilink5_release_guard_reason"
#define REASON_LEGACY_DIR "/data/local/tmp"
#define REASON_PATH_MAX 512

static const char* reason_file_path(void) {
    static char path[REASON_PATH_MAX];
    static atomic_int resolved = ATOMIC_VAR_INIT(0);
    if (atomic_load(&resolved)) return path;

    const char* root = getenv("OVERDRIVE_SCRATCH");
    size_t root_len = root ? strlen(root) : 0;
    /* Reject empty, relative, or over-long roots: fall back to legacy. */
    if (root_len == 0 || root[0] != '/'
            || root_len + sizeof(REASON_FILE_NAME) > sizeof(path)) {
        root = REASON_LEGACY_DIR;
        root_len = sizeof(REASON_LEGACY_DIR) - 1;
    }
    while (root_len > 1 && root[root_len - 1] == '/') root_len--;
    memcpy(path, root, root_len);
    memcpy(path + root_len, REASON_FILE_NAME, sizeof(REASON_FILE_NAME));
    atomic_store(&resolved, 1);
    return path;
}

/* Resolve at load so the mid-failure path never touches the environment. */
__attribute__((constructor))
static void resolve_reason_file_path_at_load(void) {
    (void)reason_file_path();
    // A dead stdout reader must not kill the producer before it stops its cameras.
    signal(SIGPIPE, SIG_IGN);
}

static send_fn real_send;
static sendmsg_fn real_sendmsg;
static signal_fn real_signal;
static pthread_once_t symbol_once = PTHREAD_ONCE_INIT;
static atomic_int camera_socket = ATOMIC_VAR_INIT(-1);

static void resolve_symbols_once(void) {
    real_send = (send_fn)dlsym(RTLD_NEXT, "send");
    real_sendmsg = (sendmsg_fn)dlsym(RTLD_NEXT, "sendmsg");
    real_signal = (signal_fn)dlsym(RTLD_NEXT, "signal");
}

static int resolve_symbols(void) {
    pthread_once(&symbol_once, resolve_symbols_once);
    return real_send && real_sendmsg;
}

static int64_t monotonic_millis(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static int remaining_millis(int64_t deadline) {
    int64_t now = monotonic_millis();
    if (now < 0 || now >= deadline) return 0;
    int64_t remaining = deadline - now;
    return remaining > INT32_MAX ? INT32_MAX : (int)remaining;
}

/*
 * fast_cam_capture polls each camera with a 1 ms qcarcam_get_frame timeout and the vendor AIS
 * client logs every timeout (rc 12) as two ERR lines — ~2000 lines/s that pin logd. Rate-limit
 * only those lines in this process. setprop persist.overdrive.fast_cam_ais_log 1 opts out.
 */
#define AIS_LOG_OPT_OUT_PROPERTY "persist.overdrive.fast_cam_ais_log"
enum { AIS_LOG_WINDOW_MS = 60000, AIS_LOG_PASS_PER_WINDOW = 5 };

/* liblog's struct __android_log_message (API 30+); the NDK header hides it below minSdk 30. */
typedef struct {
    size_t struct_size;
    int32_t buffer_id;
    int32_t priority;
    const char* tag;
    const char* file;
    uint32_t line;
    const char* message;
} guard_log_message_t;

typedef void (*guard_logger_fn)(const guard_log_message_t*);
typedef void (*guard_set_logger_fn)(guard_logger_fn);

static guard_logger_fn logd_logger;
static _Atomic int64_t ais_window_start_ms = ATOMIC_VAR_INIT(0);
static atomic_uint ais_window_passed = ATOMIC_VAR_INIT(0);
static atomic_uint ais_window_suppressed = ATOMIC_VAR_INIT(0);

static int is_ais_frame_timeout_line(const guard_log_message_t* log_message) {
    const char* text = log_message->message;
    if (!log_message->tag || !text || strcmp(log_message->tag, "AIS-LOG") != 0) return 0;
    if (strncmp(text, "ais_get_frame:", 14) != 0
            && strncmp(text, "ais_client_process_cmd:", 23) != 0) {
        return 0;
    }
    size_t n = strlen(text);
    while (n > 0 && (text[n - 1] == '\n' || text[n - 1] == '\r' || text[n - 1] == ' ')) n--;
    return n >= 3 && text[n - 3] == ' ' && text[n - 2] == '1' && text[n - 1] == '2';
}

static void ais_rate_limited_logger(const guard_log_message_t* log_message) {
    if (!is_ais_frame_timeout_line(log_message)) {
        logd_logger(log_message);
        return;
    }
    int64_t now = monotonic_millis();
    int64_t start = atomic_load(&ais_window_start_ms);
    if (now >= 0 && now - start >= AIS_LOG_WINDOW_MS
            && atomic_compare_exchange_strong(&ais_window_start_ms, &start, now)) {
        unsigned suppressed = atomic_exchange(&ais_window_suppressed, 0u);
        atomic_store(&ais_window_passed, 0u);
        if (suppressed > 0) {
            char text[160];
            snprintf(text, sizeof(text),
                    "suppressed %u AIS get_frame timeout lines in the last %llds",
                    suppressed, (long long)((now - start) / 1000));
            guard_log_message_t summary = {
                sizeof(summary), 0 /* LOG_ID_MAIN */, 5 /* ANDROID_LOG_WARN */,
                "FastCamGuard", NULL, 0, text,
            };
            logd_logger(&summary);
        }
    }
    if (atomic_fetch_add(&ais_window_passed, 1u) < AIS_LOG_PASS_PER_WINDOW) {
        logd_logger(log_message);
    } else {
        atomic_fetch_add(&ais_window_suppressed, 1u);
    }
}

__attribute__((constructor))
static void install_ais_log_rate_limit(void) {
    char opt_out[PROP_VALUE_MAX] = {0};
    if (__system_property_get(AIS_LOG_OPT_OUT_PROPERTY, opt_out) > 0 && opt_out[0] == '1') {
        return;
    }
    // Load liblog now so the vendor AIS client binds to this same instance and its hook.
    void* liblog = dlopen("liblog.so", RTLD_NOW);
    if (!liblog) return;
    guard_set_logger_fn set_logger =
            (guard_set_logger_fn)dlsym(liblog, "__android_log_set_logger");
    logd_logger = (guard_logger_fn)dlsym(liblog, "__android_log_logd_logger");
    if (set_logger && logd_logger) set_logger(ais_rate_limited_logger);
}

static atomic_int orderly_stop_requested = ATOMIC_VAR_INIT(0);
static _Atomic int64_t orderly_stop_deadline_ms = ATOMIC_VAR_INIT(0);
static atomic_int stop_requested_by_signal = ATOMIC_VAR_INIT(0);
static _Atomic(term_handler_fn) producer_term_handler;

static const char* stop_origin(void) {
    if (!atomic_load(&orderly_stop_requested)) return "none";
    return atomic_load(&stop_requested_by_signal) ? "signal" : "guard";
}

/*
 * Record the initiating failure before the producer stops (or fail_closed() erases it).
 * One preformatted line, written best-effort to (a) stderr — merged into
 * stdout by the daemon's redirectErrorStream(true), so the [FastCamProc]
 * drainer logs it — and (b) the reason file, which survives both a detached
 * drainer (daemon shutdown) and daemon-log truncation. No allocation; a
 * fixed stack buffer and raw write()s only, since the process is mid-failure.
 * `frame` is optional (handshake failures have none); `detail` carries the
 * step-specific count (bytes sent/received).
 */
static void record_failure(
        const char* step,
        int saved_errno,
        const fast_cam_frame_msg_t* frame,
        long detail) {
    char line[256];
    int length;
    if (frame) {
        length = snprintf(
                line, sizeof(line),
                "RELEASE_GUARD_FAIL: step=%s errno=%d detail=%ld"
                " cam=%u buf=%u seq=%u uptime_ms=%lld"
                " timeout_ms=%d stop=%s\n",
                step, saved_errno, detail,
                (unsigned)frame->cam_id,
                (unsigned)frame->buf_index,
                (unsigned)frame->sequence_no,
                (long long)monotonic_millis(),
                FAST_CAM_RELEASE_TIMEOUT_MS,
                stop_origin());
    } else {
        length = snprintf(
                line, sizeof(line),
                "RELEASE_GUARD_FAIL: step=%s errno=%d detail=%ld"
                " uptime_ms=%lld timeout_ms=%d stop=%s\n",
                step, saved_errno, detail,
                (long long)monotonic_millis(),
                FAST_CAM_RELEASE_TIMEOUT_MS,
                stop_origin());
    }
    if (length <= 0) return;
    if (length > (int)sizeof(line) - 1) length = (int)sizeof(line) - 1;

    ssize_t ignored = write(STDERR_FILENO, line, (size_t)length);
    (void)ignored;
    // Whoever sent the SIGTERM owns the stop; a reason file would outlive it.
    if (atomic_load(&stop_requested_by_signal)) return;

    int file_fd = open(
            reason_file_path(),
            O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC,
            0600);
    if (file_fd >= 0) {
        ignored = write(file_fd, line, (size_t)length);
        (void)ignored;
        close(file_fd);
    }
}

__attribute__((noreturn))
static void fail_closed(int socket_fd) {
    shutdown(socket_fd, SHUT_RDWR);
    // Only without fast_cam_capture's own SIGTERM handler: returning would release an
    // unconfirmed frame, and there is no orderly stop to request.
    _exit(125);
}

/* The handler must be fast_cam_capture's own (it only clears the loop flag), not a library's. */
static int handler_in_main_executable(void* handler) {
    void* program_headers = (void*)getauxval(AT_PHDR);
    Dl_info handler_info;
    Dl_info main_info;
    return program_headers
            && dladdr(handler, &handler_info) != 0
            && dladdr(program_headers, &main_info) != 0
            && handler_info.dli_fbase == main_info.dli_fbase;
}

/*
 * Every call is an outside SIGTERM (the guard calls the producer's handler directly), so it caps
 * the guard's waits, including a stop grace the guard already started.
 */
static void guard_term_handler(int sig) {
    int saved_errno = errno;
    int64_t now = monotonic_millis();
    int64_t cap = now >= 0 ? now + FAST_CAM_RELEASE_SIGNAL_STOP_GRACE_MS : 0;
    int64_t current = atomic_load(&orderly_stop_deadline_ms);
    if (!atomic_load(&orderly_stop_requested)
            || (cap > 0 && (current <= 0 || cap < current))) {
        atomic_store(&orderly_stop_deadline_ms, cap);
    }
    atomic_store(&stop_requested_by_signal, 1);
    atomic_store(&orderly_stop_requested, 1);
    term_handler_fn handler = atomic_load(&producer_term_handler);
    if (handler) handler(sig);
    errno = saved_errno;
}

/* fast_cam_capture installs its SIGTERM handler with signal(); the guard runs in front of it. */
__attribute__((visibility("default")))
term_handler_fn signal(int sig, term_handler_fn handler) {
    pthread_once(&symbol_once, resolve_symbols_once);
    if (!real_signal) {
        errno = ENOSYS;
        return SIG_ERR;
    }
    if (sig != SIGTERM || handler == SIG_DFL || handler == SIG_IGN || handler == SIG_ERR
            || handler == guard_term_handler
            || !handler_in_main_executable((void*)handler)) {
        return real_signal(sig, handler);
    }
    term_handler_fn previous = atomic_exchange(&producer_term_handler, handler);
    term_handler_fn installed = real_signal(SIGTERM, guard_term_handler);
    if (installed == SIG_ERR) {
        atomic_store(&producer_term_handler, previous);
        return SIG_ERR;
    }
    return installed == guard_term_handler && previous ? previous : installed;
}

/*
 * Leave the frame loop through fast_cam_capture's own SIGTERM handler: it only clears the loop
 * flag, and the code after the loop stops and closes every camera before freeing the buffers.
 * 0 when that handler is not installed (or, when the guard could not wrap it, SIGTERM is blocked).
 */
static int request_orderly_stop(void) {
    if (atomic_load(&orderly_stop_requested)) return 1;
    struct sigaction current;
    if (sigaction(SIGTERM, NULL, &current) != 0) return 0;
    int wrapped = current.sa_handler == guard_term_handler;
    term_handler_fn handler = wrapped ? atomic_load(&producer_term_handler) : current.sa_handler;
    if (!handler || handler == SIG_DFL || handler == SIG_IGN
            || !handler_in_main_executable((void*)handler)) {
        return 0;
    }
    sigset_t blocked;
    if (!wrapped && (pthread_sigmask(SIG_BLOCK, NULL, &blocked) != 0
            || sigismember(&blocked, SIGTERM) == 1)) {
        return 0;
    }
    int64_t now = monotonic_millis();
    int64_t unset = 0;
    // An outside SIGTERM may have set a nearer deadline since the check above; never extend it.
    atomic_compare_exchange_strong(&orderly_stop_deadline_ms, &unset,
            now >= 0 ? now + FAST_CAM_RELEASE_STOP_GRACE_MS : 0);
    atomic_store(&orderly_stop_requested, 1);
    if (wrapped) {
        handler(SIGTERM);
    } else {
        raise(SIGTERM);
    }
    return 1;
}

/* Once a stop is requested, no wait outlives its deadline. */
static int64_t stop_capped(int64_t deadline) {
    if (!atomic_load(&orderly_stop_requested)) return deadline;
    int64_t stop = atomic_load(&orderly_stop_deadline_ms);
    return stop > 0 && stop < deadline ? stop : deadline;
}

/* stderr only (not the reason file): the producer stops through its normal teardown. */
static void note_stop(
        const char* why,
        const char* step,
        int saved_errno,
        const fast_cam_frame_msg_t* frame) {
    char line[192];
    int length = snprintf(
            line, sizeof(line),
            "RELEASE_GUARD_STOP: %s at step=%s errno=%d cam=%d buf=%d"
            " uptime_ms=%lld; stopping cameras\n",
            why, step, saved_errno,
            frame ? (int)frame->cam_id : -1,
            frame ? (int)frame->buf_index : -1,
            (long long)monotonic_millis());
    if (length > 0) {
        if (length > (int)sizeof(line) - 1) length = (int)sizeof(line) - 1;
        ssize_t ignored = write(STDERR_FILENO, line, (size_t)length);
        (void)ignored;
    }
}

/* The consumer closed its end, so no reader is left. */
static int stop_after_consumer_left(
        const char* step,
        int saved_errno,
        const fast_cam_frame_msg_t* frame) {
    if (!request_orderly_stop()) return 0;
    note_stop("consumer left", step, saved_errno, frame);
    return 1;
}

/*
 * Hand the client back as dead: fast_cam_capture closes it, releases the frame and, with the
 * stop requested, stops its cameras. A consumer still holding the frame can at worst copy a torn
 * image; exiting with the cameras running is what must not happen.
 */
static ssize_t drop_consumer(int socket_fd) {
    shutdown(socket_fd, SHUT_RDWR);
    errno = ECONNRESET;
    return -1;
}

/* After a recorded guard failure: stop through the producer's handler, else exit. */
static ssize_t stop_after_failure(
        int socket_fd,
        const char* step,
        int saved_errno,
        const fast_cam_frame_msg_t* frame) {
    int already_stopping = atomic_load(&orderly_stop_requested);
    if (!request_orderly_stop()) fail_closed(socket_fd);
    if (!already_stopping) note_stop("guard failure", step, saved_errno, frame);
    return drop_consumer(socket_fd);
}

/*
 * poll() one descriptor until the (stop-capped) deadline, in short slices so a stop requested
 * from another thread's signal handler also ends the wait. 0 at the deadline.
 */
static int poll_until(struct pollfd* descriptor, int64_t deadline) {
    for (;;) {
        int timeout = remaining_millis(stop_capped(deadline));
        if (timeout <= 0) {
            int last = poll(descriptor, 1, 0);
            return last > 0 ? last : 0;
        }
        if (timeout > FAST_CAM_RELEASE_POLL_SLICE_MS) timeout = FAST_CAM_RELEASE_POLL_SLICE_MS;
        int ready = poll(descriptor, 1, timeout);
        if (ready > 0 || (ready < 0 && errno != EINTR)) return ready;
    }
}

/* Closes every descriptor a (possibly truncated) message carried. */
static void close_received_descriptors(struct msghdr* message) {
    for (struct cmsghdr* header = CMSG_FIRSTHDR(message);
            header;
            header = CMSG_NXTHDR(message, header)) {
        if (header->cmsg_level != SOL_SOCKET
                || header->cmsg_type != SCM_RIGHTS
                || header->cmsg_len < CMSG_LEN(0)) {
            continue;
        }
        int count = (int)((header->cmsg_len - CMSG_LEN(0)) / sizeof(int));
        int* descriptors = (int*)CMSG_DATA(header);
        for (int i = 0; i < count; i++) close(descriptors[i]);
    }
}

/* 1 signalled, 0 still pending at the deadline, -1 error. Leaves the fence open. */
static int poll_fence(int fence_fd, int64_t deadline) {
    struct pollfd descriptor = {
        .fd = fence_fd,
        .events = POLLIN,
        .revents = 0,
    };
    int result = poll_until(&descriptor, deadline);
    if (result == 0) return 0;
    return result > 0 && (descriptor.revents & POLLIN) ? 1 : -1;
}

static int wait_for_fence(int fence_fd, const fast_cam_frame_msg_t* frame) {
    if (fence_fd < 0) return 0;
    int signalled = poll_fence(
            fence_fd, monotonic_millis() + FAST_CAM_RELEASE_TIMEOUT_MS);
    if (signalled == 0
            && !atomic_load(&orderly_stop_requested)
            && request_orderly_stop()) {
        note_stop("release fence pending", "release-fence", 0, frame);
        signalled = poll_fence(fence_fd, atomic_load(&orderly_stop_deadline_ms));
    }
    close(fence_fd);
    return signalled == 1 ? 0 : -1;
}

static int receive_release(
        int socket_fd,
        const fast_cam_frame_msg_t* frame) {
    fast_cam_frame_release_msg_t release = {0};
    size_t total = 0;
    int fence_fd = -1;
    int fence_count = 0;
    int saved_errno = 0;
    int consumer_gone = 0;
    int stop_grace_started = 0;
    int64_t deadline =
            monotonic_millis() + FAST_CAM_RELEASE_TIMEOUT_MS;
    while (total < sizeof(release)) {
        struct pollfd descriptor = {
            .fd = socket_fd,
            .events = POLLIN,
            .revents = 0,
        };
        int ready = poll_until(&descriptor, deadline);
        if (ready == 0 && !stop_grace_started
                && !atomic_load(&orderly_stop_requested)
                && request_orderly_stop()) {
            // A live but silent consumer: stop after this frame and keep waiting for it.
            stop_grace_started = 1;
            note_stop("release pending", "release-recv", 0, frame);
            deadline = atomic_load(&orderly_stop_deadline_ms);
            continue;
        }
        if (ready > 0
                && !(descriptor.revents & POLLIN)
                && (descriptor.revents & POLLHUP)) {
            consumer_gone = 1;
            break;
        }
        if (ready <= 0 || !(descriptor.revents & POLLIN)) {
            saved_errno = ready < 0 ? errno : 0;   // 0 = deadline expired
            break;
        }

        struct iovec iov = {
            .iov_base = (uint8_t*)&release + total,
            .iov_len = sizeof(release) - total,
        };
        char control[CMSG_SPACE(sizeof(int))] = {0};
        struct msghdr message = {0};
        message.msg_iov = &iov;
        message.msg_iovlen = 1;
        message.msg_control = control;
        message.msg_controllen = sizeof(control);
        ssize_t received = recvmsg(
                socket_fd,
                &message,
                MSG_DONTWAIT | MSG_CMSG_CLOEXEC);
        if (received < 0 && (errno == EINTR
                || errno == EAGAIN
                || errno == EWOULDBLOCK)) {
            continue;
        }
        if (received == 0
                || (received < 0
                        && (errno == ECONNRESET || errno == EPIPE))) {
            saved_errno = received < 0 ? errno : 0;
            consumer_gone = 1;
            break;
        }
        if (received < 0
                || (message.msg_flags & (MSG_TRUNC | MSG_CTRUNC))) {
            saved_errno = received < 0 ? errno : 0;
            if (received >= 0) close_received_descriptors(&message);
            break;
        }
        int ancillary_valid = 1;
        for (struct cmsghdr* header = CMSG_FIRSTHDR(&message);
                header;
                header = CMSG_NXTHDR(&message, header)) {
            if (header->cmsg_level == SOL_SOCKET
                    && header->cmsg_type == SCM_RIGHTS) {
                if (header->cmsg_len < CMSG_LEN(sizeof(int))
                        || (header->cmsg_len - CMSG_LEN(0))
                                % sizeof(int) != 0) {
                    ancillary_valid = 0;
                    break;
                }
                int count = (int)(
                        (header->cmsg_len - CMSG_LEN(0)) / sizeof(int));
                int* descriptors = (int*)CMSG_DATA(header);
                for (int i = 0; i < count; i++) {
                    if (fence_count++ == 0) {
                        fence_fd = descriptors[i];
                    } else {
                        close(descriptors[i]);
                    }
                }
            }
        }
        if (!ancillary_valid) break;
        total += (size_t)received;
    }
    if (consumer_gone) {
        if (fence_fd >= 0) close(fence_fd);
        if (stop_after_consumer_left("release-recv", saved_errno, frame)) {
            return RELEASE_CONSUMER_GONE;
        }
        record_failure("release-recv", saved_errno, frame, (long)total);
        return RELEASE_FAILED;
    }
    if (total != sizeof(release)
            || release.magic != FAST_CAM_MAGIC
            || release.msg_type != FAST_CAM_MSG_FRAME_RELEASE
            || release.cam_id != frame->cam_id
            || release.buf_index != frame->buf_index
            || release.sequence_no != frame->sequence_no
            || fence_count > 1) {
        if (fence_fd >= 0) close(fence_fd);
        // Truncated (timeout or transport error: detail = bytes received)
        // or a release that does not match the in-flight frame.
        record_failure(
                total != sizeof(release)
                        ? "release-recv" : "release-mismatch",
                saved_errno, frame, (long)total);
        return RELEASE_FAILED;
    }
    if (wait_for_fence(fence_fd, frame) != 0) {
        // The consumer's release fence never signalled inside the timeout:
        // the importing side may still be reading the DMA-BUF.
        record_failure("release-fence", 0, frame, (long)total);
        return RELEASE_FAILED;
    }
    return RELEASE_CONFIRMED;
}

__attribute__((visibility("default")))
ssize_t sendmsg(
        int socket_fd,
        const struct msghdr* message,
        int flags) {
    if (!resolve_symbols()) {
        errno = ENOSYS;
        return -1;
    }
    if (!message
            || message->msg_iovlen != 1
            || message->msg_iov[0].iov_len
                    != sizeof(fast_cam_handshake_multi_resp_t)) {
        return real_sendmsg(socket_fd, message, flags);
    }

    const fast_cam_handshake_multi_resp_t* original =
            (const fast_cam_handshake_multi_resp_t*)
                    message->msg_iov[0].iov_base;
    if (!original
            || original->magic != FAST_CAM_MAGIC
            || original->msg_type != FAST_CAM_MSG_HANDSHAKE_RESP) {
        return real_sendmsg(socket_fd, message, flags);
    }

    // Stopping: a new consumer gets no buffers.
    if (atomic_load(&orderly_stop_requested)) return drop_consumer(socket_fd);
    fast_cam_handshake_multi_resp_t guarded = *original;
    guarded.msg_type |= FAST_CAM_CAP_FRAME_RELEASE_FENCE;
    struct iovec guarded_iov = {
        .iov_base = &guarded,
        .iov_len = sizeof(guarded),
    };
    struct msghdr guarded_message = *message;
    guarded_message.msg_iov = &guarded_iov;
    guarded_message.msg_iovlen = 1;
    ssize_t sent = real_sendmsg(socket_fd, &guarded_message, flags);
    if (sent < 0 && (errno == EPIPE || errno == ECONNRESET)) {
        int saved_errno = errno;
        int stopping = stop_after_consumer_left(
                "handshake-send", saved_errno, NULL);
        errno = saved_errno;
        // The failed handshake makes fast_cam_capture drop this client itself.
        if (stopping) return sent;
    }
    if (sent != (ssize_t)sizeof(guarded)) {
        int saved_errno = errno;
        record_failure("handshake-send", saved_errno, NULL, (long)sent);
        return stop_after_failure(socket_fd, "handshake-send", saved_errno, NULL);
    }
    atomic_store_explicit(
            &camera_socket, socket_fd, memory_order_release);
    return sent;
}

__attribute__((visibility("default")))
ssize_t send(int socket_fd, const void* data, size_t length, int flags) {
    if (!resolve_symbols()) {
        errno = ENOSYS;
        return -1;
    }
    fast_cam_frame_msg_t frame = {0};
    int guarded_frame =
            data
            && length == sizeof(frame)
            && socket_fd == atomic_load_explicit(
                    &camera_socket, memory_order_acquire);
    if (guarded_frame) {
        memcpy(&frame, data, sizeof(frame));
        guarded_frame =
                frame.magic == FAST_CAM_MAGIC
                && frame.msg_type == FAST_CAM_MSG_FRAME_READY;
    }
    // Already stopping: the consumer gets no further frames.
    if (guarded_frame && atomic_load(&orderly_stop_requested)) {
        return drop_consumer(socket_fd);
    }
    ssize_t sent = real_send(socket_fd, data, length, flags);
    if (!guarded_frame) return sent;
    if (sent < 0 && (errno == EPIPE || errno == ECONNRESET)) {
        int saved_errno = errno;
        int stopping = stop_after_consumer_left(
                "frame-send", saved_errno, &frame);
        errno = saved_errno;
        // fast_cam_capture drops the client on EPIPE/ECONNRESET, releases the frame and stops.
        if (stopping) return sent;
    }
    if (sent != (ssize_t)sizeof(frame)) {
        int saved_errno = errno;
        record_failure("frame-send", saved_errno, &frame, (long)sent);
        return stop_after_failure(socket_fd, "frame-send", saved_errno, &frame);
    }
    int outcome = receive_release(socket_fd, &frame);
    if (outcome == RELEASE_CONSUMER_GONE) {
        // Same as a failed send: the frame has no reader left.
        errno = ECONNRESET;
        return -1;
    }
    if (outcome != RELEASE_CONFIRMED) {
        // receive_release already recorded the specific failure.
        return stop_after_failure(socket_fd, "release", 0, &frame);
    }
    return sent;
}
