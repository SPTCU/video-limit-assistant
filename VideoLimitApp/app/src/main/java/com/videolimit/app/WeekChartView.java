package com.videolimit.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Calendar;

/** 最近 7 天使用时长柱状图（纯代码绘制，无第三方依赖） */
public class WeekChartView extends View {

    private long[] values = new long[7];
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint todayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WeekChartView(Context c) {
        super(c);
        init();
    }

    public WeekChartView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        barPaint.setColor(0xFFC7CDF7);
        todayPaint.setColor(0xFF4E6EF2);
        labelPaint.setColor(0xFF6B7080);
        labelPaint.setTextSize(10 * density);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setColor(0xFF191C2B);
        valuePaint.setTextSize(10 * density);
        valuePaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setWeekData(long[] v) {
        if (v != null && v.length == 7) {
            values = v;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float density = getResources().getDisplayMetrics().density;
        float padTop = 20 * density;
        float padBottom = 22 * density;

        long max = 0;
        for (long v : values) {
            if (v > max) max = v;
        }
        if (max < 60L * 60L * 1000L) {
            max = 60L * 60L * 1000L; // 至少 1 小时量程
        }

        float usable = h - padTop - padBottom;
        float slot = w / 7f;
        float barW = slot * 0.42f;

        Calendar cal = Calendar.getInstance();
        for (int i = 0; i < 7; i++) {
            float cx = slot * (i + 0.5f);
            float barH = usable * values[i] / (float) max;
            if (barH < 2 * density && values[i] > 0) {
                barH = 2 * density;
            }
            float top = h - padBottom - barH;
            Paint p = (i == 6) ? todayPaint : barPaint;
            canvas.drawRoundRect(cx - barW / 2f, top, cx + barW / 2f, h - padBottom,
                    6 * density, 6 * density, p);

            String val = shortText(values[i]);
            canvas.drawText(val, cx, top - 5 * density, valuePaint);

            cal.setTimeInMillis(System.currentTimeMillis() - (6 - i) * 24L * 60L * 60L * 1000L);
            String label = (cal.get(Calendar.MONTH) + 1) + "/" + cal.get(Calendar.DAY_OF_MONTH);
            canvas.drawText(label, cx, h - 6 * density, labelPaint);
        }
    }

    private String shortText(long ms) {
        if (ms < 60L * 1000L) {
            return "0";
        }
        long min = ms / 60000L;
        if (min < 60) {
            return min + "分";
        }
        double hours = min / 60.0;
        if (hours < 10) {
            return String.format("%.1f时", hours);
        }
        return ((long) hours) + "时";
    }
}
