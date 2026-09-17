package com.overlay.livewallpaper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 设置 + 开关 Activity。
 *   - 视频源列表（内置 + /sdcard/Movies）
 */
public class MainActivity extends Activity {

    private static final String PREF_FLOW_ENABLED = "flow_enabled";
    private static final String PREF_FLOW_WIDTH = "flow_width_dp";
    private static final String PREF_FLOW_SENSITIVITY = "flow_sensitivity";
    private static final String PREF_FLOW_STYLE = "flow_style";
    private static final String PREF_FLOW_BRIGHTNESS = "flow_brightness_pct";

    private Spinner spinnerVideo;
    private List<VideoSource.Item> videoItems;
    private AudioVisualizerView visualizer;
    private AudioAnalyzer analyzer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        
        stopService(new Intent(this, OverlayService.class));

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density;

        
        spinnerVideo = findViewById(R.id.spinnerVideo);
        videoItems = VideoSource.list(this);
        String currentPath = VideoSource.currentPath(this);

        
        boolean foundCurrent = false;
        for (VideoSource.Item it : videoItems) {
            if (it.path.equals(currentPath)) { foundCurrent = true; break; }
        }
        if (!foundCurrent && !VideoSource.BUILTIN_PATH.equals(currentPath)) {
            File cf = new File(currentPath);
            if (cf.exists()) {
                videoItems.add(new VideoSource.Item(cf.getName(), currentPath, false));
            }
        }

        List<String> videoNames = new ArrayList<>();
        int currentIdx = 0;
        for (int i = 0; i < videoItems.size(); i++) {
            VideoSource.Item it = videoItems.get(i);
            videoNames.add(it.name);
            if (it.path.equals(currentPath)) currentIdx = i;
        }
        ArrayAdapter<String> videoAdapter = new ArrayAdapter<>(
                this, R.layout.spinner_item, videoNames);
        videoAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinnerVideo.setAdapter(videoAdapter);
        spinnerVideo.setSelection(currentIdx);

        
        Button btnBrowse = findViewById(R.id.btnBrowse);
        btnBrowse.setOnClickListener(v -> showFileBrowser());

        
        visualizer = findViewById(R.id.visualizer);
        Switch swFlow = findViewById(R.id.swFlow);
        SeekBar sbWidth = findViewById(R.id.sbWidth);
        TextView tvWidth = findViewById(R.id.tvWidth);
        SeekBar sbSens = findViewById(R.id.sbSens);
        TextView tvSens = findViewById(R.id.tvSens);
        SeekBar sbBright = findViewById(R.id.sbBright);
        TextView tvBright = findViewById(R.id.tvBright);

        boolean flowEnabled = prefs.getBoolean(PREF_FLOW_ENABLED, true);
        int widthDp = prefs.getInt(PREF_FLOW_WIDTH, 12);
        int sensPct = prefs.getInt(PREF_FLOW_SENSITIVITY, 50);
        int brightPct = prefs.getInt(PREF_FLOW_BRIGHTNESS, 100);
        int flowStyle = prefs.getInt(PREF_FLOW_STYLE, AudioVisualizerView.STYLE_RAINBOW);

        swFlow.setChecked(flowEnabled);
        sbWidth.setProgress(widthDp);
        tvWidth.setText(widthDp + "dp");
        sbSens.setProgress(sensPct);
        tvSens.setText(sensPct + "%");
        sbBright.setProgress(brightPct);
        tvBright.setText(brightPct + "%");

        visualizer.setEnabled2(flowEnabled);
        visualizer.setFlowWidth(widthDp * density);
        
        visualizer.setSensitivity(0.1f + (sensPct / 100f) * 4.9f);
        
        visualizer.setBrightness(brightPct / 100f);
        visualizer.setStyle(flowStyle);

        
        RadioGroup styleGroup = findViewById(R.id.styleGroup);
        float d = density;
        String[] styleNames = {
                "彩虹流动", "双色彩带", "极光",
                "霓虹", "赛博"
        };
        int[] styleValues = {
                AudioVisualizerView.STYLE_RAINBOW,
                AudioVisualizerView.STYLE_DUAL,
                AudioVisualizerView.STYLE_AURORA,
                AudioVisualizerView.STYLE_NEON,
                AudioVisualizerView.STYLE_CYBER
        };
        
        if (flowStyle == AudioVisualizerView.STYLE_SINGLE
                || flowStyle == AudioVisualizerView.STYLE_SPECTRUM
                || flowStyle == 7 || flowStyle == 8) {
            flowStyle = AudioVisualizerView.STYLE_RAINBOW;
        }
        for (int i = 0; i < styleNames.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(styleNames[i]);
            rb.setTextColor(0xFFFFFFFF);
            rb.setPadding((int) (8 * d), (int) (6 * d), (int) (8 * d), (int) (6 * d));
            rb.setTextSize(14);
            rb.setTag(styleValues[i]);
            RadioGroup.LayoutParams lp = new RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.WRAP_CONTENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = (int) (6 * d);
            styleGroup.addView(rb, lp);
            if (styleValues[i] == flowStyle) styleGroup.check(rb.getId());
        }
        if (styleGroup.getCheckedRadioButtonId() == View.NO_ID && styleGroup.getChildCount() > 0) {
            styleGroup.check(styleGroup.getChildAt(0).getId());
        }
        styleGroup.setOnCheckedChangeListener((g, checkedId) -> {
            View rb = g.findViewById(checkedId);
            if (rb == null) return;
            Object tag = rb.getTag();
            if (tag instanceof Integer) {
                int s = (Integer) tag;
                prefs.edit().putInt(PREF_FLOW_STYLE, s).apply();
                visualizer.setStyle(s);
            }
        });

