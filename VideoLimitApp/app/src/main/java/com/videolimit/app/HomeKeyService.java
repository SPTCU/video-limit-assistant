package com.videolimit.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务：提供「退出当前应用」的能力，是自动退出的核心。
 * 支持两种动作：模拟导航栏返回键（goBack）、回桌面（goHome）。
 * 到点时由监督服务按用户选择的退出方式调用；用户若在休息期内又切回受管应用，
 * 窗口变化事件会立刻把用户再送出去。
 * 本服务不读取屏幕内容。
 */
public class HomeKeyService extends AccessibilityService {

    private static volatile HomeKeyService instance;

    public static boolean isConnected() {
        return instance != null;
    }

    /** 返回 true 表示已成功触发回桌面 */
    public static boolean goHome() {
        HomeKeyService s = instance;
        if (s == null) {
            return false;
        }
        try {
            return s.performGlobalAction(GLOBAL_ACTION_HOME);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 模拟一次导航栏「返回」，相当于用户自己按返回键退一层页面。
     * 只退一层，通常需要连按几次才能真正退出应用。
     */
    public static boolean goBack() {
        HomeKeyService s = instance;
        if (s == null) {
            return false;
        }
        try {
            return s.performGlobalAction(GLOBAL_ACTION_BACK);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null) {
            return;
        }
        // 休息期内又打开受管应用 -> 立即再次退出（走与到点相同的退出方式）
        if (LimitService.shouldKick(pkg.toString())) {
            if (LimitService.isRunning) {
                LimitService.kickNow();
            } else {
                goHome();
            }
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
