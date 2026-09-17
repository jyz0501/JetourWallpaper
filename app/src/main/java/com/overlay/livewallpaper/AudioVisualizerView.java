package com.overlay.livewallpaper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;


public class AudioVisualizerView extends View {

    public static final int STYLE_RAINBOW   = 0;
    public static final int STYLE_SINGLE    = 1;
    public static final int STYLE_DUAL      = 2;
    public static final int STYLE_AURORA    = 3;
    public static final int STYLE_NEON      = 4;
    public static final int STYLE_SPECTRUM  = 5;
    public static final int STYLE_CYBER     = 6;
    public static final int STYLE_COUNT     = 7;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Matrix shaderMatrix = new Matrix();

    private float baseFlowWidth = 12f;      
    private float flowWidth = 12f;          
    private float sensitivity = 2.5f;
    private boolean enabled = true;
    private int style = STYLE_RAINBOW;
    private float brightnessFactor = 1.0f;  

    private float audioLevel = 0f;          
    private float audioIntensity = 0f;      
    private float audioHue = 0f;            

    private long lastFrameTs = 0L;
    private float flowPhase = 0f;     
    private boolean sizeLogged = false;

    public AudioVisualizerView(Context ctx) {
        super(ctx);
        init();
    }

    public AudioVisualizerView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.MITER);   
        paint.setStrokeCap(Paint.Cap.BUTT);        
        paint.setStrokeMiter(4f);
        paint.setStrokeWidth(flowWidth);

        setClickable(false);
        setFocusable(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void setFlowWidth(float px) {
        this.baseFlowWidth = Math.max(2f, px);
        this.flowWidth = this.baseFlowWidth;
        paint.setStrokeWidth(this.flowWidth);
        invalidate();
    }

    public void setSensitivity(float s) {
        this.sensitivity = Math.max(0.1f, s);
    }

    public void setEnabled2(boolean e) {
        this.enabled = e;
        invalidate();
    }

    public void setStyle(int s) {
        if (s < 0 || s >= STYLE_COUNT) s = 0;
        this.style = s;
        invalidate();
    }

    
    public void setBrightness(float f) {
        this.brightnessFactor = Math.max(0.1f, Math.min(2.0f, f));
        invalidate();
    }

    
    public void onAudioLevel(float level) {
        if (!enabled) return;
        float scaled = Math.min(1f, level * sensitivity);
        audioLevel = audioLevel * 0.6f + scaled * 0.4f;
        postInvalidate(); 
    }

    
    public void setIntensity(float intensity) {
        if (!enabled) return;
        float it = intensity;
        if (it < 0f) it = 0f;
        if (it > 1f) it = 1f;
        audioIntensity = audioIntensity * 0.6f + it * 0.4f;
        postInvalidate();
    }

    
    public void setHue(float hueDeg) {
        if (!enabled) return;
        float h = hueDeg;
        while (h < 0f) h += 360f;
        while (h >= 360f) h -= 360f;
        float diff = h - audioHue;
        while (diff > 180f) diff -= 360f;
        while (diff < -180f) diff += 360f;
        audioHue = audioHue + diff * 0.4f;
        if (audioHue < 0f) audioHue += 360f;
        if (audioHue >= 360f) audioHue -= 360f;
        postInvalidate();
    }

    
    public void onAudioData(float intensity, float hueDeg) {
        if (!enabled) return;
        
        float it = intensity;
        if (it < 0f) it = 0f;
        if (it > 1f) it = 1f;
        audioIntensity = audioIntensity * 0.6f + it * 0.4f;
        float h = hueDeg;
        while (h < 0f) h += 360f;
        while (h >= 360f) h -= 360f;
        float diff = h - audioHue;
        while (diff > 180f) diff -= 360f;
        while (diff < -180f) diff += 360f;
        audioHue = audioHue + diff * 0.4f;
        if (audioHue < 0f) audioHue += 360f;
        if (audioHue >= 360f) audioHue -= 360f;
        postInvalidate(); 
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!enabled) return;

        long now = System.currentTimeMillis();
        float dt = lastFrameTs == 0 ? 16f : Math.min(60f, now - lastFrameTs);
        lastFrameTs = now;

        
        flowPhase = (flowPhase + dt / 6000f) % 1f;

        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (!sizeLogged) {
            android.util.Log.i("AudioVisualizerView",
                    "onDraw size=" + w + "x" + h + " style=" + style);
            sizeLogged = true;
        }

        float breath = 0.5f + 0.5f * (float) Math.sin(now / 1600.0);

        flowWidth = baseFlowWidth;
        paint.setStrokeWidth(flowWidth);

        float inset = flowWidth / 2f;
        float left = inset, top = inset, right = w - inset, bottom = h - inset;

        path.reset();
        path.addRect(left, top, right, bottom, Path.Direction.CW);

        final float brightness;
        
        float base = breath * (0.55f + 0.45f * audioLevel) + audioLevel * 0.35f;
        brightness = Math.min(1f, Math.max(0.05f, base * brightnessFactor));
        final boolean needContinuousRepaint = true;

        float rotateDeg = flowPhase * 360f;
        Shader shader = buildShader(style, w, h, rotateDeg, now);
        int alpha = (int) (255 * brightness);

        paint.setShader(shader);
        paint.setAlpha(alpha);
        if (style == STYLE_SINGLE) {
            paint.setShader(null);
            paint.setColor(Color.parseColor("#4FB6FF"));
        }
        canvas.drawPath(path, paint);
        paint.setShader(null);
        paint.setColor(0);

        if (needContinuousRepaint) {
            invalidate();
        }
    }

    
    private Shader buildShader(int style, float w, float h, float rotateDeg, long nowMs) {
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = (float) Math.hypot(cx, cy);

        switch (style) {
            case STYLE_SINGLE:
                
                return null;

            case STYLE_DUAL: {
                int[] colors = new int[]{
                        Color.parseColor("#6C8BFF"),
                        Color.parseColor("#B66CFF"),
                        Color.parseColor("#6C8BFF")
                };
                float[] positions = new float[]{0f, 0.5f, 1f};
                LinearGradient lg = new LinearGradient(0, 0, w, 0, colors, positions,
                        Shader.TileMode.REPEAT);
                shaderMatrix.reset();
                shaderMatrix.setTranslate(flowPhase * w, 0);
                lg.setLocalMatrix(shaderMatrix);
                return lg;
            }

            case STYLE_AURORA: {
                int[] colors = new int[]{
                        Color.parseColor("#2AE38C"),
                        Color.parseColor("#3BD4D4"),
                        Color.parseColor("#5E6BFF"),
                        Color.parseColor("#A861D8"),
                        Color.parseColor("#2AE38C")
                };
                float[] positions = new float[]{0f, 0.25f, 0.5f, 0.75f, 1f};
                SweepGradient sg = new SweepGradient(cx, cy, colors, positions);
                shaderMatrix.reset();
                shaderMatrix.postRotate(rotateDeg * 0.5f, cx, cy);
                sg.setLocalMatrix(shaderMatrix);
                return sg;
            }

            case STYLE_NEON: {
                int[] colors = new int[]{
                        Color.parseColor("#FF2DB3"),
                        Color.parseColor("#00F6FF"),
                        Color.parseColor("#FF2DB3")
                };
                float[] positions = new float[]{0f, 0.5f, 1f};
                LinearGradient lg = new LinearGradient(0, 0, w, 0, colors, positions,
                        Shader.TileMode.MIRROR);
                shaderMatrix.reset();
                shaderMatrix.setTranslate(((flowPhase * 2f) % 1f) * w, 0);
                lg.setLocalMatrix(shaderMatrix);
                return lg;
            }

            case STYLE_SPECTRUM:
                
                return buildShader(STYLE_RAINBOW, w, h, rotateDeg, nowMs);

            case STYLE_CYBER: {
                int[] colors = new int[]{
                        Color.parseColor("#FF26A0"),
                        Color.parseColor("#00F0B0"),
                        Color.parseColor("#FFE410"),
                        Color.parseColor("#001030"),
                        Color.parseColor("#FF26A0")
                };
                float[] positions = new float[]{0f, 0.25f, 0.5f, 0.75f, 1f};
                float diag = (float) Math.hypot(w, h);
                LinearGradient lg = new LinearGradient(0, 0, diag, diag, colors, positions,
                        Shader.TileMode.REPEAT);
                shaderMatrix.reset();
                shaderMatrix.postRotate(-45f, cx, cy);
                shaderMatrix.postTranslate(flowPhase * diag, flowPhase * diag);
                lg.setLocalMatrix(shaderMatrix);
                return lg;
            }

            case STYLE_RAINBOW:
            default: {
                int[] colors = new int[]{
                        Color.RED, Color.MAGENTA, Color.BLUE,
                        Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED
                };
                float[] positions = new float[]{0f, 0.16f, 0.33f, 0.5f, 0.66f, 0.83f, 1f};
                SweepGradient sg = new SweepGradient(cx, cy, colors, positions);
                shaderMatrix.reset();
                shaderMatrix.postRotate(rotateDeg, cx, cy);
                sg.setLocalMatrix(shaderMatrix);
                return sg;
            }
        }
    }
}
