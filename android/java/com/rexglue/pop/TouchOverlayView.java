package com.rexglue.pop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class TouchOverlayView extends View {
    private static native int nativeGetShaderState();
    private static native int nativeGetShaderDone();
    private static native int nativeGetShaderTotal();
    private static native boolean nativeIsBootReady();
    private final Paint barFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    static native void nativeEnsureGamepad();
    static native void nativeSetButton(int button, boolean down);
    static native void nativeSetAxis(int axis, short value);
    static native long nativeGetPresentedFrames();

    private static final int ICON_JUMP  = 1;
    private static final int ICON_CLAW  = 2;
    private static final int ICON_SWORD = 3;
    private static final int ICON_ELIKA = 4;
    private static final int ICON_GRIP  = 5;
    private static final int ICON_LIGHT = 6;
    private static final int ICON_TALK  = 7;
    private static final int ICON_LOOK  = 8;
    private static final int ICON_MAP   = 9;
    private static final int ICON_PAUSE = 10;
    private static final int ICON_HUD   = 11;

    private static class Btn {
        String caption;
        int code;
        int iconType;
        float cx, cy, r;
        int fillColor;
        int glowColor;
        boolean primary;
        boolean pressed;

        Btn(String cap, int c, int icon, int fill, int glow, boolean isPrimary) {
            caption = cap;
            code = c;
            iconType = icon;
            fillColor = fill;
            glowColor = glow;
            primary = isPrimary;
        }
    }

    private static class Stick {
        int axisX, axisY;
        float cx, cy, r, kx, ky;
        int pointerId = -1;
        Stick(int ax, int ay) { axisX = ax; axisY = ay; }
    }

    private final List<Btn> buttons = new ArrayList<>();
    private final Stick leftStick = new Stick(0, 1);
    private final SparseArray<Btn> activeBtns = new SparseArray<>();
    private boolean hidden = false;

    // Right-side touch-drag camera state (mapped to Right Stick axes 2 & 3)
    private int camPointerId = -1;
    private float camLastX = 0f, camLastY = 0f;
    private float camCurX = 0f, camCurY = 0f;
    private float camAccumDx = 0f, camAccumDy = 0f;
    private float camSmoothX = 0f, camSmoothY = 0f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint goldOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint goldInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint captionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fpsBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fpsBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fpsTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF fpsRect = new RectF();
    private final RectF tmpRect = new RectF();
    private final Path iconPath = new Path();

    private final boolean showFps;
    private long lastFrames = 0;
    private long lastTimeMs = 0;
    private int currentFps = 0;
    private boolean bootComplete = false;
    private long bootStartMs = 0;
    private long bootBaseFrames = 0;

    private final Runnable bootTicker = new Runnable() {
        @Override
        public void run() {
            if (nativeIsBootReady()) {
                bootComplete = true;
                invalidate();
                return;
            }
            invalidate();
            postDelayed(this, 60);
        }
    };

    private final Runnable fpsTicker = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            long frames = nativeGetPresentedFrames();
            if (lastTimeMs > 0 && now > lastTimeMs) {
                long df = frames - lastFrames;
                long dt = now - lastTimeMs;
                currentFps = df >= 0 ? (int) Math.round((df * 1000.0) / dt) : 0;
            }
            lastFrames = frames;
            lastTimeMs = now;
            invalidate();
            postDelayed(this, 500);
        }
    };

    private final Runnable camTicker = new Runnable() {
        @Override
        public void run() {
            if (camPointerId == -1) {
                if (camSmoothX != 0f || camSmoothY != 0f) {
                    camSmoothX = 0f;
                    camSmoothY = 0f;
                    nativeSetAxis(2, (short) 0);
                    nativeSetAxis(3, (short) 0);
                }
                return;
            }
            float scale = Math.max(getWidth(), 1280) * 0.0052f;
            float rawX = clamp(camAccumDx / scale, -1f, 1f);
            float rawY = clamp(camAccumDy / scale, -1f, 1f);
            camAccumDx = 0f;
            camAccumDy = 0f;

            float targetX = (Math.abs(rawX) > 0.015f)
                    ? Math.signum(rawX) * (0.28f + 0.72f * Math.abs(rawX))
                    : 0f;
            float targetY = (Math.abs(rawY) > 0.015f)
                    ? Math.signum(rawY) * (0.28f + 0.72f * Math.abs(rawY))
                    : 0f;

            camSmoothX = camSmoothX * 0.18f + targetX * 0.82f;
            camSmoothY = camSmoothY * 0.18f + targetY * 0.82f;
            if (Math.abs(camSmoothX) < 0.04f) camSmoothX = 0f;
            if (Math.abs(camSmoothY) < 0.04f) camSmoothY = 0f;

            nativeSetAxis(2, (short) (camSmoothX * 32767f));
            nativeSetAxis(3, (short) (camSmoothY * 32767f));
            postDelayed(this, 16);
        }
    };

    public TouchOverlayView(Context ctx) {
        super(ctx);
        boolean fpsFlag = true;
        try {
            File dir = ctx.getExternalFilesDir(null);
            File f = new File(dir != null ? dir : ctx.getFilesDir(), LauncherActivity.SETTINGS_FILE);
            if (f.isFile()) {
                Properties p = new Properties();
                try (FileInputStream fis = new FileInputStream(f)) { p.load(fis); }
                fpsFlag = Boolean.parseBoolean(p.getProperty("show_fps", "true"));
            }
        } catch (Exception ignored) {}
        showFps = fpsFlag;

        goldOuterPaint.setStyle(Paint.Style.STROKE);
        goldOuterPaint.setStrokeWidth(3.6f);
        goldOuterPaint.setColor(Color.argb(215, 212, 175, 55));

        goldInnerPaint.setStyle(Paint.Style.STROKE);
        goldInnerPaint.setStrokeWidth(1.5f);
        goldInnerPaint.setColor(Color.argb(135, 250, 232, 160));

        iconFillPaint.setStyle(Paint.Style.FILL);
        iconFillPaint.setColor(Color.argb(245, 255, 244, 214));

        iconStrokePaint.setStyle(Paint.Style.STROKE);
        iconStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        iconStrokePaint.setStrokeJoin(Paint.Join.ROUND);
        iconStrokePaint.setColor(Color.argb(245, 255, 244, 214));

        captionPaint.setColor(Color.argb(220, 245, 222, 165));
        captionPaint.setTextAlign(Paint.Align.CENTER);
        captionPaint.setTypeface(Typeface.SERIF);
        captionPaint.setFakeBoldText(true);

        fpsBgPaint.setColor(Color.argb(175, 22, 15, 10));
        fpsBorderPaint.setStyle(Paint.Style.STROKE);
        fpsBorderPaint.setStrokeWidth(2f);
        fpsBorderPaint.setColor(Color.argb(180, 212, 175, 55));
        fpsTextPaint.setColor(Color.parseColor("#8EF56E"));
        fpsTextPaint.setTypeface(Typeface.MONOSPACE);
        fpsTextPaint.setFakeBoldText(true);
        fpsTextPaint.setTextSize(32f);

        // Primary 4 Prince of Persia action medallions (mapped to Xbox A, B, X, Y)
        buttons.add(new Btn("JUMP",  0, ICON_JUMP,  Color.argb(150, 18, 68, 52),  Color.argb(215, 76, 209, 148), true));
        buttons.add(new Btn("CLAW",  1, ICON_CLAW,  Color.argb(150, 82, 22, 24),  Color.argb(215, 231, 96, 80),  true));
        buttons.add(new Btn("SWORD", 2, ICON_SWORD, Color.argb(150, 22, 46, 86),  Color.argb(215, 92, 172, 245), true));
        buttons.add(new Btn("ELIKA", 3, ICON_ELIKA, Color.argb(155, 96, 68, 16),  Color.argb(225, 255, 214, 92), true));

        // Combat & exploration actions (RT, RB, LT, LB)
        buttons.add(new Btn("GRIP",  -2, ICON_GRIP,  Color.argb(145, 52, 36, 24), Color.argb(215, 230, 178, 96), true));
        buttons.add(new Btn("LIGHT", 10, ICON_LIGHT, Color.argb(140, 30, 62, 72), Color.argb(215, 130, 235, 240), false));
        buttons.add(new Btn("TALK",  -1, ICON_TALK,  Color.argb(140, 42, 46, 76), Color.argb(215, 170, 195, 255), false));
        buttons.add(new Btn("LOOK",   9, ICON_LOOK,  Color.argb(140, 48, 36, 28), Color.argb(215, 225, 195, 130), false));

        // Compact utility medallions (BACK, START, HUD toggle)
        buttons.add(new Btn("MAP",    4, ICON_MAP,   Color.argb(135, 36, 26, 18), Color.argb(205, 215, 180, 105), false));
        buttons.add(new Btn("PAUSE",  6, ICON_PAUSE, Color.argb(135, 36, 26, 18), Color.argb(205, 215, 180, 105), false));
        buttons.add(new Btn("",     -99, ICON_HUD,   Color.argb(120, 32, 22, 16), Color.argb(200, 212, 175, 55),  false));

        bootStartMs = SystemClock.elapsedRealtime();
        bootBaseFrames = nativeGetPresentedFrames();
        post(bootTicker);
        if (showFps) {
            postDelayed(fpsTicker, 500);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(fpsTicker);
        removeCallbacks(camTicker);
        removeCallbacks(bootTicker);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float u = Math.min(w, h) * 0.085f;
        fpsTextPaint.setTextSize(Math.max(22f, u * 0.31f));

        // Dedicated left movement joystick
        leftStick.cx = w * 0.135f;
        leftStick.cy = h * 0.72f;
        leftStick.r = u * 1.48f;
        leftStick.kx = leftStick.cx;
        leftStick.ky = leftStick.cy;

        // Right-thumb PUBG-style layout:
        // Large SWORD at lower-right, slightly smaller JUMP directly above it,
        // ELIKA & CLAW stacked at equal size to the left of JUMP & SWORD
        float rightColX = w * 0.885f;
        float leftColX  = w * 0.775f;
        setBtn(2, rightColX, h * 0.785f, u * 0.96f); // SWORD (large, lower-right)
        setBtn(0, rightColX, h * 0.545f, u * 0.76f); // JUMP  (directly above SWORD)
        setBtn(3, leftColX,  h * 0.575f, u * 0.68f); // ELIKA (left of JUMP)
        setBtn(1, leftColX,  h * 0.805f, u * 0.68f); // CLAW  (left of SWORD, same size)

        // GRIP tucked cleanly above the cluster next to LIGHT
        setBtn(-2, w * 0.790f, h * 0.20f, u * 0.62f); // GRIP

        // Top exploration keys (wide open center-right stays free for camera touch-drag)
        setBtn(9,  w * 0.085f, h * 0.19f, u * 0.62f); // LOOK
        setBtn(-1, w * 0.175f, h * 0.19f, u * 0.62f); // TALK
        setBtn(10, w * 0.905f, h * 0.19f, u * 0.62f); // LIGHT (Compass)

        // Compact top-center utility bar
        setBtn(4,   w * 0.415f, h * 0.135f, u * 0.48f); // MAP
        setBtn(-99, w * 0.500f, h * 0.165f, u * 0.40f); // HUD Toggle
        setBtn(6,   w * 0.585f, h * 0.135f, u * 0.48f); // PAUSE
    }

    private void setBtn(int code, float cx, float cy, float r) {
        for (Btn b : buttons) {
            if (b.code == code) { b.cx = cx; b.cy = cy; b.r = r; return; }
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!bootComplete) {
            drawBootOverlay(c);
            return;
        }
        if (showFps) {
            String txt = currentFps + " FPS";
            if (currentFps >= 25) fpsTextPaint.setColor(Color.parseColor("#8EF56E"));
            else if (currentFps >= 15) fpsTextPaint.setColor(Color.parseColor("#FFD966"));
            else fpsTextPaint.setColor(Color.parseColor("#FF6B6B"));

            float tw = fpsTextPaint.measureText(txt);
            float th = fpsTextPaint.getTextSize();
            float boxW = tw + 28f;
            float boxH = th + 14f;
            float left = (getWidth() - boxW) * 0.5f;
            float top = 10f;
            fpsRect.set(left, top, left + boxW, top + boxH);
            c.drawRoundRect(fpsRect, 12f, 12f, fpsBgPaint);
            c.drawRoundRect(fpsRect, 12f, 12f, fpsBorderPaint);
            c.drawText(txt, left + 14f, top + th + 1f, fpsTextPaint);
        }

        if (!hidden) {
            drawPersianStick(c, leftStick);
            if (camPointerId != -1) {
                // Subtle golden touch reticle only while swiping camera
                fillPaint.setColor(Color.argb(45, 255, 220, 120));
                c.drawCircle(camCurX, camCurY, 44f, fillPaint);
                goldInnerPaint.setColor(Color.argb(150, 245, 212, 115));
                c.drawCircle(camCurX, camCurY, 44f, goldInnerPaint);
                goldInnerPaint.setColor(Color.argb(135, 250, 232, 160));
            }
        }

        for (Btn b : buttons) {
            if (hidden && b.code != -99) continue;
            drawPersianMedallion(c, b);
        }
    }

    private void drawBootOverlay(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        c.drawColor(Color.rgb(14, 10, 7));

        long elapsedMs = Math.max(0L, android.os.SystemClock.elapsedRealtime() - bootStartMs);
        float sec = elapsedMs / 1000f;
        int st    = nativeGetShaderState();
        int done  = nativeGetShaderDone();
        int total = nativeGetShaderTotal();
        float cx = w * 0.5f;
        float cy = h * 0.39f;
        float r  = Math.min(w, h) * 0.11f;

        goldInnerPaint.setColor(Color.argb(90, 212, 175, 55));
        c.drawCircle(cx, cy, r, goldInnerPaint);
        goldOuterPaint.setStrokeWidth(5f);
        goldOuterPaint.setColor(Color.argb(235, 232, 192, 85));
        tmpRect.set(cx - r, cy - r, cx + r, cy + r);
        float angle = (elapsedMs * 0.22f) % 360f;
        c.drawArc(tmpRect, angle, 110f, false, goldOuterPaint);
        c.drawArc(tmpRect, angle + 180f, 110f, false, goldOuterPaint);

        drawIcon(c, ICON_ELIKA, cx, cy, r * 0.52f, false);

        captionPaint.setTextSize(Math.max(36f, Math.min(w, h) * 0.048f));
        captionPaint.setColor(Color.argb(245, 245, 216, 138));
        c.drawText("PRINCE OF PERSIA", cx, cy + r + Math.min(w, h) * 0.095f, captionPaint);

        String statusText;
        float frac = -1f;
        if (st == 1 && total > 0) {
            int clamped = Math.min(done, total);
            frac = clamped / (float) total;
            int pct = Math.min(100, Math.round(frac * 100f));
            statusText = String.format(java.util.Locale.US, "Precompiling Shaders  %d / %d  (%d%%)", clamped, total, pct);
        } else if (st == 2) {
            frac = 1f;
            statusText = String.format(java.util.Locale.US, "Starting Game...  %.1fs", sec);
        } else {
            statusText = String.format(java.util.Locale.US, "Initializing Engine...  %.1fs", sec);
        }

        captionPaint.setTextSize(Math.max(24f, Math.min(w, h) * 0.030f));
        captionPaint.setColor(Color.argb(235, 238, 224, 190));
        c.drawText(statusText, cx, cy + r + Math.min(w, h) * 0.155f, captionPaint);

        float barW = Math.min(w * 0.52f, 760f);
        float barH = Math.max(12f, Math.min(w, h) * 0.015f);
        float barX = cx - barW * 0.5f;
        float barY = cy + r + Math.min(w, h) * 0.185f;
        goldOuterPaint.setStrokeWidth(2.5f);
        goldOuterPaint.setColor(Color.argb(170, 226, 186, 92));
        tmpRect.set(barX, barY, barX + barW, barY + barH);
        c.drawRoundRect(tmpRect, barH * 0.5f, barH * 0.5f, goldOuterPaint);

        float fillFrac = (frac >= 0f) ? frac : (0.15f + 0.10f * (float) Math.sin(elapsedMs * 0.004));
        if (fillFrac > 0.01f) {
            float fillW = Math.max(barH, barW * fillFrac);
            tmpRect.set(barX + 2f, barY + 2f, barX + fillW - 2f, barY + barH - 2f);
            barFillPaint.setStyle(Paint.Style.FILL);
            barFillPaint.setColor(Color.argb(235, 226, 186, 86));
            c.drawRoundRect(tmpRect, barH * 0.5f, barH * 0.5f, barFillPaint);
        }

        captionPaint.setTextSize(Math.max(18f, Math.min(w, h) * 0.022f));
        captionPaint.setColor(Color.argb(165, 200, 185, 155));
        c.drawText("Audio muted until shader cache finishes compiling", cx, barY + barH + Math.min(w, h) * 0.048f, captionPaint);
        goldInnerPaint.setColor(Color.argb(135, 250, 232, 160));
    }

    private void drawPersianStick(Canvas c, Stick s) {
        // Dark obsidian base with ornate Persian compass rings
        fillPaint.setColor(Color.argb(115, 24, 16, 11));
        c.drawCircle(s.cx, s.cy, s.r, fillPaint);

        goldOuterPaint.setStrokeWidth(3.8f);
        c.drawCircle(s.cx, s.cy, s.r, goldOuterPaint);
        c.drawCircle(s.cx, s.cy, s.r * 0.87f, goldInnerPaint);

        // 8-point compass ticks
        iconStrokePaint.setStrokeWidth(2.2f);
        iconStrokePaint.setColor(Color.argb(160, 235, 200, 110));
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4.0;
            float rIn = (i % 2 == 0) ? s.r * 0.72f : s.r * 0.80f;
            float rOut = s.r * 0.94f;
            float cos = (float) Math.cos(ang);
            float sin = (float) Math.sin(ang);
            c.drawLine(s.cx + cos * rIn, s.cy + sin * rIn, s.cx + cos * rOut, s.cy + sin * rOut, iconStrokePaint);
        }

        // Glowing golden sand thumb orb
        boolean active = (s.pointerId != -1);
        float kr = s.r * 0.39f;
        fillPaint.setColor(active ? Color.argb(210, 236, 190, 82) : Color.argb(165, 196, 152, 68));
        c.drawCircle(s.kx, s.ky, kr, fillPaint);
        goldOuterPaint.setStrokeWidth(2.6f);
        c.drawCircle(s.kx, s.ky, kr, goldOuterPaint);
        fillPaint.setColor(Color.argb(190, 255, 244, 205));
        c.drawCircle(s.kx, s.ky, kr * 0.36f, fillPaint);
    }

    private void drawPersianMedallion(Canvas c, Btn b) {
        float cx = b.cx, cy = b.cy, r = b.r;

        // Medallion body
        fillPaint.setColor(b.pressed ? b.glowColor : b.fillColor);
        c.drawCircle(cx, cy, r, fillPaint);

        // Double ornate gold border
        goldOuterPaint.setStrokeWidth(b.pressed ? 4.8f : 3.4f);
        goldOuterPaint.setColor(b.pressed ? Color.argb(255, 255, 238, 155) : Color.argb(215, 212, 175, 55));
        c.drawCircle(cx, cy, r, goldOuterPaint);
        c.drawCircle(cx, cy, r * 0.86f, goldInnerPaint);

        // Four subtle Persian diamond flourishes on primary medallions
        if (b.primary) {
            iconFillPaint.setColor(Color.argb(220, 238, 205, 110));
            float d = r * 0.085f;
            drawDiamond(c, cx, cy - r, d);
            drawDiamond(c, cx, cy + r, d);
            drawDiamond(c, cx - r, cy, d);
            drawDiamond(c, cx + r, cy, d);
        }

        // Vector icon shifted slightly up when caption is shown
        boolean hasCap = b.caption != null && !b.caption.isEmpty();
        float iconY = hasCap ? cy - r * 0.11f : cy;
        float iconR = hasCap ? r * 0.44f : r * 0.48f;
        drawIcon(c, b.iconType, cx, iconY, iconR, b.pressed);

        if (hasCap) {
            captionPaint.setTextSize(r * 0.27f);
            captionPaint.setColor(b.pressed ? Color.argb(255, 30, 18, 8) : Color.argb(230, 248, 228, 175));
            c.drawText(b.caption, cx, cy + r * 0.66f, captionPaint);
        }
        drawKeyBadgeCircle(c, b, cx, cy, r);
    }

    private String getMappedKeyLabel(Btn b) {
        switch (b.code) {
            case 0:  return "A";
            case 1:  return "B";
            case 2:  return "X";
            case 3:  return "Y";
            case 4:  return "BACK";
            case 6:  return "START";
            case 9:  return "RB";
            case 10: return "RT";
            case -1: return "LB";
            case -2: return "LT";
            default: return "";
        }
    }

    private void drawKeyBadgeCircle(Canvas c, Btn b, float cx, float cy, float r) {
        String keyLbl = getMappedKeyLabel(b);
        if (keyLbl == null || keyLbl.isEmpty()) return;

        // Larger radius + bolder text for clear readability on smaller/compact screens
        float br = Math.max(17f, Math.min(r * 0.34f, 34f));
        float bx = cx + r * 0.66f;
        float by = cy - r * 0.66f;

        fillPaint.setColor(b.pressed ? Color.argb(250, 250, 224, 120) : Color.argb(238, 16, 11, 7));
        c.drawCircle(bx, by, br, fillPaint);

        goldOuterPaint.setStrokeWidth(Math.max(2.4f, br * 0.14f));
        goldOuterPaint.setColor(b.pressed ? Color.argb(255, 255, 242, 180) : Color.argb(245, 238, 198, 88));
        c.drawCircle(bx, by, br, goldOuterPaint);

        boolean isLongLbl = keyLbl.length() > 2;
        captionPaint.setTextSize(isLongLbl ? br * 0.72f : br * 1.05f);
        captionPaint.setColor(b.pressed ? Color.argb(255, 25, 15, 6) : Color.argb(255, 255, 242, 185));
        Paint.FontMetrics fm = captionPaint.getFontMetrics();
        float textY = by - (fm.ascent + fm.descent) * 0.5f;
        c.drawText(keyLbl, bx, textY, captionPaint);
    }

    private void drawDiamond(Canvas c, float x, float y, float d) {
        iconPath.reset();
        iconPath.moveTo(x, y - d);
        iconPath.lineTo(x + d, y);
        iconPath.lineTo(x, y + d);
        iconPath.lineTo(x - d, y);
        iconPath.close();
        c.drawPath(iconPath, iconFillPaint);
    }

    private void drawIcon(Canvas c, int type, float cx, float cy, float s, boolean pressed) {
        int col = pressed ? Color.argb(255, 28, 18, 10) : Color.argb(245, 255, 245, 218);
        iconFillPaint.setColor(col);
        iconStrokePaint.setColor(col);
        iconStrokePaint.setStrokeWidth(Math.max(2.6f, s * 0.14f));
        iconPath.reset();

        switch (type) {
            case ICON_SWORD: {
                // Curved Persian scimitar (shamshir)
                iconPath.moveTo(cx - s * 0.72f, cy + s * 0.48f);
                iconPath.quadTo(cx + s * 0.20f, cy + s * 0.35f, cx + s * 0.78f, cy - s * 0.72f);
                iconPath.quadTo(cx + s * 0.38f, cy - s * 0.02f, cx - s * 0.48f, cy + s * 0.24f);
                iconPath.close();
                c.drawPath(iconPath, iconFillPaint);
                // Crossguard & hilt
                c.drawLine(cx - s * 0.66f, cy + s * 0.08f, cx - s * 0.24f, cy + s * 0.56f, iconStrokePaint);
                c.drawLine(cx - s * 0.48f, cy + s * 0.34f, cx - s * 0.80f, cy + s * 0.68f, iconStrokePaint);
                break;
            }
            case ICON_JUMP: {
                // Sweeping acrobatic leap chevron + wing trails
                iconPath.moveTo(cx - s * 0.68f, cy + s * 0.38f);
                iconPath.quadTo(cx - s * 0.05f, cy - s * 0.58f, cx + s * 0.72f, cy - s * 0.36f);
                iconPath.quadTo(cx + s * 0.08f, cy - s * 0.18f, cx - s * 0.42f, cy + s * 0.62f);
                iconPath.close();
                c.drawPath(iconPath, iconFillPaint);
                // Upward arrowhead
                iconPath.reset();
                iconPath.moveTo(cx + s * 0.28f, cy - s * 0.68f);
                iconPath.lineTo(cx + s * 0.80f, cy - s * 0.38f);
                iconPath.lineTo(cx + s * 0.44f, cy + s * 0.06f);
                c.drawPath(iconPath, iconStrokePaint);
                break;
            }
            case ICON_CLAW: {
                // Three fierce curved gauntlet claw blades
                float[] ox = new float[] { -s * 0.42f, 0f, s * 0.42f };
                float[] oy = new float[] { s * 0.10f, -s * 0.05f, s * 0.12f };
                for (int i = 0; i < 3; i++) {
                    iconPath.reset();
                    iconPath.moveTo(cx + ox[i] - s * 0.26f, cy + oy[i] - s * 0.52f);
                    iconPath.quadTo(cx + ox[i] + s * 0.14f, cy + oy[i] - s * 0.04f,
                                    cx + ox[i] + s * 0.22f, cy + oy[i] + s * 0.62f);
                    iconPath.quadTo(cx + ox[i] - s * 0.02f, cy + oy[i] + s * 0.06f,
                                    cx + ox[i] - s * 0.34f, cy + oy[i] - s * 0.36f);
                    iconPath.close();
                    c.drawPath(iconPath, iconFillPaint);
                }
                break;
            }
            case ICON_ELIKA: {
                // Radiant 4-point Persian magic light star
                iconPath.moveTo(cx, cy - s * 0.88f);
                iconPath.quadTo(cx + s * 0.14f, cy - s * 0.14f, cx + s * 0.88f, cy);
                iconPath.quadTo(cx + s * 0.14f, cy + s * 0.14f, cx, cy + s * 0.88f);
                iconPath.quadTo(cx - s * 0.14f, cy + s * 0.14f, cx - s * 0.88f, cy);
                iconPath.quadTo(cx - s * 0.14f, cy - s * 0.14f, cx, cy - s * 0.88f);
                iconPath.close();
                c.drawPath(iconPath, iconFillPaint);
                c.drawCircle(cx, cy, s * 0.28f, iconStrokePaint);
                break;
            }
            case ICON_GRIP: {
                // Pointed Persian kite/teardrop shield
                iconPath.moveTo(cx, cy - s * 0.78f);
                iconPath.lineTo(cx + s * 0.66f, cy - s * 0.42f);
                iconPath.quadTo(cx + s * 0.62f, cy + s * 0.32f, cx, cy + s * 0.82f);
                iconPath.quadTo(cx - s * 0.62f, cy + s * 0.32f, cx - s * 0.66f, cy - s * 0.42f);
                iconPath.close();
                c.drawPath(iconPath, iconStrokePaint);
                c.drawLine(cx, cy - s * 0.56f, cx, cy + s * 0.56f, iconStrokePaint);
                c.drawCircle(cx, cy - s * 0.06f, s * 0.18f, iconFillPaint);
                break;
            }
            case ICON_LIGHT: {
                // Guiding Light 8-point compass star
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4.0;
                    float len = (i % 2 == 0) ? s * 0.82f : s * 0.48f;
                    c.drawLine(cx, cy, cx + (float) Math.cos(a) * len, cy + (float) Math.sin(a) * len, iconStrokePaint);
                }
                c.drawCircle(cx, cy, s * 0.24f, iconFillPaint);
                break;
            }
            case ICON_TALK: {
                // Ornate speech bubble with sound wave
                tmpRect.set(cx - s * 0.66f, cy - s * 0.54f, cx + s * 0.66f, cy + s * 0.34f);
                c.drawRoundRect(tmpRect, s * 0.28f, s * 0.28f, iconStrokePaint);
                iconPath.moveTo(cx - s * 0.26f, cy + s * 0.34f);
                iconPath.lineTo(cx - s * 0.48f, cy + s * 0.70f);
                iconPath.lineTo(cx + s * 0.06f, cy + s * 0.34f);
                c.drawPath(iconPath, iconStrokePaint);
                c.drawCircle(cx - s * 0.24f, cy - s * 0.10f, s * 0.09f, iconFillPaint);
                c.drawCircle(cx,             cy - s * 0.10f, s * 0.09f, iconFillPaint);
                c.drawCircle(cx + s * 0.24f, cy - s * 0.10f, s * 0.09f, iconFillPaint);
                break;
            }
            case ICON_LOOK: {
                // Stylized Persian almond eye
                iconPath.moveTo(cx - s * 0.78f, cy);
                iconPath.quadTo(cx, cy - s * 0.68f, cx + s * 0.78f, cy);
                iconPath.quadTo(cx, cy + s * 0.68f, cx - s * 0.78f, cy);
                iconPath.close();
                c.drawPath(iconPath, iconStrokePaint);
                c.drawCircle(cx, cy, s * 0.26f, iconFillPaint);
                break;
            }
            case ICON_MAP: {
                // Ancient folded scroll/map
                tmpRect.set(cx - s * 0.64f, cy - s * 0.46f, cx + s * 0.64f, cy + s * 0.46f);
                c.drawRoundRect(tmpRect, s * 0.14f, s * 0.14f, iconStrokePaint);
                c.drawLine(cx - s * 0.36f, cy - s * 0.14f, cx + s * 0.36f, cy - s * 0.14f, iconStrokePaint);
                c.drawLine(cx - s * 0.36f, cy + s * 0.14f, cx + s * 0.20f, cy + s * 0.14f, iconStrokePaint);
                break;
            }
            case ICON_PAUSE: {
                // Two ornate vertical pause pillars
                tmpRect.set(cx - s * 0.44f, cy - s * 0.56f, cx - s * 0.14f, cy + s * 0.56f);
                c.drawRoundRect(tmpRect, s * 0.08f, s * 0.08f, iconFillPaint);
                tmpRect.set(cx + s * 0.14f, cy - s * 0.56f, cx + s * 0.44f, cy + s * 0.56f);
                c.drawRoundRect(tmpRect, s * 0.08f, s * 0.08f, iconFillPaint);
                break;
            }
            case ICON_HUD: {
                // Eye toggle (crossed out when hidden)
                iconPath.moveTo(cx - s * 0.72f, cy);
                iconPath.quadTo(cx, cy - s * 0.56f, cx + s * 0.72f, cy);
                iconPath.quadTo(cx, cy + s * 0.56f, cx - s * 0.72f, cy);
                iconPath.close();
                c.drawPath(iconPath, iconStrokePaint);
                c.drawCircle(cx, cy, s * 0.22f, iconFillPaint);
                if (hidden) {
                    c.drawLine(cx - s * 0.64f, cy + s * 0.56f, cx + s * 0.64f, cy - s * 0.56f, iconStrokePaint);
                }
                break;
            }
            default:
                break;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void sendBtn(Btn b, boolean down) {
        b.pressed = down;
        if (b.code == -1) nativeSetAxis(4, (short) (down ? 32767 : -32768));
        else if (b.code == -2) nativeSetAxis(5, (short) (down ? 32767 : -32768));
        else if (b.code >= 0) nativeSetButton(b.code, down);
    }

    private void updateStick(Stick s, float x, float y) {
        float dx = x - s.cx, dy = y - s.cy;
        float dist = (float) Math.hypot(dx, dy);
        if (dist > s.r) { dx = dx / dist * s.r; dy = dy / dist * s.r; }
        s.kx = s.cx + dx; s.ky = s.cy + dy;
        nativeSetAxis(s.axisX, (short) (dx / s.r * 32767f));
        nativeSetAxis(s.axisY, (short) (dy / s.r * 32767f));
    }

    private void resetStick(Stick s) {
        s.pointerId = -1; s.kx = s.cx; s.ky = s.cy;
        nativeSetAxis(s.axisX, (short) 0);
        nativeSetAxis(s.axisY, (short) 0);
    }

    private void resetCameraTouch() {
        camPointerId = -1;
        camAccumDx = 0f;
        camAccumDy = 0f;
        camSmoothX = 0f;
        camSmoothY = 0f;
        removeCallbacks(camTicker);
        removeCallbacks(bootTicker);
        nativeSetAxis(2, (short) 0);
        nativeSetAxis(3, (short) 0);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!bootComplete) return true;
        nativeEnsureGamepad();
        int action = ev.getActionMasked();
        int idx = ev.getActionIndex();
        int pid = ev.getPointerId(idx);

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = ev.getX(idx), y = ev.getY(idx);
            for (Btn b : buttons) {
                if (hidden && b.code != -99) continue;
                if (Math.hypot(x - b.cx, y - b.cy) <= b.r * 1.22f) {
                    if (b.code == -99) {
                        hidden = !hidden;
                        if (hidden) {
                            resetStick(leftStick);
                            resetCameraTouch();
                        }
                        invalidate();
                        return true;
                    }
                    activeBtns.put(pid, b);
                    sendBtn(b, true);
                    invalidate();
                    return true;
                }
            }
            if (!hidden) {
                if (leftStick.pointerId == -1 && Math.hypot(x - leftStick.cx, y - leftStick.cy) <= leftStick.r * 1.45f) {
                    leftStick.pointerId = pid;
                    updateStick(leftStick, x, y);
                    invalidate();
                    return true;
                }
                // Any open touch on middle/right screen drives camera smoothly
                if (camPointerId == -1 && x >= getWidth() * 0.28f) {
                    camPointerId = pid;
                    camLastX = x; camLastY = y;
                    camCurX = x;  camCurY = y;
                    camAccumDx = 0f; camAccumDy = 0f;
                    removeCallbacks(camTicker);
        removeCallbacks(bootTicker);
                    postDelayed(camTicker, 16);
                    invalidate();
                    return true;
                }
            }
            return false;
        } else if (action == MotionEvent.ACTION_MOVE) {
            boolean handled = false;
            for (int i = 0; i < ev.getPointerCount(); i++) {
                int id = ev.getPointerId(i);
                if (id == leftStick.pointerId) {
                    updateStick(leftStick, ev.getX(i), ev.getY(i));
                    handled = true;
                } else if (id == camPointerId) {
                    float nx = ev.getX(i), ny = ev.getY(i);
                    camAccumDx += (nx - camLastX);
                    camAccumDy += (ny - camLastY);
                    camLastX = nx; camLastY = ny;
                    camCurX = nx;  camCurY = ny;
                    handled = true;
                }
            }
            if (handled) invalidate();
            return handled;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            Btn b = activeBtns.get(pid);
            if (b != null) { sendBtn(b, false); activeBtns.remove(pid); invalidate(); return true; }
            if (pid == leftStick.pointerId) { resetStick(leftStick); invalidate(); return true; }
            if (pid == camPointerId) { resetCameraTouch(); invalidate(); return true; }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < activeBtns.size(); i++) {
                sendBtn(activeBtns.valueAt(i), false);
            }
            activeBtns.clear();
            resetStick(leftStick);
            resetCameraTouch();
            invalidate();
            return true;
        }
        return false;
    }
}