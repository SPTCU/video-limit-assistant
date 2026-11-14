package com.videolimit.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

class AppListAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<AppInfo> data;

    AppListAdapter(Context c, List<AppInfo> data) {
        this.inflater = LayoutInflater.from(c);
        this.data = data;
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public AppInfo getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    static class Holder {
        ImageView icon;
        TextView name;
        TextView pkg;
        Switch sw;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final AppInfo info = data.get(position);
        final Context ctx = parent.getContext();
        Holder h;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_app, parent, false);
            h = new Holder();
            h.icon = convertView.findViewById(R.id.app_icon);
            h.name = convertView.findViewById(R.id.app_name);
            h.pkg = convertView.findViewById(R.id.app_pkg);
            h.sw = convertView.findViewById(R.id.sw_limit);
            convertView.setTag(h);
        } else {
            h = (Holder) convertView.getTag();
        }

        h.icon.setImageDrawable(info.icon);
        h.name.setText(info.name);
        h.pkg.setText(info.pkg);

        h.sw.setOnCheckedChangeListener(null);
        h.sw.setChecked(Prefs.isManaged(ctx, info.pkg));
        h.sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.setManaged(ctx, info.pkg, isChecked);
            }
        });

        return convertView;
    }
}
