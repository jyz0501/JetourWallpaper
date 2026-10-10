package com.qianxian.livewallpaper;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;

public class OverlayService extends Service {
    private static final String TAG = "OverlayService";
    private static final String CHAN_ID = "overlay_video";
    private static final int NOTI_ID = 0xA1;
    private static final String LAUNCHER_PKG = "com.autolink.launcher";
    private static final long POLL_MS = 500L;

    private WindowManager wm;
    private TextureView textureView;
    private View overlayView;          
    private View flowOverlayView;      
    private AudioVisualizerView flowView;  
    private View settingsButton;        
    private MediaPlayer player;
    private boolean added;
    private boolean flowAdded;

    private AudioAnalyzer audioAnalyzer;   

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poller = new Runnable() {
        @Override public void run() {
            updateVisibility();
            handler.postDelayed(this, POLL_MS);
        }
    };

    
    private volatile boolean naviActive = false;
    
    private volatile boolean manualHide = false;

    private LogcatWatcher logcatWatcher;

    /** 车机桌面不存在（例如装在普通手机上测试）时，放宽为一直显示，否则叠加层永远不可见。 */
    private volatile boolean launcherInstalled = true;

    private String lastTopPkg = "";
    private boolean lastNaviActive = false;
    private boolean lastManualHide = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundCompat();
        launcherInstalled = isPackageInstalled(LAUNCHER_PKG);
        if (!launcherInstalled) {
            Log.i(TAG, "launcher " + LAUNCHER_PKG + " not installed, overlay always visible");
        }
        addOverlay();
        addSettingsButton();
        startLogcatWatcher();
        startFlowLight();
        handler.post(poller);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(poller);
        stopLogcatWatcher();
        stopFlowLight();
        removeOverlay();
        removeFlowOverlay();
        removeSettingsButton();
        super.onDestroy();
    }

    

    private void addFlowOverlay() {
        if (wm == null || flowAdded) return;

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

        
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                screenW, screenH,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setTitle("LiveWallpaperFlowLight");

        FrameLayout container = new FrameLayout(this);
        container.setLayoutParams(new FrameLayout.LayoutParams(screenW, screenH));
        try {
            wm.addView(container, lp);
            flowOverlayView = container;
            flowAdded = true;
            Log.i(TAG, "FlowOverlay added type=" + type + " (top-level)");
        } catch (Exception e) {
            Log.e(TAG, "addFlowOverlay failed", e);
        }
    }

    private void removeFlowOverlay() {
        if (wm != null && flowAdded && flowOverlayView != null) {
            try {
                wm.removeView(flowOverlayView);
            } catch (Exception e) {
                Log.e(TAG, "removeFlowOverlay failed", e);
            }
            flowAdded = false;
            flowOverlayView = null;
        }
    }

    private void startFlowLight() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean enabled = prefs.getBoolean("flow_enabled", true);
        if (!enabled || flowView != null) return;

        
        addFlowOverlay();
        if (flowOverlayView == null) {
            Log.w(TAG, "startFlowLight skipped: flowOverlayView not available");
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        int widthDp = prefs.getInt("flow_width_dp", 12);
        int sensPct = prefs.getInt("flow_sensitivity", 50);
        int brightPct = prefs.getInt("flow_brightness_pct", 100);
        int speedPct = prefs.getInt("flow_speed_pct", 50);
        int style = prefs.getInt("flow_style", AudioVisualizerView.STYLE_SPECTRUM);

        flowView = new AudioVisualizerView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        flowView.setLayoutParams(lp);
        flowView.setEnabled2(true);
        flowView.setFlowWidth(widthDp * density);
        flowView.setSensitivity(0.5f + (sensPct / 100f) * 1.5f);
        flowView.setBrightness(brightPct / 100f);
        flowView.setFlowSpeed(speedPct / 100f);
        flowView.setStyle(style);
        
        ((FrameLayout) flowOverlayView).addView(flowView);

        audioAnalyzer = AudioAnalyzer.get();
        audioAnalyzer.setListener(frame -> {
            if (flowView != null) flowView.onFrame(frame);
        });
        audioAnalyzer.start(this);
        Log.i(TAG, "FlowLight started style=" + style
                + " widthDp=" + widthDp + " sensPct=" + sensPct
                + " brightPct=" + brightPct
                + " (top-level window)");
    }

    private void stopFlowLight() {
        if (audioAnalyzer != null) {
            audioAnalyzer.stop();
            audioAnalyzer = null;
        }
        if (flowView != null && flowOverlayView instanceof FrameLayout) {
            try {
                ((FrameLayout) flowOverlayView).removeView(flowView);
            } catch (Exception ignored) { }
            flowView = null;
        }
        Log.i(TAG, "FlowLight stopped");
    }

    

    private void startLogcatWatcher() {
        if (logcatWatcher != null) return;
        logcatWatcher = new LogcatWatcher();
        logcatWatcher.start();
        Log.i(TAG, "LogcatWatcher started");
    }

    private void stopLogcatWatcher() {
        if (logcatWatcher != null) {
            logcatWatcher.stopWatching();
            logcatWatcher = null;
        }
    }


    private class LogcatWatcher extends Thread {
        private static final String SCHEME_MARK = "scheme = [cmdWakeUp_internal.]";
        private static final String END_MARK = "addCommandWakeUp end,";
        private static final String UPDATE_MARK = "updateBlackWords end,";

        private Process process;
        private volatile boolean running = true;

        @Override
        public void run() {
            try {
                process = new ProcessBuilder("logcat", "-s", "AIN-CommandWakeUpRegistry:V")
                        .redirectErrorStream(true)
                        .start();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                String line;
                while (running && (line = reader.readLine()) != null) {
                    boolean active;
                    boolean matched = true;
                    if (line.contains(SCHEME_MARK)) {
                        active = line.contains("放大地图") || line.contains("缩小地图");
                    } else if (line.contains(END_MARK) || line.contains(UPDATE_MARK)) {
                        active = false;
                    } else {
                        matched = false;
                        active = naviActive;
                    }
                    if (matched && active != naviActive) {
                        naviActive = active;
                        Log.i(TAG, "naviActive=" + naviActive
                                + " (from AIN-CommandWakeUpRegistry)");
                    }
                }
            } catch (Exception e) {
                if (running) Log.w(TAG, "LogcatWatcher error: " + e.getMessage());
            }
        }

        void stopWatching() {
            running = false;
            if (process != null) {
                process.destroy();
            }
        }
    }

    

    private boolean isLauncherForeground() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return false;
        String topPkg = null;
        try {
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(10);
            if (tasks != null && !tasks.isEmpty()) {
                for (ActivityManager.RunningTaskInfo t : tasks) {
                    if (t.topActivity == null) continue;
                    String pkg = t.topActivity.getPackageName();
                    if (pkg == null) continue;
                    topPkg = pkg;
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getRunningTasks failed: " + e.getMessage());
        }
        boolean launcherTop = LAUNCHER_PKG.equals(topPkg);
        if (!String.valueOf(topPkg).equals(lastTopPkg)) {
            Log.i(TAG, "topActivity=" + topPkg + " launcherTop=" + launcherTop);
            lastTopPkg = String.valueOf(topPkg);
        }
        return launcherTop;
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            return getPackageManager().getPackageInfo(pkg, 0) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void updateVisibility() {
        boolean launcherTop = launcherInstalled ? isLauncherForeground() : true;

        boolean showOverlay;
        boolean showButton;
        if (manualHide) {
            
            showOverlay = false;
            showButton = launcherTop;
        } else {
            boolean show = launcherTop && !naviActive;
            showOverlay = show;
            showButton = show;
        }

        float target = showOverlay ? 1f : 0f;
        if (overlayView != null && overlayView.getAlpha() != target) {
            overlayView.setAlpha(target);
            Log.i(TAG, "Overlay alpha=" + target
                    + " (launcher=" + launcherTop + " naviActive=" + naviActive
                    + " manualHide=" + manualHide + ")");
        }
        float btnTarget = showButton ? 1f : 0f;
        if (settingsButton != null && settingsButton.getAlpha() != btnTarget) {
            settingsButton.setAlpha(btnTarget);
        }

        if (naviActive != lastNaviActive) {
            Log.i(TAG, "naviActive=" + naviActive + " (state changed)");
            lastNaviActive = naviActive;
        }
        if (manualHide != lastManualHide) {
            Log.i(TAG, "manualHide=" + manualHide);
            lastManualHide = manualHide;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void addOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) {
            Log.e(TAG, "WindowManager null");
            return;
        }

        // 视频壁纸总开关：关掉后不创建视频窗口、不解码，只保留流光灯
        boolean videoEnabled = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("video_enabled", true);
        if (!videoEnabled) {
            Log.i(TAG, "video disabled by user, skip video overlay");
            return;
        }

        
        
        
        int[] tryTypes = new int[] {
                2013, 
                2004, 
                2010, 
                2014, 
                2017, 
                2006, 
                2005, 
                2003, 
                2002, 
                2038  
        };

        
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;

        FrameLayout container = null;
        int usedType = -1;
        for (int t : tryTypes) {
            WindowManager.LayoutParams fLp = new WindowManager.LayoutParams(
                    screenW, screenH,
                    t,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            fLp.gravity = Gravity.TOP | Gravity.START;
            fLp.setTitle("LiveWallpaperOverlay");
            
            FrameLayout c = new FrameLayout(this);
            c.setLayoutParams(new FrameLayout.LayoutParams(screenW, screenH));
            c.setAlpha(0f);
            try {
                wm.addView(c, fLp);
                container = c;
                usedType = t;
                added = true;
                Log.i(TAG, "Overlay added type=" + t);
                break;
            } catch (Exception ex) {
                Log.w(TAG, "  type=" + t + " failed: " + ex.getMessage());
                try { wm.removeViewImmediate(c); } catch (Exception ignore) { }
            }
        }
        if (container == null) {
            Log.e(TAG, "addView all types failed");
            return;
        }
        overlayView = container;

        
        
        final FrameLayout fContainer = container;
        final int fType = usedType;
        textureView = new TextureView(this);
        FrameLayout.LayoutParams tvLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        textureView.setLayoutParams(tvLp);
        container.addView(textureView);

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                startPlayback(new Surface(surface));
            }
            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { }
            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                stopPlayback();
                return true;
            }
            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
        });
    }

    private void addSettingsButton() {
        if (wm == null) return;
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

        int size = dp(40);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                size, size,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.setTitle("LiveWallpaperSettingsBtn");

        TextView btn = new TextView(this);
        btn.setText("\u2699"); 
        btn.setTextSize(20);
        btn.setTextColor(0xFFFFFFFF);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x66000000);
        btn.setBackground(bg);

        
        btn.setOnClickListener(v -> {
            manualHide = !manualHide;
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            updateVisibility();
            Log.i(TAG, "gear single click -> manualHide=" + manualHide);
        });

        
        btn.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        });

        try {
            wm.addView(btn, lp);
            settingsButton = btn;
            settingsButton.setAlpha(0f); 
        } catch (Exception e) {
            Log.e(TAG, "addSettingsButton failed", e);
        }
    }

    private Uri resolveVideoUri() {
        String path = VideoSource.currentPath(this);
        if (VideoSource.BUILTIN_PATH.equals(path)) {
            String p = "android.resource://" + getPackageName() + "/" + R.raw.su7;
            return Uri.parse(p);
        }
        File f = new File(path);
        if (f.exists()) {
            return Uri.fromFile(f);
        }
        Log.w(TAG, "Video not found, fallback to builtin: " + path);
        String p = "android.resource://" + getPackageName() + "/" + R.raw.su7;
        return Uri.parse(p);
    }

    private void startPlayback(Surface surface) {
        try {
            stopPlayback();
            player = new MediaPlayer();
            player.setDataSource(this, resolveVideoUri());
            player.setSurface(surface);
            player.setLooping(true);
            player.setVideoScalingMode(
                    MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            player.setOnPreparedListener(mp -> {
                mp.start();
                Log.i(TAG, "Playback started "
                        + mp.getVideoWidth() + "x" + mp.getVideoHeight()
                        + " uri=" + resolveVideoUri());
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "startPlayback failed", e);
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try {
                player.stop();
                player.release();
            } catch (Exception ignored) { }
            player = null;
        }
    }

    private void removeOverlay() {
        stopPlayback();
        if (wm != null && added && overlayView != null) {
            try {
                wm.removeView(overlayView);
            } catch (Exception e) {
                Log.e(TAG, "removeView failed", e);
            }
            added = false;
            overlayView = null;
        }
    }

    private void removeSettingsButton() {
        if (wm != null && settingsButton != null) {
            try {
                wm.removeView(settingsButton);
            } catch (Exception ignored) { }
            settingsButton = null;
        }
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHAN_ID, "动态壁纸叠加", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, CHAN_ID)
                .setContentTitle("捷徒动画")
                .setContentText("叠加播放中 · 单击齿轮隐藏/长按设置")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build();
        startForeground(NOTI_ID, n);
    }
}
