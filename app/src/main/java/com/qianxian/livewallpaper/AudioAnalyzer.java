package com.qianxian.livewallpaper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;
import android.util.Log;

/**
 * Chroma 级音频律动分析引擎。
 *
 * 两条采集链路，自动切换：
 *  1. Visualizer(session 0)：直接抓全局音频输出，零延迟、无环境噪声。
 *     仅在 system uid + 平台签名（车机版）下可用。
 *  2. AudioRecord(MIC)：Android 14 起三方 App 无法使用 session 0，
 *     手机版自动回退到麦克风采集，外放/环境声同样能驱动灯光。
 *
 * 信号处理（两条链路共用）：
 *  - 对数分频：FFT 拆成 16 个频段，低频窄、高频宽，符合听感；
 *  - 统计节拍检测：瞬时能量 > 局部均值 × C + 方差修正，190ms 去抖，并估算 BPM；
 *  - AGC 自适应增益：跟踪近期峰值，小音量与安静片段也能驱动灯光；
 *  - 色相由低频占比、高频占比、频谱重心共同决定；
 *  - 一次回调输出完整 AudioFrame，保证亮度/颜色/节拍严格同步。
 */
public class AudioAnalyzer {

    private static final String TAG = "AudioAnalyzer";

    private static volatile AudioAnalyzer INSTANCE;

    public static AudioAnalyzer get() {
        if (INSTANCE == null) {
            synchronized (AudioAnalyzer.class) {
                if (INSTANCE == null) INSTANCE = new AudioAnalyzer();
            }
        }
        return INSTANCE;
    }

    /** 频谱柱数量。 */
    public static final int BANDS = 16;

    private static final int HISTORY = 32;
    private static final float BEAT_C = 1.42f;
    private static final float BEAT_VAR_K = 0.020f;
    private static final long BEAT_MIN_GAP_MS = 190L;

    // ---- 麦克风采集参数 ----
    private static final int MIC_RATE = 44100;
    private static final int MIC_FFT = 1024;

    public interface Listener {
        void onFrame(AudioFrame frame);
    }

    private Visualizer visualizer;
    private volatile Listener listener;
    private Context ctx;
    private volatile Thread micThread;
    private volatile boolean running = false;
    private volatile long lastDataMs = 0L;

    private final float[] bands = new float[BANDS];
    private volatile float level;
    private volatile float bass;
    private volatile float mid;
    private volatile float treble;
    private volatile float centroid = 0.4f;

    private final float[] energyHistory = new float[HISTORY];
    private int historyIdx = 0;
    private int historyFilled = 0;
    private long lastBeatMs = 0L;
    private volatile float beat = 0f;
    private volatile int bpm = 0;
    private final long[] beatTimes = new long[8];
    private int beatTimesIdx = 0;
    private int beatTimesFilled = 0;

    private float agcPeak = 0.18f;
    private volatile float hue = 210f;

    private long lastEmitMs = 0L;

    private AudioAnalyzer() { }

    public void setListener(Listener l) {
        this.listener = l;
    }

    // ==================== 生命周期 ====================

    public synchronized void start(Context context) {
        this.ctx = context == null ? ctx : context.getApplicationContext();
        if (running) return;
        running = true;

        boolean ok = startVisualizer();
        if (!ok) {
            Log.i(TAG, "Visualizer unavailable, fallback to mic");
            startMic();
            return;
        }
        // Visualizer 可能构造成功但永不回调（Android 14 对 session 0 的限制），
        // 用看门狗兜底切到麦克风
        Thread wd = new Thread(() -> {
            try { Thread.sleep(2000L); } catch (InterruptedException e) { return; }
            if (running && lastDataMs == 0L) {
                Log.i(TAG, "Visualizer produced no data, switching to mic");
                stopVisualizer();
                startMic();
            }
        }, "audio-watchdog");
        wd.setDaemon(true);
        wd.start();
    }

    public synchronized void stop() {
        running = false;
        stopVisualizer();
        stopMic();
    }

    // ==================== 采集链路 A：Visualizer ====================

