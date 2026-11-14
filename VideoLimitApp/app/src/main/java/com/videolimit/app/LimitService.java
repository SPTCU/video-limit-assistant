package com.videolimit.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 监督服务：按「连续使用时长」计时，到点自动把用户踢出视频应用，
 * 并在随后的休息期内拦截重新进入。
 *
 * 状态机要点：
 *  - 连续会话：某个受管应用持续前台即累计；短暂切出（≤20 秒）不重置；
 *  - 到点：调用无障碍回桌面，并记录冷却截止时间；
 *  - 冷却期：再次打开该应用会立刻再被退出，直到休息结束。
 */
public class LimitService extends Service {

    public static volatile boolean isRunning = false;
    /** 正在计时的应用（供界面展示） */
    public static volatile String activePkg = null;
    public static volatile long activeSince = 0;
    /** 正在休息的应用 */
    public static volatile String restPkg = null;
    public static volatile long restUntil = 0;

    private static final int NOTI_ID = 1001;
    private static final String CHANNEL = "limit_guard";
    /** 短暂切出该时长内不打断连续计时 */
    private static final long LEAVE_GRACE_MS = 20_000L;
    /** 退出动作的最小间隔，避免每秒重复触发 */
    private static final long KICK_THROTTLE_MS = 1500L;
    private static final long STATS_INTERVAL_MS = 5000L;
    /** 连按返回键的间隔 */
    private static final long BACK_STEP_MS = 260L;
    /** 连按返回后仍未离开当前应用，则回桌面兜底 */
    private static final long BACK_FALLBACK_MS = 900L;
    /** 全面屏手势下系统返回动画更长，间隔与兜底时间都要放宽，否则会误判为"没退出" */
    private static final long BACK_STEP_GESTURE_MS = 340L;
    private static final long BACK_FALLBACK_GESTURE_MS = 1300L;

    private static volatile LimitService sInstance;

    private Handler handler;
    private RestOverlay overlay;
    private final Map<String, Long> sessions = new HashMap<>();
    private final Map<String, Long> pausedAt = new HashMap<>();
    private final Map<String, Long> cooldownUntil = new HashMap<>();

    private String lastForeground;
    private long lastKickAt;
    private long lastStatsAt;
    private long todayTotalMs;

    /** 休息期内再次进入受管应用时应立即退出 */
    public static boolean shouldKick(String pkg) {
        return pkg != null && pkg.equals(restPkg) && System.currentTimeMillis() < restUntil;
    }

    /** 立即执行一次退出（不受节流限制），由无障碍窗口事件触发 */
    public static void kickNow() {
        LimitService s = sInstance;
        if (s != null) {
            s.kick(true);
        }
    }

    private final Runnable tick = () -> {
        step();
        scheduleNext();
    };

