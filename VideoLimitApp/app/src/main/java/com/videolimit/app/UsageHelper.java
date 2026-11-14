package com.videolimit.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 使用统计相关工具：权限判断、前台应用、时长统计 */
public class UsageHelper {

    public static long todayStart() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    public static String todayKey() {
        Calendar cal = Calendar.getInstance();
        return String.format("%04d%02d%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH));
    }

    public static boolean hasUsagePermission(Context c) {
        try {
            UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - 60L * 60L * 1000L, now);
            return stats != null && !stats.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public static void openUsageSettings(Context c) {
        c.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    public static boolean canOverlay(Context c) {
        return Settings.canDrawOverlays(c);
    }

    public static void openOverlaySettings(Context c) {
        c.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + c.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    public static boolean isAccessibilityOn(Context c) {
        try {
            AccessibilityManager am =
                    (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            List<AccessibilityServiceInfo> list =
                    am.getEnabledAccessibilityServiceList(
                            AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (AccessibilityServiceInfo info : list) {
                if (info.getId() != null && info.getId().startsWith(c.getPackageName())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static void openAccessibilitySettings(Context c) {
        c.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    public static boolean isScreenOn(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 系统当前导航方式：0 三键 / 1 两键 / 2 全面屏手势；-1 读取不到。
     * 这是 Android 10 起系统写入 Settings.Secure 的值，更老的版本没有这一项。
     */
    public static int systemNavMode(Context c) {
        try {
            return Settings.Secure.getInt(c.getContentResolver(), "navigation_mode", -1);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 实际采用的导航方式（把"自动识别"解析成三键或手势） */
    public static int effectiveNavMode(Context c) {
        int saved = Prefs.getNavMode(c);
        if (saved == Prefs.NAV_KEYS || saved == Prefs.NAV_GESTURE) {
            return saved;
        }
        // 2 = 全面屏手势；其余（0 三键 / 1 两键 / -1 读不到）都按导航栏处理
        return systemNavMode(c) == 2 ? Prefs.NAV_GESTURE : Prefs.NAV_KEYS;
    }

    public static boolean isGestureNav(Context c) {
        return effectiveNavMode(c) == Prefs.NAV_GESTURE;
    }

    public static String navModeName(int mode) {
        return mode == Prefs.NAV_GESTURE ? "全面屏手势" : "三键导航";
    }

    /**
     * 当前前台应用包名：取最近 5 分钟窗口内最后一条 RESUMED 事件；
     * 若窗口内无事件（用户长时间停留同一个应用），回退到上次结果。
     */
    public static String foregroundPackage(Context c, String fallback) {
        try {
            long now = System.currentTimeMillis();
            UsageStatsManager usm =
                    (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents events = usm.queryEvents(now - 5L * 60L * 1000L, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                    last = e.getPackageName();
                }
            }
            return last != null ? last : fallback;
        } catch (Exception ex) {
            return fallback;
        }
    }

    /** 使用快照 */
    public static class Snapshot {
        public final Map<String, Long> usage = new HashMap<>();
        public String foreground;
    }

    public static Snapshot snapshotToday(Context c, Set<String> pkgs) {
        return snapshot(c, pkgs, todayStart(), System.currentTimeMillis(), true);
    }

    public static Snapshot snapshot(Context c, Set<String> pkgs,
                                    long begin, long end, boolean live) {
        Snapshot s = new Snapshot();
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents events = usm.queryEvents(begin, end);
            Map<String, Long> lastResume = new HashMap<>();
            UsageEvents.Event e = new UsageEvents.Event();
            long now = System.currentTimeMillis();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                String p = e.getPackageName();
                int t = e.getEventType();
                if (t == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastResume.put(p, e.getTimeStamp());
                    s.foreground = p;
                } else if (t == UsageEvents.Event.ACTIVITY_PAUSED
                        || t == UsageEvents.Event.ACTIVITY_STOPPED) {
                    Long r = lastResume.remove(p);
                    if (r != null) {
                        addTo(s.usage, p, e.getTimeStamp() - r);
                    }
                }
            }
            if (live) {
                for (Map.Entry<String, Long> en : lastResume.entrySet()) {
                    addTo(s.usage, en.getKey(), Math.max(0, now - en.getValue()));
                }
            }
        } catch (Exception ignored) {
        }
        s.usage.keySet().retainAll(pkgs);
        return s;
    }

    public static long todayUsageMs(Context c, Set<String> pkgs) {
        Snapshot s = snapshotToday(c, pkgs);
        long total = 0;
        for (Long v : s.usage.values()) {
            total += v;
        }
        return total;
    }

    /** 最近 7 天每日总时长（index 0 = 6 天前 … index 6 = 今天） */
    public static long[] weekUsageMs(Context c, Set<String> pkgs) {
        long[] out = new long[7];
        long day = 24L * 60L * 60L * 1000L;
        long todayStart = todayStart();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 7; i++) {
            long start = todayStart - (6 - i) * day;
            long end = (i == 6) ? now : todayStart - (5 - i) * day;
            Snapshot s = snapshot(c, pkgs, start, end, false);
            long total = 0;
            for (Long v : s.usage.values()) {
                total += v;
            }
            out[i] = total;
        }
        return out;
    }

    private static void addTo(Map<String, Long> m, String k, long v) {
        Long old = m.get(k);
        m.put(k, old == null ? v : old + v);
    }

    public static String formatDuration(long ms) {
        long min = ms / 60000L;
        if (min < 1) {
            return "不到1分钟";
        }
        if (min < 60) {
            return min + "分钟";
        }
        long h = min / 60;
        long m = min % 60;
        return m == 0 ? (h + "小时") : (h + "小时" + m + "分");
    }

    public static String formatRemain(long ms) {
        long sec = Math.max(0, ms / 1000);
        if (sec >= 60) {
            return (sec / 60) + " 分 " + (sec % 60) + " 秒";
        }
        return sec + " 秒";
    }
}
