package com.videolimit.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 本地配置：自动退出间隔、休息时长、受管应用集合。
 * 全部数据只存本机 SharedPreferences，不联网。
 */
public class Prefs {
    private static final String SP = "video_limit_prefs";
    private static final String KEY_APPS = "limits";
    private static final String KEY_SERVICE = "service_on";
    private static final String KEY_BOOT = "boot_on";
    private static final String KEY_INTERVAL = "interval_min";
    private static final String KEY_REST = "rest_min";
    private static final String KEY_EXIT_MODE = "exit_mode";
    private static final String KEY_NAV_MODE = "nav_mode";

    public static final int DEFAULT_INTERVAL = 10;
    public static final int DEFAULT_REST = 2;

    /** 直接按 Home 回桌面 */
    public static final int EXIT_HOME = 0;
    /** 先连按返回键退页面，退不掉再回桌面 */
    public static final int EXIT_BACK = 1;

    /** 跟随系统自动识别导航方式 */
    public static final int NAV_AUTO = 0;
    /** 三键 / 两键导航栏 */
    public static final int NAV_KEYS = 1;
    /** 全面屏手势 */
    public static final int NAV_GESTURE = 2;

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public static boolean isServiceOn(Context c) {
        return sp(c).getBoolean(KEY_SERVICE, false);
    }

    public static void setServiceOn(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_SERVICE, v).apply();
    }

    public static boolean isBootOn(Context c) {
        return sp(c).getBoolean(KEY_BOOT, true);
    }

    public static void setBootOn(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_BOOT, v).apply();
    }

    /** 连续刷多少分钟后自动退出 */
    public static int getIntervalMin(Context c) {
        int v = sp(c).getInt(KEY_INTERVAL, DEFAULT_INTERVAL);
        if (v < 1) {
            return DEFAULT_INTERVAL;
        }
        return Math.min(v, 180);
    }

    public static void setIntervalMin(Context c, int v) {
        sp(c).edit().putInt(KEY_INTERVAL, Math.max(1, Math.min(v, 180))).apply();
    }

    /** 退出后需要休息多少分钟才能再打开 */
    public static int getRestMin(Context c) {
        int v = sp(c).getInt(KEY_REST, DEFAULT_REST);
        if (v < 1) {
            return DEFAULT_REST;
        }
        return Math.min(v, 60);
    }

    public static void setRestMin(Context c, int v) {
        sp(c).edit().putInt(KEY_REST, Math.max(1, Math.min(v, 60))).apply();
    }

    /** 到点时用哪种方式退出当前应用 */
    public static int getExitMode(Context c) {
        int v = sp(c).getInt(KEY_EXIT_MODE, EXIT_BACK);
        return v == EXIT_HOME ? EXIT_HOME : EXIT_BACK;
    }

    public static void setExitMode(Context c, int v) {
        sp(c).edit().putInt(KEY_EXIT_MODE, v == EXIT_HOME ? EXIT_HOME : EXIT_BACK).apply();
    }

    /** 手机用的导航方式：自动识别 / 三键导航 / 全面屏手势 */
    public static int getNavMode(Context c) {
        int v = sp(c).getInt(KEY_NAV_MODE, NAV_AUTO);
        return (v == NAV_KEYS || v == NAV_GESTURE) ? v : NAV_AUTO;
    }

    public static void setNavMode(Context c, int v) {
        sp(c).edit().putInt(KEY_NAV_MODE,
                (v == NAV_KEYS || v == NAV_GESTURE) ? v : NAV_AUTO).apply();
    }

    private static JSONObject apps(Context c) {
        try {
            return new JSONObject(sp(c).getString(KEY_APPS, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /**
     * 读取开关值。
     * 注意：JSONObject.optBoolean 只认 Boolean 和 "true"/"false" 字符串，
     * 遇到数字会返回默认值 —— 早期版本曾把开关存成 1/0，这里做兼容，
     * 避免升级后旧数据被误判为"未勾选"。
     */
    private static boolean flagOn(JSONObject item) {
        if (item == null) {
            return false;
        }
        Object v = item.opt("on");
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue() != 0;
        }
        if (v instanceof String) {
            String s = (String) v;
            return "true".equalsIgnoreCase(s) || "1".equals(s);
        }
        return false;
    }

    public static boolean isManaged(Context c, String pkg) {
        return flagOn(apps(c).optJSONObject(pkg));
    }

    public static void setManaged(Context c, String pkg, boolean on) {
        JSONObject all = apps(c);
        JSONObject item = all.optJSONObject(pkg);
        if (item == null) {
            item = new JSONObject();
        }
        try {
            item.put("on", on);   // 必须存布尔，不能存 1/0
            all.put(pkg, item);
        } catch (Exception ignored) {
        }
        sp(c).edit().putString(KEY_APPS, all.toString()).apply();
    }

    public static List<String> managedPackages(Context c) {
        List<String> out = new ArrayList<>();
        JSONObject all = apps(c);
        Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (flagOn(all.optJSONObject(k))) {
                out.add(k);
            }
        }
        return out;
    }
}
