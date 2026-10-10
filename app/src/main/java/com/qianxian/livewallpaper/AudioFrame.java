package com.qianxian.livewallpaper;

/**
 * 一帧完整的音频特征，由 {@link AudioAnalyzer} 统一产出。
 * 渲染层只依赖这个快照，保证亮度/颜色/节拍严格同步。
 */
public final class AudioFrame {

    /** 整体音量 0..1（已做 AGC 自适应增益）。 */
    public final float level;

    /** 频谱柱，长度 {@link AudioAnalyzer#BANDS}，0..1。 */
    public final float[] bands;

    /** 低频 / 中频 / 高频能量 0..1。 */
    public final float bass;
    public final float mid;
    public final float treble;

    /** 频谱重心 0..1，值越大声音越"亮"。 */
    public final float centroid;

    /** 节拍冲击 0..1，鼓点瞬间置位后指数衰减。 */
    public final float beat;

    /** 估算 BPM，0 表示尚未稳定。 */
    public final int bpm;

    /** 建议色相 0..360。 */
    public final float hue;

    /** 单调递增帧号。 */
    public final long seq;

    private static long SEQ = 0;

    public AudioFrame(float level, float[] bands, float bass, float mid, float treble,
                      float centroid, float beat, int bpm, float hue) {
        this.level = level;
        this.bands = bands;
        this.bass = bass;
        this.mid = mid;
        this.treble = treble;
        this.centroid = centroid;
        this.beat = beat;
        this.bpm = bpm;
        this.hue = hue;
        this.seq = ++SEQ;
    }

    /** 无声音时的静默帧。 */
    public static AudioFrame silent() {
        float[] empty = new float[AudioAnalyzer.BANDS];
        return new AudioFrame(0f, empty, 0f, 0f, 0f, 0.4f, 0f, 0, 210f);
    }
}