    private void scheduleNext() {
        if (handler != null) {
            handler.postDelayed(tick, 1000);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        sInstance = this;
        createChannel();
        Notification n = buildNotification("监督中", "正在统计使用时长…");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTI_ID, n);
        }
        handler = new Handler(Looper.getMainLooper());
        handler.post(tick);
    }

    private void step() {
        List<String> managed = Prefs.managedPackages(this);
        if (managed.isEmpty()) {
            clearAllState();
            notify("刷视频限时助手", "尚未选择受管应用，去首页「管理」里勾选");
            return;
        }

        long now = System.currentTimeMillis();
        Set<String> set = new HashSet<>(managed);
        int intervalMin = Prefs.getIntervalMin(this);
        int restMin = Prefs.getRestMin(this);

        // 灭屏视为离开
        String fg;
        if (UsageHelper.isScreenOn(this)) {
            fg = UsageHelper.foregroundPackage(this, lastForeground);
            lastForeground = fg;
        } else {
            fg = null;
        }

        // 维护各应用的连续会话（短暂切出不重置）
        for (String p : managed) {
            if (p.equals(fg)) {
                pausedAt.remove(p);
                if (!sessions.containsKey(p)) {
                    sessions.put(p, now);
                }
            } else {
                Long leave = pausedAt.get(p);
                if (leave == null) {
                    pausedAt.put(p, now);
                } else if (now - leave > LEAVE_GRACE_MS) {
                    sessions.remove(p);
                    pausedAt.remove(p);
                }
            }
        }

        // 清理过期冷却
        Iterator<Map.Entry<String, Long>> it = cooldownUntil.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= now) {
                it.remove();
            }
        }

        // 不在受管应用里
        if (fg == null || !set.contains(fg)) {
            activePkg = null;
            activeSince = 0;
            refreshStats(now, set, false);
            if (restUntil > now && restPkg != null) {
                notify("休息中：" + AppLabel.of(this, restPkg),
                        "还有 " + UsageHelper.formatRemain(restUntil - now) + " 才能再打开");
            } else {
                notify("刷视频限时助手",
                        "监督中 · 今日已刷 " + UsageHelper.formatDuration(todayTotalMs));
            }
            return;
        }

        // 休息期内又打开 -> 立刻再退出
        Long cd = cooldownUntil.get(fg);
        if (cd != null && cd > now) {
            activePkg = null;
            activeSince = 0;
            restPkg = fg;
            restUntil = cd;
            sessions.remove(fg);
            pausedAt.put(fg, now);
            kick();
            refreshStats(now, set, false);
            notify("休息中：" + AppLabel.of(this, fg),
                    "还有 " + UsageHelper.formatRemain(cd - now) + " 才能再打开");
            return;
        }

        long since = sessions.containsKey(fg) ? sessions.get(fg) : now;
        long used = now - since;
        long intervalMs = intervalMin * 60000L;

        if (used >= intervalMs) {
            long restUntilTs = now + restMin * 60000L;
            cooldownUntil.put(fg, restUntilTs);
            restPkg = fg;
            restUntil = restUntilTs;
            activePkg = null;
            activeSince = 0;
            sessions.remove(fg);
            pausedAt.put(fg, now);
            kick();
            if (UsageHelper.canOverlay(this)) {
                showRestCard(fg, intervalMin, restMin, !HomeKeyService.isConnected());
            }
            refreshStats(now, set, false);
            notify("休息中：" + AppLabel.of(this, fg),
                    "已连续刷 " + intervalMin + " 分钟，休息 " + restMin + " 分钟");
            return;
        }

        activePkg = fg;
        activeSince = since;
        restPkg = null;
        restUntil = 0;
        refreshStats(now, set, true);
        notify("正在刷：" + AppLabel.of(this, fg),
                "还剩 " + UsageHelper.formatRemain(intervalMs - used) + " 自动退出"
                        + " · 今日 " + UsageHelper.formatDuration(todayTotalMs));
    }

    /**
     * 执行退出动作。
     * 默认「返回键优先」：连按两次返回，像用户自己一层层退出去；
     * 若 900ms 后仍停留在受管应用里（有些 App 把返回键改成了切页），回桌面兜底。
     * 也可在首页切到「直接回桌面」。
     */
    private void kick() {
        kick(false);
    }

    private void kick(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastKickAt < KICK_THROTTLE_MS) {
            return;
        }
        lastKickAt = now;
        // 清空前台缓存，避免退出后仍按旧值误判「还在刷」
        lastForeground = null;

        boolean useBack = Prefs.getExitMode(this) == Prefs.EXIT_BACK
                && HomeKeyService.isConnected();

        if (!useBack) {
            HomeKeyService.goHome();
            return;
        }

        HomeKeyService.goBack();
        if (handler == null) {
            return;
        }
        // 手势导航的系统返回动画更长，按导航方式放宽判定时间
        boolean gesture = UsageHelper.isGestureNav(this);
        final long stepMs = gesture ? BACK_STEP_GESTURE_MS : BACK_STEP_MS;
        final long fallbackMs = gesture ? BACK_FALLBACK_GESTURE_MS : BACK_FALLBACK_MS;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                HomeKeyService.goBack();
            }
        }, stepMs);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                fallbackHome();
            }
        }, fallbackMs);
    }

    /** 返回键没能把用户带离受管应用时，用 Home 键兜底 */
    private void fallbackHome() {
        if (!isRunning) {
            return;
        }
        String fg = UsageHelper.foregroundPackage(this, null);
        if (fg != null && Prefs.isManaged(this, fg)) {
            lastForeground = null;
            HomeKeyService.goHome();
        }
    }

    private void showRestCard(String pkg, int intervalMin, int restMin, boolean needAccess) {
        try {
            if (overlay == null) {
                overlay = new RestOverlay(this);
            }
            overlay.show(AppLabel.of(this, pkg), intervalMin, restMin, needAccess);
        } catch (Exception ignored) {
        }
    }

    private void refreshStats(long now, Set<String> pkgs, boolean force) {
        if (!force && now - lastStatsAt < STATS_INTERVAL_MS) {
            return;
        }
        lastStatsAt = now;
        todayTotalMs = UsageHelper.todayUsageMs(this, pkgs);
    }

    private void clearAllState() {
        sessions.clear();
        pausedAt.clear();
        cooldownUntil.clear();
        activePkg = null;
        activeSince = 0;
        restPkg = null;
        restUntil = 0;
        if (overlay != null) {
            overlay.hide();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (sInstance == this) {
            sInstance = null;
        }
        clearAllState();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "监督服务", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setDescription("显示当前刷视频倒计时与休息状态");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }

    private void notify(String title, String text) {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTI_ID, buildNotification(title, text));
        }
    }
}
