package com.videolimit.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 应用限额管理页：勾选应用、设置每日限额 */
public class AppListActivity extends Activity {

    /** 常见视频/短视频应用包名（排在列表最前） */
    private static final String[] VIDEO_PKGS = {
            "com.ss.android.ugc.aweme",      // 抖音
            "com.smile.gifmaker",            // 快手
            "com.ss.android.ugc.live",       // 抖音火山版
            "com.kuaishou.nebula",           // 快手极速版
            "tv.danmaku.bili",               // 哔哩哔哩
            "com.xingin.xhs",                // 小红书
            "com.sina.weibo",                // 微博
            "com.tencent.qqlive",            // 腾讯视频
            "com.qiyi.video",                // 爱奇艺
            "com.youku.phone",               // 优酷
            "com.ss.android.article.video",  // 西瓜视频
            "com.baidu.haokan",              // 好看视频
    };

    private ListView listView;
    private AppListAdapter adapter;
    private final List<AppInfo> apps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_list);

        Button back = findViewById(R.id.btn_back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        listView = findViewById(R.id.app_list);
        loadApps();

        adapter = new AppListAdapter(this, apps);
        listView.setAdapter(adapter);
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> resolvers =
                pm.queryIntentActivities(main, 0);
        Set<String> preset = new HashSet<>();
        Collections.addAll(preset, VIDEO_PKGS);
        Set<String> seen = new HashSet<>();

        for (android.content.pm.ResolveInfo ri : resolvers) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) {
                continue; // 排除自己与重复项
            }
            AppInfo info = new AppInfo();
            info.pkg = pkg;
            info.name = ri.loadLabel(pm).toString();
            info.icon = ri.loadIcon(pm);
            info.preset = preset.contains(pkg);
            apps.add(info);
        }

        Collections.sort(apps, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo a, AppInfo b) {
                if (a.preset != b.preset) {
                    return a.preset ? -1 : 1;
                }
                boolean la = Prefs.isManaged(AppListActivity.this, a.pkg);
                boolean lb = Prefs.isManaged(AppListActivity.this, b.pkg);
                if (la != lb) {
                    return la ? -1 : 1;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    }
}