        swFlow.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(PREF_FLOW_ENABLED, checked).apply();
            visualizer.setEnabled2(checked);
        });

        sbWidth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fu) {
                tvWidth.setText(p + "dp");
                visualizer.setFlowWidth(p * density);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                prefs.edit().putInt(PREF_FLOW_WIDTH, sb.getProgress()).apply();
            }
        });

        sbSens.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fu) {
                tvSens.setText(p + "%");
                visualizer.setSensitivity(0.1f + (p / 100f) * 4.9f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                prefs.edit().putInt(PREF_FLOW_SENSITIVITY, sb.getProgress()).apply();
            }
        });

        sbBright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fu) {
                tvBright.setText(p + "%");
                visualizer.setBrightness(p / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                prefs.edit().putInt(PREF_FLOW_BRIGHTNESS, sb.getProgress()).apply();
            }
        });

        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);

        btnStart.setOnClickListener(v -> {
            int pos = spinnerVideo.getSelectedItemPosition();
            if (pos >= 0 && pos < videoItems.size()) {
                VideoSource.setCurrent(this, videoItems.get(pos).path);
            }
            startService(new Intent(MainActivity.this, OverlayService.class));
            finish();
        });

        btnStop.setOnClickListener(v -> {
            stopService(new Intent(MainActivity.this, OverlayService.class));
            finish();
        });

        
        analyzer = AudioAnalyzer.get();
        analyzer.setListener(new AudioAnalyzer.Listener() {
            @Override
            public void onLevel(float level) {
                visualizer.onAudioLevel(level);
            }

            @Override
            public void onIntensity(float intensity) {
                visualizer.setIntensity(intensity);
            }

            @Override
            public void onHue(float hueDeg) {
                visualizer.setHue(hueDeg);
            }
        });
        analyzer.start();
    }

    

    
    private void showFileBrowser() {
        File startDir = Environment.getExternalStorageDirectory();
        if (startDir == null || !startDir.isDirectory()) {
            startDir = new File("/sdcard");
        }
        showDirDialog(startDir);
    }

    
    private void showDirDialog(final File dir) {
        List<File> subDirs = new ArrayList<>();
        List<File> mp4Files = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) {
                if (f.isDirectory()) {
                    subDirs.add(f);
                } else if (f.getName().toLowerCase().endsWith(".mp4")) {
                    mp4Files.add(f);
                }
            }
        }
        Collections.sort(subDirs, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        Collections.sort(mp4Files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        
        List<String> names = new ArrayList<>();
        List<File> targets = new ArrayList<>();
        boolean isRoot = isStorageRoot(dir);
        if (!isRoot) {
            names.add("↑ 返回上级");
            targets.add(dir.getParentFile());
        }
        for (File d : subDirs) {
            names.add("📁 " + d.getName());
            targets.add(d);
        }
        for (File f : mp4Files) {
            names.add("🎬 " + f.getName());
            targets.add(f);
        }

        String title = dir.getAbsolutePath();
        if (title.length() > 40) title = "..." + title.substring(title.length() - 38);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, names);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        builder.setAdapter(adapter, (dialog, which) -> {
            File target = targets.get(which);
            if (target == null) return;
            if (target.isDirectory()) {
                showDirDialog(target);
            } else {
                
                pickVideoFile(target);
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    
    private boolean isStorageRoot(File dir) {
        if (dir == null) return true;
        File parent = dir.getParentFile();
        return parent == null || !parent.canRead()
                || "/storage".equals(dir.getAbsolutePath())
                || "/sdcard".equals(dir.getAbsolutePath())
                || Environment.getExternalStorageDirectory().getAbsolutePath().equals(dir.getAbsolutePath());
    }

    
    private void pickVideoFile(File file) {
        VideoSource.setCurrent(this, file.getAbsolutePath());
        Toast.makeText(this, "已选取: " + file.getName(), Toast.LENGTH_SHORT).show();

        
        videoItems = VideoSource.list(this);
        boolean exists = false;
        for (VideoSource.Item it : videoItems) {
            if (it.path.equals(file.getAbsolutePath())) { exists = true; break; }
        }
        if (!exists) {
            videoItems.add(new VideoSource.Item(file.getName(), file.getAbsolutePath(), false));
        }
        List<String> videoNames = new ArrayList<>();
        int idx = 0;
        for (int i = 0; i < videoItems.size(); i++) {
            videoNames.add(videoItems.get(i).name);
            if (videoItems.get(i).path.equals(file.getAbsolutePath())) idx = i;
        }
        ArrayAdapter<String> videoAdapter = new ArrayAdapter<>(
                this, R.layout.spinner_item, videoNames);
        videoAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinnerVideo.setAdapter(videoAdapter);
        spinnerVideo.setSelection(idx);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (analyzer != null) {
            analyzer.stop();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }
}
