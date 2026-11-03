package com.qianxian.JetourWallpaper;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;


public class QuickActionActivity extends Activity {

    private static final String TAG = "QuickAction";

    public static final String ACTION_START = "com.qianxian.JetourWallpaper.action.START_OVERLAY";
    public static final String ACTION_STOP  = "com.qianxian.JetourWallpaper.action.STOP_OVERLAY";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            String a = getIntent() == null ? null : getIntent().getAction();
            if (ACTION_START.equals(a)) {
                start();
            } else if (ACTION_STOP.equals(a)) {
                stop();
            } else {
                
                start();
            }
        } catch (Throwable t) {
            Log.e(TAG, "onCreate failed: " + t.getMessage(), t);
        }
        finish();
    }

    private void start() {
        try {
            startForegroundService(new Intent(this, OverlayService.class));
            Log.i(TAG, "start OverlayService via QuickAction");
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        } catch (Throwable t) {
            Log.e(TAG, "startForegroundService fall back: " + t.getMessage());
            try { startService(new Intent(this, OverlayService.class)); }
            catch (Throwable ignore) { }
        }
    }

    private void stop() {
        try {
            boolean stopped = stopService(new Intent(this, OverlayService.class));
            Log.i(TAG, "stop OverlayService via QuickAction stopped=" + stopped);
        } catch (Throwable t) {
            Log.e(TAG, "stopService failed: " + t.getMessage(), t);
        }
    }
}