    private boolean startVisualizer() {
        try {
            int[] range = Visualizer.getCaptureSizeRange();
            int captureSize = 1024;
            if (range != null && range.length >= 2) {
                captureSize = Math.min(range[1], Math.max(range[0], 1024));
            }
            Visualizer v = new Visualizer(0);
            try { v.setEnabled(false); } catch (Exception ignore) { }
            v.setCaptureSize(captureSize);
            v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer viz, byte[] wf, int rate) {
                    if (wf == null || wf.length == 0) return;
                    long sumSq = 0;
                    for (byte b : wf) {
                        int c = (b & 0xFF) - 128;
                        sumSq += (long) c * c;
                    }
                    double rms = Math.sqrt((double) sumSq / wf.length) / 128.0;
                    lastDataMs = SystemClock.uptimeMillis();
                    onLevelSample((float) rms);
                }

                @Override
                public void onFftDataCapture(Visualizer viz, byte[] fft, int rate) {
                    if (fft == null || fft.length < 4) return;
                    int n = fft.length / 2;
                    float[] mag = new float[n];
                    for (int k = 0; k < n; k++) {
                        int re = fft[2 * k];
                        int im = fft[2 * k + 1];
                        mag[k] = (float) Math.sqrt((double) re * re + (double) im * im);
                    }
                    lastDataMs = SystemClock.uptimeMillis();
                    processSpectrum(mag, n, true);
                }
            }, Visualizer.getMaxCaptureRate(), true, true);
            v.setEnabled(true);
            visualizer = v;
            Log.i(TAG, "Visualizer started captureSize=" + captureSize);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "Visualizer init failed: " + e);
            visualizer = null;
            return false;
        }
    }

    private void stopVisualizer() {
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
                visualizer.release();
            } catch (Throwable ignored) { }
            visualizer = null;
        }
    }

    // ==================== 采集链路 B：麦克风 ====================

    private boolean hasMicPermission() {
        return ctx != null
                && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private synchronized void startMic() {
        if (micThread != null) return;
        if (!hasMicPermission()) {
            Log.w(TAG, "Mic fallback skipped: RECORD_AUDIO not granted");
            return;
        }
        Thread t = new Thread(() -> {
            AudioRecord rec = null;
            try {
                int buf = AudioRecord.getMinBufferSize(MIC_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                buf = Math.max(buf, MIC_FFT * 4);
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, MIC_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "AudioRecord init failed");
                    return;
                }
                rec.startRecording();
                Log.i(TAG, "Mic capture started rate=" + MIC_RATE + " fft=" + MIC_FFT);

                short[] bufShort = new short[MIC_FFT];
                float[] re = new float[MIC_FFT];
                float[] im = new float[MIC_FFT];
                float[] mag = new float[MIC_FFT / 2];
                float[] window = new float[MIC_FFT];
                for (int i = 0; i < MIC_FFT; i++) {
                    window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (MIC_FFT - 1)));
                }

                while (running) {
                    int read = rec.read(bufShort, 0, MIC_FFT);
                    if (read <= 0) continue;

                    double sumSq = 0;
                    for (int i = 0; i < read; i++) {
                        float s = bufShort[i] / 32768f;
                        sumSq += (double) s * s;
                        float w = (i < MIC_FFT ? window[i] : 0f);
                        re[i] = s * w;
                        im[i] = 0f;
                    }
                    for (int i = read; i < MIC_FFT; i++) { re[i] = 0f; im[i] = 0f; }

                    double rms = Math.sqrt(sumSq / read);
                    lastDataMs = SystemClock.uptimeMillis();
                    onLevelSample((float) rms);

                    fft(re, im);
                    int n = MIC_FFT / 2;
                    for (int k = 0; k < n; k++) {
                        mag[k] = (float) Math.sqrt((double) re[k] * re[k] + (double) im[k] * im[k]);
                    }
                    processSpectrum(mag, n, false);
                }
            } catch (Throwable e) {
                Log.w(TAG, "Mic capture error: " + e);
            } finally {
                if (rec != null) {
                    try { rec.stop(); rec.release(); } catch (Throwable ignored) { }
                }
                Log.i(TAG, "Mic capture stopped");
            }
        }, "audio-mic");
        t.setDaemon(true);
        micThread = t;
        t.start();
    }

    private synchronized void stopMic() {
        Thread t = micThread;
        micThread = null;
        if (t != null) t.interrupt();
    }

    /** 原地迭代式 radix-2 FFT，长度必须为 2 的幂。 */
    private void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; j >= bit; bit >>= 1) j -= bit;
            j += bit;
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            float wr = (float) Math.cos(ang);
            float wi = (float) Math.sin(ang);
            int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                float cr = 1f, ci = 0f;
                for (int k = 0; k < half; k++) {
                    float ar = re[i + k], ai = im[i + k];
                    float br = re[i + k + half], bi = im[i + k + half];
                    float vr = br * cr - bi * ci;
                    float vi = br * ci + bi * cr;
                    re[i + k] = ar + vr;
                    im[i + k] = ai + vi;
                    re[i + k + half] = ar - vr;
                    im[i + k + half] = ai - vi;
                    float ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }

    // ==================== 信号处理（共用） ====================

    /**
     * @param mag       各频点幅值
     * @param n         频点数
     * @param isVisualizer true 表示幅值来自 Visualizer（8bit、已归一化），
     *                     false 表示来自麦克风 PCM（需按 FFT 长度归一化）
     */
    private void processSpectrum(float[] mag, int n, boolean isVisualizer) {
        if (n < 4) return;

        double wSum = 0;
        double cSum = 0;
        float scale = isVisualizer ? 255f : (MIC_FFT / 4f) * 32768f;

        for (int b = 0; b < BANDS; b++) {
            int start = binOf(b, n);
            int end = Math.max(start + 1, binOf(b + 1, n));
            double acc = 0;
            for (int k = start; k < end && k < n; k++) acc += mag[k];
            float avgMag = (float) (acc / (end - start));

            float amp = avgMag / scale;
            float db = (float) (20.0 * Math.log10(amp + 1e-6));
            float v = clamp01((db + 62f) / 62f);
            v *= 1.0f + 0.9f * (b / (float) (BANDS - 1)); // 高频补偿
            v = clamp01(v);

            float prev = bands[b];
            bands[b] = v > prev ? prev * 0.35f + v * 0.65f : prev * 0.80f + v * 0.20f;

            float w = bands[b];
            wSum += w;
            cSum += w * (b / (float) (BANDS - 1));
        }
        centroid = wSum > 0.001f ? clamp01((float) (cSum / wSum)) : 0.4f;

        float bAcc = 0, mAcc = 0, tAcc = 0;
        for (int i = 0; i < BANDS; i++) {
            if (i < 4) bAcc += bands[i];
            else if (i < 11) mAcc += bands[i];
            else tAcc += bands[i];
        }
        float tgtB = clamp01(bAcc / 4f);
        float tgtM = clamp01(mAcc / 7f);
        float tgtT = clamp01(tAcc / 5f);
        bass = bass * 0.6f + tgtB * 0.4f;
        mid = mid * 0.6f + tgtM * 0.4f;
        treble = treble * 0.6f + tgtT * 0.4f;

        float h = hue;
        double total = tgtB + tgtM + tgtT;
        if (total > 0.02) {
            float warm = (float) (tgtB / total);
            float cold = (float) (tgtT / total);
            float target = (cold * 190f + (1f - warm) * 60f + centroid * 60f) % 360f;
            float diff = target - h;
            while (diff > 180f) diff -= 360f;
            while (diff < -180f) diff += 360f;
            h = (h + diff * 0.10f + 360f) % 360f;
        }
        hue = h;
    }

    private void onLevelSample(float rms) {
        // AGC：峰值上冲快、回落慢，保证任意音量下 level 都能铺满 0..1
        if (rms > agcPeak) agcPeak = agcPeak * 0.85f + rms * 0.15f;
        else agcPeak = agcPeak * 0.995f + rms * 0.005f;

        float peak = Math.max(0.06f, agcPeak);
        float norm = Math.min(1.6f, rms / peak);

        // 上升快、下降慢：鼓点立刻亮起，衰减留余韵
        float t = level;
        if (norm > t) t = t * 0.25f + norm * 0.75f;
        else t = t * 0.82f + norm * 0.18f;
        level = clamp01(t);

        pushEnergy(rms);
        detectBeat(rms);

        long now = SystemClock.uptimeMillis();
        long dt = Math.max(1L, Math.min(200L, now - lastEmitMs));
        lastEmitMs = now;
        float b = beat * (float) Math.exp(-dt / 130.0);
        beat = b < 0.002f ? 0f : b;

        emit();
    }

    /** 对数分频边界，跳过直流分量。 */
    private int binOf(int band, int n) {
        double minF = 1.0;
        double maxF = n - 1.0;
        double f = minF * Math.pow(maxF / minF, band / (double) BANDS);
        return Math.max(1, Math.min(n - 1, (int) Math.round(f)));
    }

    // ==================== 节拍 ====================

    private void pushEnergy(float e) {
        energyHistory[historyIdx] = e;
        historyIdx = (historyIdx + 1) % HISTORY;
        if (historyFilled < HISTORY) historyFilled++;
    }

    private void detectBeat(float instant) {
        if (historyFilled < 8) return;

        double sum = 0;
        for (int i = 0; i < historyFilled; i++) sum += energyHistory[i];
        double avg = sum / historyFilled;

        double var = 0;
        for (int i = 0; i < historyFilled; i++) {
            double d = energyHistory[i] - avg;
            var += d * d;
        }
        var /= historyFilled;

        // 方差小 → 阈值抬高（避免平稳段落误触发）
        double threshold = BEAT_C * avg + BEAT_VAR_K / (var * 1000.0 + 1.0) + 0.006;

        long now = SystemClock.uptimeMillis();
        if (instant > threshold && instant > 0.02 && now - lastBeatMs > BEAT_MIN_GAP_MS) {
            float over = (float) (instant / (threshold + 1e-6)) - 1f;
            beat = Math.min(1f, 0.55f + over * 0.8f);
            lastBeatMs = now;
            recordBeat(now);
        }
    }

    private void recordBeat(long t) {
        beatTimes[beatTimesIdx] = t;
        beatTimesIdx = (beatTimesIdx + 1) % beatTimes.length;
        if (beatTimesFilled < beatTimes.length) beatTimesFilled++;

        if (beatTimesFilled >= 4) {
            long span = 0;
            int cnt = 0;
            for (int i = 1; i < beatTimesFilled; i++) {
                long d = beatTimes[i] - beatTimes[i - 1];
                if (d > 250 && d < 1500) { span += d; cnt++; }
            }
            if (cnt > 0) {
                int est = (int) (60000L / (span / cnt));
                if (est >= 50 && est <= 190) bpm = est;
            }
        }
    }

    // ==================== 输出 ====================

    private void emit() {
        Listener l = listener;
        if (l == null) return;
        float[] copy = new float[BANDS];
        System.arraycopy(bands, 0, copy, 0, BANDS);
        l.onFrame(new AudioFrame(level, copy, bass, mid, treble, centroid, beat, bpm, hue));
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
