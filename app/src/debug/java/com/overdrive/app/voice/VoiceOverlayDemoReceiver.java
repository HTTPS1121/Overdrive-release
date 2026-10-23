package com.overdrive.app.voice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.overdrive.app.R;
import com.overdrive.app.voice.ui.VoiceOrbView;
import com.overdrive.app.voice.ui.VoiceOverlay;

/** Debug builds only: plays the voice overlay through its states for visual review. */
public final class VoiceOverlayDemoReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        VoiceOverlay overlay = new VoiceOverlay(context);
        Handler main = new Handler(Looper.getMainLooper());
        long t0 = SystemClock.uptimeMillis();
        Runnable level = new Runnable() {
            @Override
            public void run() {
                long t = SystemClock.uptimeMillis() - t0;
                boolean talking = t < 2600 || (t > 7600 && t < 9100);
                double s = t / 1000.0;
                double voice = Math.abs(Math.sin(s * 9.0)) * (0.55 + 0.45 * Math.sin(s * 2.3));
                overlay.setLevel(talking ? (float) Math.max(0, voice) : 0f);
                if (t < 11000) main.postDelayed(this, 33);
            }
        };
        overlay.show(VoiceOrbView.STATE_LISTENING, overlay.text(R.string.voice_overlay_listening));
        main.post(level);
        main.postDelayed(() -> overlay.show(VoiceOrbView.STATE_THINKING,
                overlay.text(R.string.voice_overlay_thinking)), 2600);
        main.postDelayed(() -> overlay.show(VoiceOrbView.STATE_SPEAKING,
                overlay.text(R.string.voice_overlay_speaking)), 3800);
        main.postDelayed(() -> overlay.finish(VoiceOrbView.STATE_SUCCESS,
                overlay.text(R.string.voice_overlay_done), 1200), 5600);
        main.postDelayed(() -> overlay.show(VoiceOrbView.STATE_LISTENING,
                overlay.text(R.string.voice_overlay_listening)), 7600);
        main.postDelayed(() -> overlay.finish(VoiceOrbView.STATE_ERROR,
                overlay.text(R.string.voice_overlay_blocked_moving), 1500), 9100);
        main.postDelayed(() -> {
            overlay.release();
            pending.finish();
        }, 11500);
    }
}
