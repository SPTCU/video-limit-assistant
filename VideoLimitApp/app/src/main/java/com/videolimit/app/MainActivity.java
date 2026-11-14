package com.videolimit.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int INTERVAL_STEP = 5;
    private static final int INTERVAL_MIN = 1;
    private static final int INTERVAL_MAX = 120;
    private static final int REST_STEP = 1;
    private static final int REST_MIN = 1;
    private static final int REST_MAX = 30;

    private TextView tvStateTitle;
    private TextView tvStateSub;
    private TextView tvInterval;
    private TextView tvRest;
    private TextView tvExitMode;
    private TextView tvExitDesc;
    private TextView tvNavMode;
    private TextView tvNavDesc;
    private TextView tvManagedEmpty;
    private ProgressBar stateProgress;
    private LinearLayout permContainer;
    private LinearLayout managedContainer;
    private Switch swService;
    private Switch swBoot;
    private WeekChartView chart;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean suppressSwitchEvent = false;
    private String lastPermKey = "";
    private String lastManagedKey = "";
    private long lastTodayAt = 0;
    private long lastChartAt = 0;
    private long todayTotalMs = 0;

    private final Runnable refresher = () -> {
        refresh();
        scheduleNext();
    };

    private void scheduleNext() {
        handler.postDelayed(refresher, 1000);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStateTitle = findViewById(R.id.tv_state_title);
        tvStateSub = findViewById(R.id.tv_state_sub);
        tvInterval = findViewById(R.id.tv_interval);
        tvRest = findViewById(R.id.tv_rest);
        tvExitMode = findViewById(R.id.tv_exit_mode);
        tvExitDesc = findViewById(R.id.tv_exit_desc);
        tvNavMode = findViewById(R.id.tv_nav_mode);
        tvNavDesc = findViewById(R.id.tv_nav_desc);
        tvManagedEmpty = findViewById(R.id.managed_empty);
        stateProgress = findViewById(R.id.state_progress);
        permContainer = findViewById(R.id.perm_container);
        managedContainer = findViewById(R.id.managed_container);
        swService = findViewById(R.id.sw_service);
        swBoot = findViewById(R.id.sw_boot);
        chart = findViewById(R.id.chart);

        findViewById(R.id.btn_manage).setOnClickListener(
                v -> startActivity(new Intent(this, AppListActivity.class)));
        findViewById(R.id.btn_int_minus).setOnClickListener(v -> changeInterval(-INTERVAL_STEP));
        findViewById(R.id.btn_int_plus).setOnClickListener(v -> changeInterval(INTERVAL_STEP));
        findViewById(R.id.btn_rest_minus).setOnClickListener(v -> changeRest(-REST_STEP));
        findViewById(R.id.btn_rest_plus).setOnClickListener(v -> changeRest(REST_STEP));
        tvExitMode.setOnClickListener(v -> {
            Prefs.setExitMode(this, Prefs.getExitMode(this) == Prefs.EXIT_BACK
                    ? Prefs.EXIT_HOME : Prefs.EXIT_BACK);
            updateExitModeText();
        });
        tvNavMode.setOnClickListener(v -> {
            int cur = Prefs.getNavMode(this);
            int next = cur == Prefs.NAV_AUTO ? Prefs.NAV_KEYS
                    : cur == Prefs.NAV_KEYS ? Prefs.NAV_GESTURE : Prefs.NAV_AUTO;
            Prefs.setNavMode(this, next);
            updateNavModeText();
        });

        swService.setOnCheckedChangeListener((b, checked) -> {
            if (suppressSwitchEvent) {
                return;
            }
            Prefs.setServiceOn(this, checked);
            if (checked) {
                startGuard();
            } else {
                stopService(new Intent(this, LimitService.class));
            }
        });

        swBoot.setOnCheckedChangeListener((b, checked) -> {
            if (suppressSwitchEvent) {
                return;
            }
            Prefs.setBootOn(this, checked);
        });

        updateRhythmTexts();
        updateExitModeText();
        updateNavModeText();
        buildPermissionRows();
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestNotificationPermissionIfNeeded();
        // 用户可能刚在系统设置里换了导航方式，回来时重新识别一次
        updateNavModeText();
        if (Prefs.isServiceOn(this) && !LimitService.isRunning) {
            startGuard();
        }
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
    }

    private void changeInterval(int delta) {
        int v = Prefs.getIntervalMin(this) + delta;
        if (v < INTERVAL_MIN) {
            v = INTERVAL_MIN;
        }
        if (v > INTERVAL_MAX) {
            v = INTERVAL_MAX;
        }
        Prefs.setIntervalMin(this, v);
        updateRhythmTexts();
    }

    private void changeRest(int delta) {
        int v = Prefs.getRestMin(this) + delta;
        if (v < REST_MIN) {
            v = REST_MIN;
        }
        if (v > REST_MAX) {
            v = REST_MAX;
        }
        Prefs.setRestMin(this, v);
        updateRhythmTexts();
    }

    private void updateRhythmTexts() {
        tvInterval.setText(Prefs.getIntervalMin(this) + " 分钟");
        tvRest.setText(Prefs.getRestMin(this) + " 分钟");
    }

    private void updateExitModeText() {
        boolean back = Prefs.getExitMode(this) == Prefs.EXIT_BACK;
        tvExitMode.setText(back ? "返回键优先" : "直接回桌面");
        tvExitDesc.setText(back
                ? "到点先连按两次返回键退出页面，退不掉再回桌面"
                : "到点直接用 Home 键回到桌面，最干脆");
    }

    private void updateNavModeText() {
        int saved = Prefs.getNavMode(this);
        int eff = UsageHelper.effectiveNavMode(this);
        String effName = UsageHelper.navModeName(eff);

        if (saved == Prefs.NAV_AUTO) {
            int sys = UsageHelper.systemNavMode(this);
            tvNavMode.setText("自动识别");
            tvNavDesc.setText(sys < 0
                    ? "系统未提供导航方式，按「" + effName + "」处理，点一下可手动指定"
                    : "已识别为「" + effName + "」，点一下可手动指定");
        } else {
            tvNavMode.setText(effName);
            tvNavDesc.setText("已手动指定「" + effName
                    + "」。两种方式都能退出 App，这项只是让判定更贴合你的系统");
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
    }

    private void startGuard() {
        Intent i = new Intent(this, LimitService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        List<String> managed = Prefs.managedPackages(this);
        Set<String> pkgs = new HashSet<>(managed);
        int intervalMin = Prefs.getIntervalMin(this);
        long intervalMs = Math.max(1, intervalMin * 60000L);

        if (now - lastTodayAt > 3000) {
            lastTodayAt = now;
            todayTotalMs = managed.isEmpty() ? 0 : UsageHelper.todayUsageMs(this, pkgs);
        }

        String activePkg = LimitService.activePkg;
        if (activePkg != null && LimitService.activeSince > 0) {
            long used = now - LimitService.activeSince;
            long remain = Math.max(0, intervalMs - used);
            tvStateTitle.setText("正在刷");
            tvStateTitle.setTextColor(getResources().getColor(R.color.primary));
            tvStateSub.setText(AppLabel.of(this, activePkg)
                    + " · 还剩 " + UsageHelper.formatRemain(remain) + " 自动退出");
            stateProgress.setProgress((int) Math.min(100, used * 100 / intervalMs));
        } else if (LimitService.restPkg != null && LimitService.restUntil > now) {
            tvStateTitle.setText("休息中");
            tvStateTitle.setTextColor(getResources().getColor(R.color.warn));
            tvStateSub.setText(AppLabel.of(this, LimitService.restPkg) + " 还有 "
                    + UsageHelper.formatRemain(LimitService.restUntil - now) + " 才能再打开");
            stateProgress.setProgress(0);
        } else {
            tvStateTitle.setText("未在刷视频");
            tvStateTitle.setTextColor(getResources().getColor(R.color.text_primary));
            tvStateSub.setText(Prefs.isServiceOn(this)
                    ? "监督中 · 今日已刷 " + UsageHelper.formatDuration(todayTotalMs)
                    : "监督服务未开启，打开下面的开关开始");
            stateProgress.setProgress(0);
        }

        suppressSwitchEvent = true;
        swService.setChecked(Prefs.isServiceOn(this));
        swBoot.setChecked(Prefs.isBootOn(this));
        suppressSwitchEvent = false;

        String managedKey = managed.toString() + "|" + intervalMin;
        if (!managedKey.equals(lastManagedKey)) {
            lastManagedKey = managedKey;
            renderManagedList(managed, intervalMin);
        }

        String permKey = UsageHelper.isAccessibilityOn(this) + "/"
                + UsageHelper.hasUsagePermission(this) + "/"
                + UsageHelper.canOverlay(this);
        if (!permKey.equals(lastPermKey)) {
            lastPermKey = permKey;
            buildPermissionRows();
        }

        if (now - lastChartAt > 30000) {
            lastChartAt = now;
            chart.setWeekData(UsageHelper.weekUsageMs(this, pkgs));
        }
    }

    private void renderManagedList(List<String> managed, int intervalMin) {
        managedContainer.removeAllViews();
        if (managed.isEmpty()) {
            tvManagedEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvManagedEmpty.setVisibility(View.GONE);
        for (String p : managed) {
            View row = LayoutInflater.from(this)
                    .inflate(R.layout.item_managed_row, managedContainer, false);
            TextView name = row.findViewById(R.id.mr_name);
            TextView desc = row.findViewById(R.id.mr_desc);
            name.setText(AppLabel.of(this, p));
            desc.setText("每 " + intervalMin + " 分钟退出");
            managedContainer.addView(row);
        }
    }

    private void buildPermissionRows() {
        permContainer.removeAllViews();
        addPermRow("无障碍（自动退出）", "必需 · 到点自动按返回键退出，退不掉则回桌面",
                UsageHelper.isAccessibilityOn(this),
                v -> UsageHelper.openAccessibilitySettings(this));
        addPermRow("使用情况访问", "必需 · 判断你在刷哪个应用、刷了多久",
                UsageHelper.hasUsagePermission(this),
                v -> UsageHelper.openUsageSettings(this));
        addPermRow("悬浮窗（提醒卡片）", "可选 · 退出时在屏幕上轻提示一次",
                UsageHelper.canOverlay(this),
                v -> UsageHelper.openOverlaySettings(this));
    }

    private void addPermRow(String title, String desc, boolean ok, View.OnClickListener l) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_perm, permContainer, false);
        TextView t = row.findViewById(R.id.perm_title);
        TextView d = row.findViewById(R.id.perm_desc);
        TextView st = row.findViewById(R.id.perm_status);
        Button btn = row.findViewById(R.id.perm_btn);
        t.setText(title);
        d.setText(desc);
        st.setText(ok ? "已开启" : "未开启");
        st.setTextColor(getResources().getColor(ok ? R.color.ok : R.color.warn));
        btn.setVisibility(ok ? View.GONE : View.VISIBLE);
        btn.setOnClickListener(l);
        permContainer.addView(row);
    }
}
