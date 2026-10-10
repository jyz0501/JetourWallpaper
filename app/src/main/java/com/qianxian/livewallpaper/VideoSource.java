package com.qianxian.livewallpaper;

import android.content.Context;
import android.os.Environment;
import android.preference.PreferenceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 视频源列表 + 选中状态持久化。
 *   - 内置：R.raw.su7
 *   - 外部：/sdcard/Movies
 */
public final class VideoSource {

    public static final String BUILTIN_PATH = "builtin:raw/su7";
    private static final String PREF_KEY = "video_source";

    public static class Item {
        public final String name;
        public final String path;
        public final boolean builtin;
        public Item(String name, String path, boolean builtin) {
            this.name = name; this.path = path; this.builtin = builtin;
        }
    }

    private VideoSource() { }

    public static List<Item> list(Context ctx) {
        List<Item> items = new ArrayList<>();
        items.add(new Item("su7 (内置)", BUILTIN_PATH, true));
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        if (dir != null && dir.isDirectory()) {
            File[] files = dir.listFiles((d, n) -> n != null && n.toLowerCase().endsWith(".mp4"));
            if (files != null && files.length > 0) {
                Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                for (File f : files) {
                    items.add(new Item(f.getName(), f.getAbsolutePath(), false));
                }
            }
        }
        return items;
    }

    public static String currentPath(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getString(PREF_KEY, BUILTIN_PATH);
    }

    public static void setCurrent(Context ctx, String path) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(PREF_KEY, path).apply();
    }
}
