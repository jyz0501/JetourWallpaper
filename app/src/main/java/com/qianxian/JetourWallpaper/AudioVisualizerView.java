package com.qianxian.JetourWallpaper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.util.AttributeSet;
import android.view.View;

/**
 * 光随声动 / 音乐律动渲染层（Chroma 风格）。
 *
 * 把屏幕四周当作一条闭合灯带，按「对数频段」实时点亮：
 *   - 底边中心为低频（鼓点），越往上走频率越高，左右对称；
 *   - 每个分段独立取色、独立亮度，鼓点命中时整条灯带瞬间爆闪；
 *   - 无信号时进入呼吸待机，不会全黑。
 *
 * STYLE_FLOWING 为纯 LED 灯带「流光」：一颗高亮光头沿灯带跑动并拖出渐隐尾巴，
 * 整条连续光带（不分段、不留灯珠暗缝），速度/亮度随音乐快慢呼吸。
 *
 * STYLE_BLOOM 为「音乐泛光」：多层半透明描边叠出柔光晕，再沿边框持续喷出
 * 细碎颗粒粒子（关闭抗锯齿，保留硬边颗粒感），音量越大喷得越密、鼓点时炸开一片。
 */
public class AudioVisualizerView extends View {

    public static final int STYLE_SPECTRUM = 0;
    public static final int STYLE_RAINBOW  = 1;
    public static final int STYLE_SINGLE   = 2;
    public static final int STYLE_DUAL     = 3;
    public static final int STYLE_AURORA   = 4;
    public static final int STYLE_NEON     = 5;
    public static final int STYLE_CYBER    = 6;
    public static final int STYLE_FLOWING  = 7;
    public static final int STYLE_BLOOM    = 8;
    public static final int STYLE_COUNT    = 9;

    /** 灯带分段数，越多越顺滑。 */
    private static final int SEGMENTS = 72;
    /** 流光：同时跑动的光头数量（等间距分布）。 */
    private static final int FLOW_HEADS = 2;
    /** 流光：绘制时每段再细分的子段数，用来消除分段量化造成的阶梯/节点感。 */
    private static final int FLOW_SUBDIV = 6;
    /** 泛光：粒子池上限。 */
    private static final int PARTICLE_MAX = 150;
    /** 目标渲染帧率，省电且足够跟手。 */
    private static final long FRAME_MS = 33L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 粒子画笔：特意不开抗锯齿，小点才够「颗粒」。 */
    private final Paint dotPaint = new Paint();
    private final Path outline = new Path();
    private PathMeasure measure;

    private float[] px = new float[SEGMENTS + 1];
    private float[] py = new float[SEGMENTS + 1];
    private final float[] segValue = new float[SEGMENTS];
    private final float[] segSmooth = new float[SEGMENTS];
    private final float[] segHue = new float[SEGMENTS];
    private final float[] hsvScratch = new float[3];

    // 泛光粒子池：沿周长位置 / 径向偏移 / 径向速度 / 剩余寿命 / 衰减 / 半径 / 色相
    private final float[] parP = new float[PARTICLE_MAX];
    private final float[] parOff = new float[PARTICLE_MAX];
    private final float[] parVel = new float[PARTICLE_MAX];
    private final float[] parLife = new float[PARTICLE_MAX];
    private final float[] parDecay = new float[PARTICLE_MAX];
    private final float[] parSize = new float[PARTICLE_MAX];
    private final float[] parHue = new float[PARTICLE_MAX];
    private int parCount = 0;
    private float spawnAcc = 0f;

    private float baseFlowWidth = 12f;
    private float sensitivity = 2.5f;
    private boolean enabled = true;
    private int style = STYLE_SPECTRUM;
    private float brightnessFactor = 1.0f;
    /** 流光跑动速度，单位：圈/秒。 */
    private float flowSpeed = 0.5f;

