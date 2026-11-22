package com.jisudownloader.util;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.widget.ProgressBar;

import androidx.annotation.AttrRes;

import com.jisudownloader.R;

public final class ThemeHelper {

    public static final int[] THEMES = {
            R.style.ThemeJisuBase,
            R.style.ThemeJisuGreen,
            R.style.ThemeJisuOrange,
            R.style.ThemeJisuDark
    };

    public static final String[] THEME_KEYS = {"blue", "green", "orange", "dark"};

    public static int themeRes(String key) {
        for (int i = 0; i < THEME_KEYS.length; i++) {
            if (THEME_KEYS[i].equals(key)) return THEMES[i];
        }
        return THEMES[0];
    }

    public static void apply(Activity a) {
        a.setTheme(themeRes(Prefs.theme()));
    }

    public static int color(Activity a, @AttrRes int attr) {
        TypedValue tv = new TypedValue();
        if (!a.getTheme().resolveAttribute(attr, tv, true)) return 0xFF888888;
        if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return tv.data;
        }
        if (tv.resourceId != 0) {
            try {
                return a.getResources().getColor(tv.resourceId, a.getTheme());
            } catch (Exception e) {
                return 0xFF888888;
            }
        }
        return 0xFF888888;
    }

    public static int primary(Activity a) {
        return color(a, R.attr.colorPrimary);
    }

    public static int cardColor(Activity a) {
        return color(a, R.attr.colorCard);
    }

    public static int textColor(Activity a) {
        return color(a, R.attr.colorText);
    }

    public static int subColor(Activity a) {
        return color(a, R.attr.colorSub);
    }

    public static int lineColor(Activity a) {
        return color(a, R.attr.colorLine);
    }

    public static int bgColor(Activity a) {
        return color(a, R.attr.colorBg);
    }

    public static int trackColor(Activity a) {
        return color(a, R.attr.colorTrack);
    }

    public static void card(Activity a, android.view.View v, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(cardColor(a));
        g.setCornerRadius(dp(a, radius));
        g.setStroke(1, lineColor(a));
        v.setBackground(g);
    }

    public static void progress(Activity a, ProgressBar bar, float ratio, boolean indeterminate) {
        int track = trackColor(a);
        int p = primary(a);
        LayerDrawable ld = new LayerDrawable(new android.graphics.drawable.Drawable[]{
                new ColorDrawable(track),
                new ColorDrawable(p)
        });
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        bar.setProgressDrawable(ld);
        bar.setIndeterminate(indeterminate);
        bar.setMax(1000);
        bar.setProgress(indeterminate ? 0 : (int) (ratio * 1000));
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}