// The voice assistant's cabin front-end: the car's two microphones averaged, the echo of both
// speaker channels cancelled against the speaker reference, low rumble high-passed, then
// SpeexDSP noise and residual-echo suppression. 16 kHz, 10 ms frames, mono out.
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "speex/speex_echo.h"
#include "speex/speex_preprocess.h"

typedef struct {
    double b0, b1, b2, a1, a2, x1, x2, y1, y2;
} Biquad;

typedef struct {
    int frame, mics, refs;
    SpeexEchoState *echo;
    SpeexPreprocessState *pre;
    Biquad hpf[2];
    int hpf_on;
    int16_t *mic, *ref, *clean;
} Frontend;

static void biquad_highpass(Biquad *q, double fc, double rate) {
    double w = 2 * M_PI * fc / rate, c = cos(w), s = sin(w), alpha = s / (2 * 0.7071), a0 = 1 + alpha;
    q->b0 = (1 + c) / 2 / a0;
    q->b1 = -(1 + c) / a0;
    q->b2 = (1 + c) / 2 / a0;
    q->a1 = -2 * c / a0;
    q->a2 = (1 - alpha) / a0;
    q->x1 = q->x2 = q->y1 = q->y2 = 0;
}

static double biquad_run(Biquad *q, double x) {
    double y = q->b0 * x + q->b1 * q->x1 + q->b2 * q->x2 - q->a1 * q->y1 - q->a2 * q->y2;
    q->x2 = q->x1;
    q->x1 = x;
    q->y2 = q->y1;
    q->y1 = y;
    return y;
}

static void frontend_destroy(Frontend *f) {
    if (!f) return;
    if (f->echo) speex_echo_state_destroy(f->echo);
    if (f->pre) speex_preprocess_state_destroy(f->pre);
    free(f->mic);
    free(f->ref);
    free(f->clean);
    free(f);
}

static Frontend *frontend_create(int rate, int frame, int filter_len, int mics, int refs,
                                 int suppress_db, int hpf_hz) {
    if (rate <= 0 || frame <= 0 || mics <= 0 || refs < 0) return NULL;
    Frontend *f = (Frontend *) calloc(1, sizeof(Frontend));
    if (!f) return NULL;
    f->frame = frame;
    f->mics = mics;
    f->refs = refs;
    f->mic = (int16_t *) calloc((size_t) frame, sizeof(int16_t));
    f->ref = (int16_t *) calloc((size_t) frame * (size_t) (refs > 0 ? refs : 1), sizeof(int16_t));
    f->clean = (int16_t *) calloc((size_t) frame, sizeof(int16_t));
    if (!f->mic || !f->ref || !f->clean) {
        frontend_destroy(f);
        return NULL;
    }
    if (refs > 0) {
        f->echo = speex_echo_state_init_mc(frame, filter_len, 1, refs);
        if (!f->echo) {
            frontend_destroy(f);
            return NULL;
        }
        speex_echo_ctl(f->echo, SPEEX_ECHO_SET_SAMPLING_RATE, &rate);
    }
    if (hpf_hz > 0) {
        // Fourth order: two cascaded second-order sections.
        biquad_highpass(&f->hpf[0], hpf_hz, rate);
        biquad_highpass(&f->hpf[1], hpf_hz, rate);
        f->hpf_on = 1;
    }
    f->pre = speex_preprocess_state_init(frame, rate);
    if (!f->pre) {
        frontend_destroy(f);
        return NULL;
    }
    int on = 1, off = 0;
    speex_preprocess_ctl(f->pre, SPEEX_PREPROCESS_SET_DENOISE, &on);
    speex_preprocess_ctl(f->pre, SPEEX_PREPROCESS_SET_NOISE_SUPPRESS, &suppress_db);
    speex_preprocess_ctl(f->pre, SPEEX_PREPROCESS_SET_AGC, &off);
    speex_preprocess_ctl(f->pre, SPEEX_PREPROCESS_SET_DEREVERB, &off);
    if (f->echo) speex_preprocess_ctl(f->pre, SPEEX_PREPROCESS_SET_ECHO_STATE, f->echo);
    return f;
}

/** One frame of interleaved PCM, the mics first and then the speaker references; one mono frame out. */
static void frontend_process(Frontend *f, const int16_t *in, int channels, int16_t *out) {
    for (int i = 0; i < f->frame; i++) {
        int32_t sum = 0;
        for (int m = 0; m < f->mics; m++) sum += in[i * channels + m];
        f->mic[i] = (int16_t) (sum / f->mics);
        for (int r = 0; r < f->refs; r++) f->ref[i * f->refs + r] = in[i * channels + f->mics + r];
    }
    if (f->echo) {
        speex_echo_cancellation(f->echo, f->mic, f->ref, f->clean);
    } else {
        memcpy(f->clean, f->mic, sizeof(int16_t) * (size_t) f->frame);
    }
    if (f->hpf_on) {
        for (int i = 0; i < f->frame; i++) {
            double y = biquad_run(&f->hpf[1], biquad_run(&f->hpf[0], f->clean[i]));
            f->clean[i] = (int16_t) (y > 32767 ? 32767 : y < -32768 ? -32768 : y);
        }
    }
    speex_preprocess_run(f->pre, f->clean);
    memcpy(out, f->clean, sizeof(int16_t) * (size_t) f->frame);
}

JNIEXPORT jlong JNICALL
Java_com_overdrive_app_audio_VoiceFrontend_nativeCreate(JNIEnv *env, jclass clazz, jint rate, jint frame,
                                                        jint filterLen, jint mics, jint refs,
                                                        jint suppressDb, jint hpfHz) {
    (void) env;
    (void) clazz;
    return (jlong) (intptr_t) frontend_create(rate, frame, filterLen, mics, refs, suppressDb, hpfHz);
}

JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_audio_VoiceFrontend_nativeProcess(JNIEnv *env, jclass clazz, jlong handle,
                                                         jshortArray in, jint channels, jshortArray out) {
    (void) clazz;
    Frontend *f = (Frontend *) (intptr_t) handle;
    if (!f || channels != f->mics + f->refs) return JNI_FALSE;
    if ((*env)->GetArrayLength(env, in) < f->frame * channels
            || (*env)->GetArrayLength(env, out) < f->frame) {
        return JNI_FALSE;
    }
    jshort *src = (*env)->GetPrimitiveArrayCritical(env, in, NULL);
    if (!src) return JNI_FALSE;
    jshort *dst = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!dst) {
        (*env)->ReleasePrimitiveArrayCritical(env, in, src, JNI_ABORT);
        return JNI_FALSE;
    }
    frontend_process(f, (const int16_t *) src, channels, (int16_t *) dst);
    (*env)->ReleasePrimitiveArrayCritical(env, out, dst, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, in, src, JNI_ABORT);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_overdrive_app_audio_VoiceFrontend_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    frontend_destroy((Frontend *) (intptr_t) handle);
}
