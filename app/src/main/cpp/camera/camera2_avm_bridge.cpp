// Camera2 360 bridge: streams the AVM super-frame camera into a caller-owned
// Surface through the NDK camera API. Loaded only in the camera2 mode.

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <camera/NdkCameraCaptureSession.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <camera/NdkCameraMetadataTags.h>
#include <camera/NdkCaptureRequest.h>
#include <media/NdkImage.h>
#include <stdint.h>
#include <stdio.h>
#include <time.h>

#include <atomic>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#define TAG "Camera2AvmBridge"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

// BYD vendor tags on the AVM camera (com.byd.camera.staticInfo).
constexpr uint32_t kTagPositionName = 0x80000001u;
constexpr uint32_t kTagSuperFrameOrder = 0x80000003u;

constexpr int32_t kSuperFrameWidth = 5120;
constexpr int32_t kSuperFrameHeight = 960;
constexpr int kFlagPrivateSuperFrame = 1;
constexpr int kFlagSystemCamera = 2;
constexpr int kProbeMaxCameraId = 7;
constexpr size_t kMaxVendorStringLength = 64;

constexpr int32_t kDeviceOk = 0;
constexpr int32_t kDeviceDisconnected = 1;
constexpr int32_t kDeviceError = 2;

// Per thread: Java reads the reason right after the failing call on the same
// thread, so a concurrent describe can never overwrite it.
thread_local std::string tLastError;

void setError(const std::string& message) {
    tLastError = message;
}

const char* statusName(camera_status_t status) {
    switch (status) {
        case ACAMERA_OK: return "OK";
        case ACAMERA_ERROR_UNKNOWN: return "UNKNOWN";
        case ACAMERA_ERROR_INVALID_PARAMETER: return "INVALID_PARAMETER";
        case ACAMERA_ERROR_CAMERA_DISCONNECTED: return "CAMERA_DISCONNECTED";
        case ACAMERA_ERROR_NOT_ENOUGH_MEMORY: return "NOT_ENOUGH_MEMORY";
        case ACAMERA_ERROR_METADATA_NOT_FOUND: return "METADATA_NOT_FOUND";
        case ACAMERA_ERROR_CAMERA_DEVICE: return "CAMERA_DEVICE";
        case ACAMERA_ERROR_CAMERA_SERVICE: return "CAMERA_SERVICE";
        case ACAMERA_ERROR_SESSION_CLOSED: return "SESSION_CLOSED";
        case ACAMERA_ERROR_INVALID_OPERATION: return "INVALID_OPERATION";
        case ACAMERA_ERROR_STREAM_CONFIGURE_FAIL: return "STREAM_CONFIGURE_FAIL";
        case ACAMERA_ERROR_CAMERA_IN_USE: return "CAMERA_IN_USE";
        case ACAMERA_ERROR_MAX_CAMERA_IN_USE: return "MAX_CAMERA_IN_USE";
        case ACAMERA_ERROR_CAMERA_DISABLED: return "CAMERA_DISABLED";
        case ACAMERA_ERROR_PERMISSION_DENIED: return "PERMISSION_DENIED";
        default: return "ERROR";
    }
}

std::string describeStatus(const char* step, camera_status_t status) {
    char buffer[160];
    snprintf(buffer, sizeof(buffer), "%s failed: %s (%d)", step, statusName(status),
             static_cast<int>(status));
    return buffer;
}

// Device callbacks carry only a token, never a pointer, so a late callback
// after close cannot reach freed memory.
constexpr int kStateSlots = 8;

struct DeviceStateSlot {
    std::atomic<uint64_t> token{0};
    std::atomic<int32_t> state{kDeviceOk};
    // Every callback, not just the first one that `state` keeps.
    std::atomic<bool> disconnected{false};
    std::atomic<int64_t> lastErrorAtMs{0};
};

DeviceStateSlot gStateSlots[kStateSlots];
std::atomic<uint64_t> gNextToken{0};

DeviceStateSlot& slotFor(uint64_t token) {
    return gStateSlots[token % kStateSlots];
}

void* tokenContext(uint64_t token) {
    return reinterpret_cast<void*>(static_cast<uintptr_t>(token));
}

