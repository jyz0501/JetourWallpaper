package com.overlay.livewallpaper;

import android.media.audiofx.Visualizer;
import android.util.Log;


public class AudioAnalyzer {

    private static final String TAG = "AudioAnalyzer";

    private static AudioAnalyzer INSTANCE;

    public synchronized static AudioAnalyzer get() {
        if (INSTANCE == null) INSTANCE = new AudioAnalyzer();
        return INSTANCE;
    }

    
    private static final float SENSITIVITY_MUL = 3.5f;

    
    public interface Listener {
        
        void onLevel(float level);

        
        default void onIntensity(float intensity) {
        }

        
        default void onHue(float hueDeg) {
        }
    }

    private Visualizer visualizer;
    private Listener listener;

    
    private float lastHue = 0f;
    private boolean firstHue = true;

    private AudioAnalyzer() {
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public synchronized void start() {
        if (visualizer != null) {
            return;
        }
        try {
            int[] range = Visualizer.getCaptureSizeRange();
            int captureSize = (range != null && range.length >= 2) ? range[1] : 256;
            Visualizer v = new Visualizer(0);
            
            try { v.setEnabled(false); } catch (Exception ignore) { }
            v.setCaptureSize(captureSize);
            v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);

            final float[] lastLevelWrap = new float[]{0f};
            final float[] lastIntensityWrap = new float[]{0f};

            
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer viz, byte[] waveform, int samplingRate) {
                    Listener l = listener;
                    if (waveform == null || waveform.length == 0) return;
                    long sumSq = 0;
                    for (byte b : waveform) {
                        int centered = (b & 0xFF) - 128;   
                        sumSq += (long) centered * centered;
                    }
                    double rms = Math.sqrt((double) sumSq / waveform.length); 

                    
                    float level = (float) Math.min(1.0, rms / 128.0);
                    float smoothedLevel = lastLevelWrap[0] * 0.6f + level * 0.4f;
                    lastLevelWrap[0] = smoothedLevel;

                    
                    float intensity = (float) Math.min(1.0, (rms / 128.0) * (double) SENSITIVITY_MUL);
                    float smoothedI = lastIntensityWrap[0] * 0.6f + intensity * 0.4f;
                    lastIntensityWrap[0] = smoothedI;

                    if (l != null) {
                        l.onLevel(smoothedLevel);
                        l.onIntensity(smoothedI);
                    }
                }

                @Override
                public void onFftDataCapture(Visualizer viz, byte[] fft, int samplingRate) {
                    if (fft == null || fft.length < 4) return;
                    Listener l = listener;
                    int n = fft.length;
                    int half = n / 2; 

                    double bassSum = 0;
                    double trebleSum = 0;

                    
                    int bassEnd = Math.max(2, half / 16);
                    for (int k = 1; k < bassEnd; k++) {
                        int re = fft[2 * k];
                        int im = fft[2 * k + 1];
                        bassSum += Math.sqrt((double) re * re + (double) im * im);
                    }
                    
                    int trebleStart = half / 4;
                    for (int k = trebleStart; k < half; k++) {
                        int re = fft[2 * k];
                        int im = fft[2 * k + 1];
                        trebleSum += Math.sqrt((double) re * re + (double) im * im);
                    }

                    float hue;
                    double total = bassSum + trebleSum;
                    if (total < 1.0) {
                        hue = firstHue ? 0f : lastHue;
                    } else {
                        double ratio = bassSum / total; 
                        
                        float hueRaw = (float) ((1.0 - ratio) * 260.0);
                        if (firstHue) {
                            hue = hueRaw;
                            firstHue = false;
                        } else {
                            
                            float diff = hueRaw - lastHue;
                            while (diff > 180f) diff -= 360f;
                            while (diff < -180f) diff += 360f;
                            hue = lastHue + diff * 0.35f;
                            if (hue < 0f) hue += 360f;
                            if (hue >= 360f) hue -= 360f;
                        }
                    }
                    lastHue = hue;

                    if (l != null) {
                        l.onHue(hue);
                    }
                }
            }, Visualizer.getMaxCaptureRate(), true, true); 
            v.setEnabled(true);
            visualizer = v;
            Log.i(TAG, "Visualizer started session=0 captureSize=" + captureSize
                    + " (waveform+fft, intensityMul=" + SENSITIVITY_MUL + ")");
        } catch (Throwable e) {
            Log.e(TAG, "Visualizer init failed: " + e.getMessage(), e);
            visualizer = null;
        }
    }

    public synchronized void stop() {
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
                visualizer.release();
            } catch (Throwable ignored) {
            }
            visualizer = null;
        }
    }
}
