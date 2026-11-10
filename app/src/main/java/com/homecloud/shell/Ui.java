package com.homecloud.shell;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/** Small helpers: the whole interface is built in code (no layout XML), with one palette. */
final class Ui {
    static final int BG = 0xFF0F1117;
    static final int SURFACE = 0xFF171A22;
    static final int SURFACE2 = 0xFF222836;
    static final int TEXT = 0xFFE7E9EE;
    static final int MUTED = 0xFF9AA3B2;
    static final int ACCENT = 0xFF5FAFFF;
    static final int DANGER = 0xFFFF6B6B;
    static final int OK = 0xFF6BD68A;
    static final int WARN = 0xFFF2C14E;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    /** Rounded background with a touch ripple. */
    static RippleDrawable ripple(Context c, int color, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(c, color, radiusDp), round(c, 0xFFFFFFFF, radiusDp));
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView button(Context c, String label, int bg, int fg) {
        TextView t = text(c, label, 14, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        t.setBackground(ripple(c, bg, 10));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    static AlertDialog.Builder dialog(Context c) {
        return new AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert);
    }

    static EditText input(Context c, String hint, String value, int inputType) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        return e;
    }

    static final int TYPE_TEXT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    static final int TYPE_PASSWORD = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    static final int TYPE_NUMBER = InputType.TYPE_CLASS_NUMBER;

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }
}