    // 音频特征
    private volatile float aLevel = 0f;
    private volatile float aBeat = 0f;
    private volatile float aHue = 210f;
    private volatile float aCentroid = 0.4f;
    private volatile float[] aBands = new float[AudioAnalyzer.BANDS];

    private long lastFrameTs = 0L;
    private long lastDataTs = 0L;
    private float idlePhase = 0f;
    private float flowPhase = 0f;
    /** 流光光头在周长上的归一化位置 0..1。 */
    private float headPhase = 0f;
    /** 当前帧的音量/鼓点，供绘制时解析求值使用。 */
    private float drawLevel = 0f;
    private float drawBeat = 0f;

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
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        dotPaint.setStyle(Paint.Style.FILL);
        setClickable(false);
        setFocusable(false);
    }

    // ==================== 对外接口 ====================

    public void setFlowWidth(float pxValue) {
        this.baseFlowWidth = Math.max(2f, pxValue);
        rebuildOutline(getWidth(), getHeight());
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
        if (s < 0 || s >= STYLE_COUNT) s = STYLE_SPECTRUM;
        this.style = s;
        parCount = 0;
        spawnAcc = 0f;
        rebuildOutline(getWidth(), getHeight());
        invalidate();
    }

    public void setBrightness(float f) {
        this.brightnessFactor = Math.max(0.1f, Math.min(2.0f, f));
        invalidate();
    }

    /** 设置流光跑动速度（圈/秒，0.05 ~ 2.0）。 */
    public void setFlowSpeed(float lapsPerSec) {
        this.flowSpeed = Math.max(0.02f, Math.min(3.0f, lapsPerSec));
        invalidate();
    }

    /** 接收一帧完整音频特征（可能在非 UI 线程）。 */
    public void onFrame(AudioFrame f) {
        if (!enabled || f == null) return;
        aLevel = f.level;
        aBeat = f.beat;
        aHue = f.hue;
        aCentroid = f.centroid;
        aBands = f.bands;
        lastDataTs = System.currentTimeMillis();
        postInvalidate();
    }

    // ==================== 几何 ====================

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildOutline(w, h);
    }

    private void rebuildOutline(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float half = baseFlowWidth / 2f;
        // 泛光要铺很宽的光晕，内缩得更多才不会被屏幕边缘切出硬边
        float glowPad = baseFlowWidth * (style == STYLE_BLOOM ? 3.6f : 1.6f);
        float inset = half + glowPad;

        // 直角灯带：四个角 90° 硬拐弯，不做圆角（真灯带贴边就是这个形状）
        float L = inset, T = inset, R = w - inset, B = h - inset;
        outline.reset();
        outline.moveTo(w / 2f, B);
        outline.lineTo(L, B);
        outline.lineTo(L, T);
        outline.lineTo(w / 2f, T);
        outline.lineTo(R, T);
        outline.lineTo(R, B);
        outline.lineTo(w / 2f, B);
        outline.close();
        measure = new PathMeasure(outline, true);

        buildSegmentPoints(w, L, T, R, B);
    }

    /**
     * 沿 6 条直边分配采样点：4 个直角必须正好落在采样点上，
     * 否则拐角会被「角前一点 → 角后一点」的连线斜切掉。
     * 周长 0% = 底边中心（低频/鼓点），50% = 顶边中心（高频），左右严格对称。
     */
    private void buildSegmentPoints(float w, float L, float T, float R, float B) {
        float[] vx = {w / 2f, L, L, w / 2f, R, R, w / 2f};
        float[] vy = {B, B, T, T, T, B, B};
        final int edges = 6;

        float[] len = new float[edges];
        float total = 0f;
        for (int k = 0; k < edges; k++) {
            len[k] = Math.abs(vx[k + 1] - vx[k]) + Math.abs(vy[k + 1] - vy[k]);
            total += len[k];
        }
        if (total <= 0f) return;

        // 按边长比例分配段数（最大余数法），每条边至少 1 段
        int[] cnt = new int[edges];
        float[] frac = new float[edges];
        int used = 0;
        for (int k = 0; k < edges; k++) {
            float exact = SEGMENTS * len[k] / total;
            int floor = (int) exact;
            cnt[k] = Math.max(1, floor);
            frac[k] = exact - floor;
            used += cnt[k];
        }
        while (used > SEGMENTS) {
            int big = 0;
            for (int k = 1; k < edges; k++) if (cnt[k] > cnt[big]) big = k;
            if (cnt[big] <= 1) break;
            cnt[big]--;
            used--;
        }
        while (used < SEGMENTS) {
            int best = 0;
            for (int k = 1; k < edges; k++) if (frac[k] > frac[best]) best = k;
            if (frac[best] < 0f) break;
            cnt[best]++;
            frac[best] = -1f;
            used++;
        }

        int idx = 0;
        for (int k = 0; k < edges; k++) {
            float dx = vx[k + 1] - vx[k];
            float dy = vy[k + 1] - vy[k];
            for (int j = 0; j < cnt[k] && idx < SEGMENTS; j++) {
                float t = j / (float) cnt[k];
                px[idx] = vx[k] + dx * t;
                py[idx] = vy[k] + dy * t;
                idx++;
            }
        }
        px[SEGMENTS] = vx[edges];
        py[SEGMENTS] = vy[edges];
    }

    // ==================== 绘制 ====================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!enabled) return;

        long now = System.currentTimeMillis();
        if (lastFrameTs != 0 && now - lastFrameTs < FRAME_MS) {
            // 限帧：只排下一帧，不重复绘制
            postInvalidateDelayed(FRAME_MS - (now - lastFrameTs));
            return;
        }
        float dt = lastFrameTs == 0 ? 33f : Math.min(120f, now - lastFrameTs);
        lastFrameTs = now;

        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0 || measure == null) return;

        boolean idle = (now - lastDataTs) > 1200L || aLevel < 0.015f;
        idlePhase = (idlePhase + dt / 2200f) % 1f;
        flowPhase = (flowPhase + dt / 5200f * (1f + aLevel * 2f)) % 1f;

        boolean flowing = (style == STYLE_FLOWING);
        boolean bloom = (style == STYLE_BLOOM);
        float level = idle ? 0f : aLevel;
        float beat = idle ? 0f : aBeat;
        drawLevel = level;
        drawBeat = beat;

        if (flowing) {
            // 光头推进：音量越大跑得越快，鼓点时再窜一截
            float lapsPerSec = flowSpeed * (1f + level * 1.6f + beat * 1.8f);
            float lapMs = 1000f / Math.max(0.02f, lapsPerSec);
            headPhase = (headPhase + dt / lapMs) % 1f;
        }

        float gain = sensitivity;
        float beatBoost = aBeat * 0.55f;

        for (int i = 0; i < SEGMENTS; i++) {
            // 沿周长取 0..1 的归一化位置，底边中心为起点
            float p = i / (float) SEGMENTS;
            // 镜像映射：p=0/1 → m=0（低频），p=0.5 → m=1（高频），左右对称
            float m = 1f - Math.abs(2f * p - 1f);
            float bf = m * (AudioAnalyzer.BANDS - 1);
            int bi = (int) bf;
            int bi2 = Math.min(AudioAnalyzer.BANDS - 1, bi + 1);
            float frac = bf - bi;
            float bv = aBands[bi] * (1f - frac) + aBands[bi2] * frac;

            float v;
            if (flowing) {
                // LED 流光：光头 + 指数渐隐拖尾，无音频也照常跑
                v = flowingValue(p);
            } else if (idle) {
                v = 0.16f + 0.14f * (float) Math.sin((idlePhase - m * 0.55f) * Math.PI * 2.0);
            } else {
                v = bv * gain * (0.55f + 0.65f * aLevel);
            }
            if (!flowing) {
                v += beatBoost * (0.45f + 0.55f * (1f - m)); // 鼓点低频端更亮
            }
            v = Math.max(0.04f, Math.min(1f, v * brightnessFactor));

            float prev = segSmooth[i];
            // 泛光要的是「呼吸」而不是「跳变」，故上冲慢、回落更慢
            float atk = flowing ? 0.72f : (bloom ? 0.30f : 0.55f);
            float rel = flowing ? 0.42f : (bloom ? 0.10f : 0.28f);
            segSmooth[i] = v > prev ? prev * (1f - atk) + v * atk : prev * (1f - rel) + v * rel;
            segValue[i] = segSmooth[i];
            segHue[i] = hueFor(style, m, p);
        }

        // 三层描边叠加出光晕：外发光 → 中层 → 亮芯
        if (bloom) {
            // 泛光：由外到内 5 层半透明描边，模拟高斯光晕
            drawStrip(canvas, baseFlowWidth * 7.0f, 0.05f, 1);
            drawStrip(canvas, baseFlowWidth * 4.6f, 0.08f, 1);
            drawStrip(canvas, baseFlowWidth * 2.9f, 0.14f, 1);
            drawStrip(canvas, baseFlowWidth * 1.7f, 0.30f, 1);
            drawStrip(canvas, baseFlowWidth, 0.85f, 1);
        } else {
            // 流光走细分解析采样，光带连续无节点；越靠亮芯分得越细
            drawStrip(canvas, baseFlowWidth * 2.6f, 0.16f, flowing ? 2 : 1);
            drawStrip(canvas, baseFlowWidth * 1.5f, 0.34f, flowing ? 4 : 1);
            drawStrip(canvas, baseFlowWidth, 1.0f, flowing ? FLOW_SUBDIV : 1);
        }

        if (bloom) {
            updateParticles(dt, level, beat);
            drawParticles(canvas, level, beat);
        }

        postInvalidateDelayed(FRAME_MS);
    }

    // ==================== 泛光粒子 ====================

    /** 推进粒子寿命，并按音量/鼓点补充新粒子。 */
    private void updateParticles(float dt, float level, float beat) {
        float sec = dt / 1000f;
        for (int i = parCount - 1; i >= 0; i--) {
            parOff[i] += parVel[i] * sec;
            parVel[i] *= (1f - 1.6f * sec);         // 空气阻力，越飘越慢
            parLife[i] -= parDecay[i] * sec;
            if (parLife[i] <= 0f) {
                int last = --parCount;
                if (i != last) {
                    parP[i] = parP[last];
                    parOff[i] = parOff[last];
                    parVel[i] = parVel[last];
                    parLife[i] = parLife[last];
                    parDecay[i] = parDecay[last];
                    parSize[i] = parSize[last];
                    parHue[i] = parHue[last];
                }
            }
        }

        // 每秒喷出数：安静时几点余烬，音量上去后成片，鼓点瞬间炸开
        float rate = 6f + 110f * level + 150f * beat;
        spawnAcc += rate * sec;
        int n = (int) spawnAcc;
        if (n > 0) {
            spawnAcc -= n;
            spawnParticles(Math.min(n, 20), level, beat);
        }
    }

    /** 从灯带上「亮的地方」 preferentially 喷出粒子。 */
    private void spawnParticles(int n, float level, float beat) {
        for (int k = 0; k < n && parCount < PARTICLE_MAX; k++) {
            // 拒绝采样：越亮的灯带段越容易成为粒子源
            float p = 0f;
            float local = 0f;
            for (int t = 0; t < 3; t++) {
                p = (float) Math.random();
                int idx = (int) (p * SEGMENTS);
                if (idx >= SEGMENTS) idx = SEGMENTS - 1;
                local = segValue[idx];
                if (Math.random() < 0.25f + local * 1.5f) break;
            }
            int i = parCount++;
            parP[i] = p;
            // 起始略微骑在灯带上，再向外飘
            parOff[i] = (float) (Math.random() * 2f - 1f) * baseFlowWidth * 0.5f;
            float dir = Math.random() < 0.25f ? -1f : 1f;   // 少量往屏幕内侧飘
            parVel[i] = dir * baseFlowWidth
                    * (0.9f + 2.6f * level + 3.4f * beat)
                    * (0.5f + (float) Math.random());
            parLife[i] = 1f;
            parDecay[i] = 0.55f + 1.05f * (float) Math.random();
            parSize[i] = baseFlowWidth * (0.09f + 0.26f * (float) Math.random());
            int idx = (int) (p * SEGMENTS);
            if (idx >= SEGMENTS) idx = SEGMENTS - 1;
            parHue[i] = (segHue[idx] + (float) (Math.random() * 40f - 20f) + 360f) % 360f;
        }
    }

    /** 两遍绘制：先铺一层大颗低透明度的柔光，再点一层硬边细颗粒。 */
    private void drawParticles(Canvas canvas, float level, float beat) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float energy = Math.min(1f, 0.35f + 0.75f * level + 0.5f * beat);

        for (int pass = 0; pass < 2; pass++) {
            float sizeMul = (pass == 0) ? 3.2f : 1.0f;
            float alphaMul = (pass == 0) ? 0.16f : 0.85f;
            for (int i = 0; i < parCount; i++) {
                float p = parP[i];
                float fi = p * SEGMENTS;
                int i0 = (int) fi;
                if (i0 >= SEGMENTS) i0 = SEGMENTS - 1;
                float f = fi - i0;
                float sx = px[i0] + (px[i0 + 1] - px[i0]) * f;
                float sy = py[i0] + (py[i0 + 1] - py[i0]) * f;

                float nx = sx - cx;
                float ny = sy - cy;
                float nl = (float) Math.sqrt(nx * nx + ny * ny);
                if (nl > 0.001f) { nx /= nl; ny /= nl; }

                float off = parOff[i];
                float life = parLife[i];
                int color = colorOf(parHue[i], Math.min(1f, 0.5f + 0.5f * life), STYLE_BLOOM);
                int alpha = (int) (255 * alphaMul * life * life * energy);
                if (alpha <= 2) continue;
                dotPaint.setColor(color);
                dotPaint.setAlpha(alpha);
                float r = parSize[i] * sizeMul * (0.45f + 0.55f * life);
                canvas.drawCircle(sx + nx * off, sy + ny * off, r, dotPaint);
            }
        }
    }

    /**
     * 流光分段亮度：光头过曝白芯 + 指数拖尾 + 常亮底光（真灯带不会全黑）。
     */
    private float flowingValue(float p) {
        float level = drawLevel;
        float beat = drawBeat;
        // 到「最近一个光头」的距离：0 = 正好在光头上，越大越靠后
        float d = headPhase - p;
        d -= (float) Math.floor(d);
        d = (d * FLOW_HEADS) % 1f / FLOW_HEADS;

        float tail = 0.115f + 0.085f * aCentroid + 0.10f * level;
        float comet = (float) Math.exp(-d / tail);
        float core = (float) Math.exp(-d / (tail * 0.30f));
        float drive = 0.55f + 0.75f * level + 0.60f * beat;
        return 0.12f + comet * drive * 0.85f + core * (0.35f + 0.55f * beat);
    }

    /**
     * 沿灯带描边。sub > 1 时把每段再切成 sub 个子段连续相接（不留缝）：
     *   - 流光：按子段所在周长位置「解析求值」，不受 SEGMENTS 量化限制，光带连续无节点；
     *   - 其余样式：在相邻两段的亮度/色相之间线性插值。
     */
    private void drawStrip(Canvas canvas, float width, float alphaMul, int sub) {
        paint.setStrokeWidth(width);
        boolean flowing = (style == STYLE_FLOWING);
        int n = Math.max(1, sub);
        for (int i = 0; i < SEGMENTS; i++) {
            int ni = (i + 1) % SEGMENTS;
            float v0 = segValue[i];
            float h0 = segHue[i];
            float ox = px[i];
            float oy = py[i];
            for (int s = 1; s <= n; s++) {
                float t = s / (float) n;
                float x = px[i] + (px[i + 1] - px[i]) * t;
                float y = py[i] + (py[i + 1] - py[i]) * t;
                float v, hue;
                if (flowing) {
                    float p = (i + t) / (float) SEGMENTS;
                    float m = 1f - Math.abs(2f * p - 1f);
                    v = Math.max(0.04f, Math.min(1f, flowingValue(p) * brightnessFactor));
                    hue = hueFor(style, m, p);
                } else if (n == 1) {
                    v = v0;
                    hue = h0;
                } else {
                    v = v0 + (segValue[ni] - v0) * t;
                    hue = h0 + hueDelta(h0, segHue[ni]) * t;
                }
                drawSeg(canvas, ox, oy, x, y, v, hue, alphaMul);
                ox = x;
                oy = y;
            }
        }
    }

    private void drawSeg(Canvas canvas, float x0, float y0, float x1, float y1,
                         float v, float hue, float alphaMul) {
        int alpha = (int) (255 * Math.min(1f, v * alphaMul * 1.15f));
        if (alpha <= 2) return;
        paint.setColor(colorOf(hue, v, style));
        paint.setAlpha(alpha);
        canvas.drawLine(x0, y0, x1, y1, paint);
    }

    /** 色相最短路径差值，避免跨 0° 插值时整圈翻转。 */
    private static float hueDelta(float from, float to) {
        float d = (to - from) % 360f;
        if (d > 180f) d -= 360f;
        else if (d < -180f) d += 360f;
        return d;
    }

    private float hueFor(int style, float m, float p) {
        switch (style) {
            case STYLE_SPECTRUM:
                // 低频→高频铺开 300° 色相，最直观的"频谱灯带"
                return (aHue + m * 300f) % 360f;
            case STYLE_RAINBOW:
                return (flowPhase * 360f + m * 180f) % 360f;
            case STYLE_SINGLE:
                return aHue;
            case STYLE_DUAL:
                return (aHue + (m > 0.5f ? 60f : 0f)) % 360f;
            case STYLE_AURORA:
                return (150f + m * 130f + flowPhase * 40f) % 360f;
            case STYLE_NEON:
                return (m > 0.5f ? 320f : 185f) % 360f;
            case STYLE_CYBER:
                return (aHue + m * 220f + flowPhase * 90f) % 360f;
            case STYLE_FLOWING:
                // 整条灯带铺一段彩虹，光头跑过时颜色跟着流动
                return (aHue + p * 300f + flowPhase * 60f) % 360f;
            case STYLE_BLOOM:
                // 泛光：色相收窄一些，避免光晕混成脏灰
                return (aHue + m * 210f + flowPhase * 70f) % 360f;
            default:
                return aHue;
        }
    }

    private int colorOf(float hueDeg, float v, int style) {
        float sat;
        switch (style) {
            case STYLE_SINGLE:
                sat = 0.55f;
                break;
            case STYLE_NEON:
                sat = 1.0f;
                break;
            case STYLE_AURORA:
                sat = 0.72f + 0.2f * aCentroid;
                break;
            case STYLE_FLOWING:
                // 灯珠本色鲜艳，光头处（v 接近 1）去饱和成炽白高光
                sat = 0.92f - 0.5f * Math.max(0f, (v - 0.72f) / 0.28f);
                break;
            case STYLE_BLOOM:
                // 泛光要柔，饱和度压低更像真实溢光
                sat = 0.55f + 0.35f * Math.min(1f, v + aBeat * 0.4f);
                break;
            default:
                // 声音越亮饱和度越高，安静时偏白柔光
                sat = 0.45f + 0.5f * Math.min(1f, v + aBeat * 0.4f);
                break;
        }
        if (sat > 1f) sat = 1f;
        hsvScratch[0] = hueDeg;
        hsvScratch[1] = sat;
        hsvScratch[2] = 1f;
        return Color.HSVToColor(hsvScratch);
    }
}
