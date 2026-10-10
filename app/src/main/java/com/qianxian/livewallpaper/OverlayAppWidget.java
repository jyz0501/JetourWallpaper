package com.qianxian.livewallpaper;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViews;


public class OverlayAppWidget extends AppWidgetProvider {

    private static final String TAG = "OverlayAppWidget";

    public static final String ACTION_START = "com.qianxian.livewallpaper.action.WIDGET_START";
    public static final String ACTION_STOP = "com.qianxian.livewallpaper.action.WIDGET_STOP";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        Log.i(TAG, "onUpdate ids=" + ids.length);
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_overlay);
        rv.setOnClickPendingIntent(R.id.btnWidgetStart, broadcast(ctx, ACTION_START));
        rv.setOnClickPendingIntent(R.id.btnWidgetStop, broadcast(ctx, ACTION_STOP));
        for (int id : ids) {
            mgr.updateAppWidget(id, rv);
        }
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "onReceive action=" + action);
        super.onReceive(ctx, intent);
        if (ACTION_START.equals(action)) {
            try {
                ComponentName c = ctx.startForegroundService(
                        new Intent(ctx, OverlayService.class));
                Log.i(TAG, "startForegroundService ok cmp=" + c);
            } catch (Throwable t) {
                Log.e(TAG, "startForegroundService failed: " + t.getMessage(), t);
                try {
                    ctx.startService(new Intent(ctx, OverlayService.class));
                    Log.i(TAG, "fallback startService ok");
                } catch (Throwable t2) {
                    Log.e(TAG, "fallback startService failed: " + t2.getMessage(), t2);
                }
            }
        } else if (ACTION_STOP.equals(action)) {
            try {
                boolean stopped = ctx.stopService(new Intent(ctx, OverlayService.class));
                Log.i(TAG, "stopService stopped=" + stopped);
            } catch (Throwable t) {
                Log.e(TAG, "stopService failed: " + t.getMessage(), t);
            }
        }
    }

    private static PendingIntent broadcast(Context ctx, String action) {
        Intent i = new Intent(action).setComponent(
                new ComponentName(ctx.getPackageName(), OverlayAppWidget.class.getName()));
        return PendingIntent.getBroadcast(
                ctx, action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
