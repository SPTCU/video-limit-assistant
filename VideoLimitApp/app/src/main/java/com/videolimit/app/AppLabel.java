package com.videolimit.app;

import android.content.Context;
import android.content.pm.PackageManager;

/** 包名 -> 应用名 */
public class AppLabel {
    public static String of(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