// Same clock as SystemClock.elapsedRealtime().
int64_t bootTimeMs() {
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

void recordDeviceState(void* context, int32_t state) {
    uint64_t token = static_cast<uint64_t>(reinterpret_cast<uintptr_t>(context));
    DeviceStateSlot& slot = slotFor(token);
    if (token == 0 || slot.token.load() != token) return;
    if ((state & 0xff) == kDeviceDisconnected) {
        slot.disconnected.store(true);
    } else {
        slot.lastErrorAtMs.store(bootTimeMs());
    }
    int32_t expected = kDeviceOk;
    slot.state.compare_exchange_strong(expected, state);
}

void onDeviceDisconnected(void* context, ACameraDevice*) {
    recordDeviceState(context, kDeviceDisconnected);
}

void onDeviceError(void* context, ACameraDevice*, int error) {
    recordDeviceState(context, kDeviceError | ((error & 0xffffff) << 8));
}

void onSessionState(void*, ACameraCaptureSession*) {}

struct AvmSession {
    uint64_t token = 0;
    ACameraManager* manager = nullptr;
    ACameraDevice* device = nullptr;
    ANativeWindow* window = nullptr;
    ACaptureSessionOutputContainer* outputs = nullptr;
    ACaptureSessionOutput* output = nullptr;
    ACameraOutputTarget* target = nullptr;
    ACaptureRequest* request = nullptr;
    ACameraCaptureSession* session = nullptr;
    bool outputAdded = false;
    bool targetAdded = false;
    ACameraDevice_StateCallbacks deviceCallbacks{};
    ACameraCaptureSession_stateCallbacks sessionCallbacks{};
    std::mutex lock;
};

AvmSession* fromHandle(jlong handle) {
    return reinterpret_cast<AvmSession*>(static_cast<uintptr_t>(handle));
}

// Stream, session and device go first so the HAL stops writing into the
// window before any of its wrappers are freed.
bool releaseSessionLocked(AvmSession* s) {
    bool clean = true;
    if (s->session != nullptr) {
        ACameraCaptureSession_stopRepeating(s->session);
        ACameraCaptureSession_close(s->session);
        s->session = nullptr;
    }
    if (s->device != nullptr) {
        camera_status_t status = ACameraDevice_close(s->device);
        if (status != ACAMERA_OK) {
            setError(describeStatus("ACameraDevice_close", status));
            clean = false;
        }
        s->device = nullptr;
    }
    if (s->request != nullptr) {
        if (s->targetAdded && s->target != nullptr) {
            ACaptureRequest_removeTarget(s->request, s->target);
        }
        ACaptureRequest_free(s->request);
        s->request = nullptr;
    }
    s->targetAdded = false;
    if (s->target != nullptr) {
        ACameraOutputTarget_free(s->target);
        s->target = nullptr;
    }
    if (s->outputs != nullptr) {
        if (s->outputAdded && s->output != nullptr) {
            ACaptureSessionOutputContainer_remove(s->outputs, s->output);
        }
        ACaptureSessionOutputContainer_free(s->outputs);
        s->outputs = nullptr;
    }
    s->outputAdded = false;
    if (s->output != nullptr) {
        ACaptureSessionOutput_free(s->output);
        s->output = nullptr;
    }
    if (s->window != nullptr) {
        ANativeWindow_release(s->window);
        s->window = nullptr;
    }
    if (s->manager != nullptr) {
        ACameraManager_delete(s->manager);
        s->manager = nullptr;
    }
    DeviceStateSlot& slot = slotFor(s->token);
    uint64_t expected = s->token;
    slot.token.compare_exchange_strong(expected, 0);
    return clean;
}

std::string sanitize(const std::string& value) {
    std::string out;
    for (char c : value) {
        unsigned char u = static_cast<unsigned char>(c);
        out.push_back((u < 0x20 || u > 0x7e || c == '|') ? '_' : c);
    }
    return out;
}

std::string readVendorString(const ACameraMetadata* chars, uint32_t tag) {
    ACameraMetadata_const_entry entry{};
    if (ACameraMetadata_getConstEntry(chars, tag, &entry) != ACAMERA_OK
            || entry.type != ACAMERA_TYPE_BYTE || entry.count == 0) {
        return std::string();
    }
    std::string value;
    for (uint32_t i = 0; i < entry.count && value.size() < kMaxVendorStringLength; i++) {
        uint8_t b = entry.data.u8[i];
        if (b == 0) break;
        value.push_back(static_cast<char>(b));
    }
    return sanitize(value);
}

int readFlags(const ACameraMetadata* chars) {
    int flags = 0;
    ACameraMetadata_const_entry entry{};
    if (ACameraMetadata_getConstEntry(chars, ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS,
                                      &entry) == ACAMERA_OK
            && entry.type == ACAMERA_TYPE_INT32) {
        for (uint32_t i = 0; i + 3 < entry.count; i += 4) {
            if (entry.data.i32[i] == AIMAGE_FORMAT_PRIVATE
                    && entry.data.i32[i + 1] == kSuperFrameWidth
                    && entry.data.i32[i + 2] == kSuperFrameHeight
                    && entry.data.i32[i + 3]
                            == ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS_OUTPUT) {
                flags |= kFlagPrivateSuperFrame;
                break;
            }
        }
    }
    if (ACameraMetadata_getConstEntry(chars, ACAMERA_REQUEST_AVAILABLE_CAPABILITIES,
                                      &entry) == ACAMERA_OK
            && entry.type == ACAMERA_TYPE_BYTE) {
        for (uint32_t i = 0; i < entry.count; i++) {
            if (entry.data.u8[i] == ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA) {
                flags |= kFlagSystemCamera;
                break;
            }
        }
    }
    return flags;
}

std::string readFpsRanges(const ACameraMetadata* chars) {
    std::string out;
    ACameraMetadata_const_entry entry{};
    if (ACameraMetadata_getConstEntry(chars, ACAMERA_CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
                                      &entry) != ACAMERA_OK
            || entry.type != ACAMERA_TYPE_INT32) {
        return out;
    }
    for (uint32_t i = 0; i + 1 < entry.count; i += 2) {
        if (!out.empty()) out.push_back(',');
        out += std::to_string(entry.data.i32[i]);
        out.push_back('-');
        out += std::to_string(entry.data.i32[i + 1]);
    }
    return out;
}

// One "id|position|superFrameOrder|flags|min-max,..." line, or false.
bool describeCamera(ACameraManager* manager, const char* id, std::string* out, int* flagsOut) {
    ACameraMetadata* chars = nullptr;
    if (ACameraManager_getCameraCharacteristics(manager, id, &chars) != ACAMERA_OK
            || chars == nullptr) {
        return false;
    }
    int flags = readFlags(chars);
    *out += sanitize(id);
    out->push_back('|');
    *out += readVendorString(chars, kTagPositionName);
    out->push_back('|');
    *out += readVendorString(chars, kTagSuperFrameOrder);
    out->push_back('|');
    *out += std::to_string(flags);
    out->push_back('|');
    *out += readFpsRanges(chars);
    out->push_back('\n');
    ACameraMetadata_free(chars);
    if (flagsOut != nullptr) *flagsOut = flags;
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_overdrive_app_camera_camera2_Camera2AvmBackend_nativeDescribeCameras(
        JNIEnv* env, jclass) {
    setError("");
    std::string out;
    ACameraManager* manager = ACameraManager_create();
    if (manager == nullptr) {
        setError("ACameraManager_create failed");
        return env->NewStringUTF("");
    }
    std::vector<std::string> listed;
    bool superFrameListed = false;
    ACameraIdList* ids = nullptr;
    camera_status_t status = ACameraManager_getCameraIdList(manager, &ids);
    if (status == ACAMERA_OK && ids != nullptr) {
        for (int i = 0; i < ids->numCameras; i++) {
            const char* id = ids->cameraIds[i];
            if (id == nullptr) continue;
            listed.emplace_back(id);
            int flags = 0;
            if (describeCamera(manager, id, &out, &flags)
                    && (flags & kFlagPrivateSuperFrame) != 0) {
                superFrameListed = true;
            }
        }
        ACameraManager_deleteCameraIdList(ids);
    } else {
        setError(describeStatus("ACameraManager_getCameraIdList", status));
    }
    // System-only cameras can be hidden from the id list but still open by id.
    if (!superFrameListed) {
        for (int i = 0; i <= kProbeMaxCameraId; i++) {
            std::string id = std::to_string(i);
            bool seen = false;
            for (const std::string& known : listed) {
                if (known == id) {
                    seen = true;
                    break;
                }
            }
            if (!seen) describeCamera(manager, id.c_str(), &out, nullptr);
        }
    }
    ACameraManager_delete(manager);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_overdrive_app_camera_camera2_Camera2AvmBackend_nativeOpen(
        JNIEnv* env, jclass, jstring cameraId, jobject surface, jint fpsMin, jint fpsMax) {
    if (cameraId == nullptr || surface == nullptr) {
        setError("camera id or surface missing");
        return 0;
    }
    const char* idChars = env->GetStringUTFChars(cameraId, nullptr);
    if (idChars == nullptr) {
        setError("camera id unavailable");
        return 0;
    }
    std::string id(idChars);
    env->ReleaseStringUTFChars(cameraId, idChars);

    // Not yet visible to any other thread, so no lock is needed until return.
    AvmSession* s = new (std::nothrow) AvmSession();
    if (s == nullptr) {
        setError("out of memory");
        return 0;
    }
    s->token = gNextToken.fetch_add(1) + 1;
    DeviceStateSlot& slot = slotFor(s->token);
    slot.state.store(kDeviceOk);
    slot.disconnected.store(false);
    slot.lastErrorAtMs.store(0);
    slot.token.store(s->token);

    std::string failure;
    camera_status_t status = ACAMERA_OK;
    int sequenceId = 0;
    s->window = ANativeWindow_fromSurface(env, surface);
    if (s->window == nullptr) {
        failure = "surface has no native window";
        goto fail;
    }
    s->manager = ACameraManager_create();
    if (s->manager == nullptr) {
        failure = "ACameraManager_create failed";
        goto fail;
    }
    s->deviceCallbacks.context = tokenContext(s->token);
    s->deviceCallbacks.onDisconnected = onDeviceDisconnected;
    s->deviceCallbacks.onError = onDeviceError;
    status = ACameraManager_openCamera(s->manager, id.c_str(), &s->deviceCallbacks, &s->device);
    if (status != ACAMERA_OK || s->device == nullptr) {
        failure = describeStatus(("openCamera(" + sanitize(id) + ")").c_str(), status);
        goto fail;
    }
    status = ACaptureSessionOutputContainer_create(&s->outputs);
    if (status != ACAMERA_OK) {
        failure = describeStatus("ACaptureSessionOutputContainer_create", status);
        goto fail;
    }
    status = ACaptureSessionOutput_create(s->window, &s->output);
    if (status != ACAMERA_OK) {
        failure = describeStatus("ACaptureSessionOutput_create", status);
        goto fail;
    }
    status = ACaptureSessionOutputContainer_add(s->outputs, s->output);
    if (status != ACAMERA_OK) {
        failure = describeStatus("ACaptureSessionOutputContainer_add", status);
        goto fail;
    }
    s->outputAdded = true;
    status = ACameraOutputTarget_create(s->window, &s->target);
    if (status != ACAMERA_OK) {
        failure = describeStatus("ACameraOutputTarget_create", status);
        goto fail;
    }
    status = ACameraDevice_createCaptureRequest(s->device, TEMPLATE_PREVIEW, &s->request);
    if (status != ACAMERA_OK) {
        failure = describeStatus("createCaptureRequest(PREVIEW)", status);
        goto fail;
    }
    status = ACaptureRequest_addTarget(s->request, s->target);
    if (status != ACAMERA_OK) {
        failure = describeStatus("ACaptureRequest_addTarget", status);
        goto fail;
    }
    s->targetAdded = true;
    if (fpsMin > 0 && fpsMax >= fpsMin) {
        int32_t range[2] = {fpsMin, fpsMax};
        status = ACaptureRequest_setEntry_i32(s->request, ACAMERA_CONTROL_AE_TARGET_FPS_RANGE,
                                              2, range);
        if (status != ACAMERA_OK) {
            LOGW("AE fps range [%d,%d] not applied: %s", fpsMin, fpsMax, statusName(status));
        }
    }
    s->sessionCallbacks.context = tokenContext(s->token);
    s->sessionCallbacks.onClosed = onSessionState;
    s->sessionCallbacks.onReady = onSessionState;
    s->sessionCallbacks.onActive = onSessionState;
    status = ACameraDevice_createCaptureSession(s->device, s->outputs, &s->sessionCallbacks,
                                                &s->session);
    if (status != ACAMERA_OK || s->session == nullptr) {
        failure = describeStatus("createCaptureSession", status);
        goto fail;
    }
    status = ACameraCaptureSession_setRepeatingRequest(s->session, nullptr, 1, &s->request,
                                                       &sequenceId);
    if (status != ACAMERA_OK) {
        failure = describeStatus("setRepeatingRequest", status);
        goto fail;
    }
    setError("");
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(s));

fail:
    releaseSessionLocked(s);
    setError(failure);
    delete s;
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_camera2_Camera2AvmBackend_nativeSetFpsRange(
        JNIEnv*, jclass, jlong handle, jint fpsMin, jint fpsMax) {
    AvmSession* s = fromHandle(handle);
    if (s == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(s->lock);
    if (s->session == nullptr || s->request == nullptr) {
        setError("capture session is closed");
        return JNI_FALSE;
    }
    int32_t range[2] = {fpsMin, fpsMax};
    camera_status_t status = ACaptureRequest_setEntry_i32(
            s->request, ACAMERA_CONTROL_AE_TARGET_FPS_RANGE, 2, range);
    if (status != ACAMERA_OK) {
        setError(describeStatus("set AE fps range", status));
        return JNI_FALSE;
    }
    int sequenceId = 0;
    status = ACameraCaptureSession_setRepeatingRequest(s->session, nullptr, 1, &s->request,
                                                       &sequenceId);
    if (status != ACAMERA_OK) {
        setError(describeStatus("setRepeatingRequest", status));
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_overdrive_app_camera_camera2_Camera2AvmBackend_nativeSessionToken(
        JNIEnv*, jclass, jlong handle) {
    AvmSession* s = fromHandle(handle);
    return s == nullptr ? 0 : static_cast<jlong>(s->token);
}

// Reads only the static slots, never a session, so it is safe against a
// concurrent close. kind = state & 0xff (0 ok, 1 disconnected, 2 error);
// error code = state >> 8.
JNIEXPORT jint JNICALL
Java_com_overdrive_app_camera_camera2_Camera2AvmBackend_nativeDeviceState(
        JNIEnv*, jclass, jlong token) {
    uint64_t key = static_cast<uint64_t>(token);
    if (key == 0) return kDeviceDisconnected;
    DeviceStateSlot& slot = slotFor(key);
    int32_t state = slot.state.load();
    if (slot.token.load() != key) return kDeviceDisconnected;
    return state;
}

// Boot-time ms of the latest device error, 0 if none, -1 once the token is retired.
JNIEXPORT jlong JNICALL
Java_com_overdrive_app_camera_camera2_Camera2AvmBackend_nativeLastDeviceErrorAtMs(
        JNIEnv*, jclass, jlong token) {
    uint64_t key = static_cast<uint64_t>(token);
    if (key == 0) return -1;
    DeviceStateSlot& slot = slotFor(key);
    int64_t at = slot.lastErrorAtMs.load();
    if (slot.token.load() != key) return -1;
    return static_cast<jlong>(at);
}

// True after any disconnect callback, even one that followed an error.
JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_camera2_Camera2AvmBackend_nativeDeviceDisconnected(
        JNIEnv*, jclass, jlong token) {
    uint64_t key = static_cast<uint64_t>(token);
    if (key == 0) return JNI_TRUE;
    DeviceStateSlot& slot = slotFor(key);
    bool disconnected = slot.disconnected.load();
    if (slot.token.load() != key) return JNI_TRUE;
    return disconnected ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_camera2_Camera2AvmBackend_nativeClose(
        JNIEnv*, jclass, jlong handle) {
    AvmSession* s = fromHandle(handle);
    if (s == nullptr) return JNI_TRUE;
    bool clean;
    {
        std::lock_guard<std::mutex> guard(s->lock);
        clean = releaseSessionLocked(s);
    }
    delete s;
    return clean ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_overdrive_app_camera_camera2_Camera2AvmBackend_nativeLastError(
        JNIEnv* env, jclass) {
    return env->NewStringUTF(sanitize(tLastError).c_str());
}

}  // extern "C"
