package com.videolimit.app;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

/**
 * 到点退出时的轻提示卡片（不是全屏锁定）：
 * 正常情况只显示 2.5 秒、不拦截触摸，提醒「已连续刷 N 分钟，已退出」；
 * 若无障碍未开启（无法自动退出），卡片会变成可点击，引导去开启权限。
 */
public class RestOverlay {

    private final Context ctx;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private View root;
    private boolean shown = false;

    public RestOverlay(Context c) {
        this.ctx = c.getApplicationContext();
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public void show(String appName, int intervalMin, int restMin, boolean needAccess) {
        hide();
        root = LayoutInflater.from(ctx).inflate(R.layout.rest_card, null);

        TextView title = root.findViewById(R.id.rest_title);
        TextView sub = root.findViewById(R.id.rest_sub);
        TextView app = root.findViewById(R.id.rest_app);
        View actions = root.findViewById(R.id.rest_actions);

        title.setText("已连续刷 " + intervalMin + " 分钟");
        if (needAccess) {
            sub.setText("需要开启无障碍权限才能自动退出");
        } else {
            sub.setText("已自动退出，休息 " + restMin + " 分钟后可再打开");
        }
        app.setText(appName);
        actions.setVisibility(needAccess ? View.VISIBLE : View.GONE);

        Button access = root.findViewById(R.id.btn_access);
        Button dismiss = root.findViewById(R.id.btn_dismiss);
        access.setOnClickListener(v -> {
            UsageHelper.openAccessibilitySettings(ctx);
            hide();
        });
        dismiss.setOnClickListener(v -> hide());

        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!needAccess) {
            // 不拦截触摸、不抢焦点：只是轻提示，用户仍可自由操作
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        p.y = dp(84);

        try {
            wm.addView(root, p);
            shown = true;
        } catch (Exception e) {
            root = null;
            return;
        }

        handler.postDelayed(this::hide, needAccess ? 8000 : 2500);
    }

    public void hide() {
        handler.removeCallbacksAndMessages(null);
        if (root != null && shown) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
            }
        }
        root = null;
        shown = false;
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
