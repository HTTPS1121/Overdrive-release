package com.overdrive.app.voice.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.overdrive.app.R;

/** Audio-reactive gradient orb; it only animates while attached and visible. */
public final class VoiceOrbView extends View {

    public static final int STATE_LISTENING = 0;
    public static final int STATE_THINKING = 1;
    public static final int STATE_SPEAKING = 2;
    public static final int STATE_SUCCESS = 3;
    public static final int STATE_ERROR = 4;

    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final Matrix matrix = new Matrix();
    private final int[] blobColors = new int[3];
    private final RadialGradient[] blobShaders = new RadialGradient[3];
    private final int[] blobShaderColors = new int[3];
    private RadialGradient glowShader;
    private int glowShaderColor;
    private final int successColor;
    private final int errorColor;
    private final long startedAt = SystemClock.uptimeMillis();

    private int state = STATE_LISTENING;
    private float targetLevel;
    private float level;
    private float tint;
    private int tintColor;
    private float phase;
    private long lastFrameAt;
    private float clipW = -1f;
    private float clipH = -1f;

    public VoiceOrbView(Context context) {
        super(context);
        // The orb is a dark sphere in both themes; the night pastels would add up to white.
        blobColors[0] = ContextCompat.getColor(context, R.color.md_sys_color_primary_light);
        blobColors[1] = ContextCompat.getColor(context, R.color.md_sys_color_secondary_light);
        blobColors[2] = ContextCompat.getColor(context, R.color.md_sys_color_tertiary_light);
        successColor = ContextCompat.getColor(context, R.color.overdrive_status_success_light);
        errorColor = ContextCompat.getColor(context, R.color.overdrive_status_danger_light);
        tintColor = successColor;
        blobPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        basePaint.setColor(Color.argb(235, 12, 16, 22));
    }

    public void setState(int state) {
        this.state = state;
        if (state == STATE_SUCCESS) tintColor = successColor;
        if (state == STATE_ERROR) tintColor = errorColor;
        invalidate();
    }

    /** Microphone level 0..1, smoothed while drawing. */
    public void setLevel(float level) {
        targetLevel = Math.max(0f, Math.min(1f, level));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        // The first frame after (re)attach starts at the current state, not the last turn's colour.
        boolean firstFrame = lastFrameAt == 0;
        float dt = firstFrame ? 0.016f : Math.min(0.1f, (now - lastFrameAt) / 1000f);
        lastFrameAt = now;

        float speed;
        float drive;
        switch (state) {
            case STATE_THINKING: speed = 2.4f; drive = 0.35f + 0.25f * pulse(now, 900); break;
            case STATE_SPEAKING: speed = 1.5f; drive = 0.30f + 0.30f * pulse(now, 520); break;
            case STATE_SUCCESS:
            case STATE_ERROR: speed = 0.8f; drive = 0.25f; break;
            default: speed = 1f; drive = targetLevel; break;
        }
        boolean tinted = state == STATE_SUCCESS || state == STATE_ERROR;
        if (firstFrame) {
            level = drive;
            tint = tinted ? 1f : 0f;
        }
        level += (drive - level) * Math.min(1f, dt * 10f);
        tint += ((tinted ? 1f : 0f) - tint) * Math.min(1f, dt * 6f);
        phase += dt * speed;

        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f * 0.78f;
        if (w != clipW || h != clipH) {
            clipW = w;
            clipH = h;
            clip.reset();
            clip.addCircle(cx, cy, r, Path.Direction.CW);
        }

        float glowR = r * (1.12f + 0.22f * level);
        int glowColor = withAlpha(mix(blobColors[0], tintColor, tint), 110);
        if (glowShader == null || glowShaderColor != glowColor) {
            glowShader = new RadialGradient(0f, 0f, 1f, glowColor, Color.TRANSPARENT,
                    Shader.TileMode.CLAMP);
            glowShaderColor = glowColor;
        }
        matrix.setScale(glowR, glowR);
        matrix.postTranslate(cx, cy);
        glowShader.setLocalMatrix(matrix);
        glowPaint.setShader(glowShader);
        canvas.drawCircle(cx, cy, glowR, glowPaint);

        canvas.save();
        canvas.clipPath(clip);
        canvas.drawCircle(cx, cy, r, basePaint);
        for (int i = 0; i < 3; i++) {
            double a = phase * (0.9 + 0.35 * i) + i * 2.094;
            float orbit = r * (0.18f + 0.10f * level);
            float bx = cx + (float) Math.cos(a) * orbit;
            float by = cy + (float) Math.sin(a * 1.17) * orbit;
            float br = r * (0.70f + 0.20f * level * (i == 0 ? 1f : 0.7f));
            int color = withAlpha(mix(blobColors[i], tintColor, tint), 210);
            if (blobShaders[i] == null || blobShaderColors[i] != color) {
                blobShaders[i] = new RadialGradient(0f, 0f, 1f, color, Color.TRANSPARENT,
                        Shader.TileMode.CLAMP);
                blobShaderColors[i] = color;
            }
            matrix.setScale(br, br);
            matrix.postTranslate(bx, by);
            blobShaders[i].setLocalMatrix(matrix);
            blobPaint.setShader(blobShaders[i]);
            canvas.drawCircle(bx, by, br, blobPaint);
        }
        canvas.restore();

        if (isAttachedToWindow() && getVisibility() == VISIBLE) postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        lastFrameAt = 0;
    }

    private float pulse(long now, int periodMs) {
        double t = ((now - startedAt) % periodMs) / (double) periodMs;
        return (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * t));
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | (alpha << 24);
    }

    private static int mix(int a, int b, float t) {
        if (t <= 0.001f) return a;
        if (t >= 0.999f) return b;
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }
}
