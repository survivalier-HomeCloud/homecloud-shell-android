package com.homecloud.shell;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The row of keys a phone keyboard lacks: Esc, Tab, Ctrl, Alt, arrows, Home/End, PgUp/PgDn and shell punctuation. */
final class ExtraKeysBar extends HorizontalScrollView {
    private static final int REPEAT_DELAY_MS = 380, REPEAT_EVERY_MS = 55;

    private final TerminalView term;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView ctrlKey, altKey;

    ExtraKeysBar(Context ctx, TerminalView term) {
        super(ctx);
        this.term = term;
        setHorizontalScrollBarEnabled(false);
        setBackgroundColor(Ui.SURFACE);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4));
        addView(row);

        key(row, "ESC", () -> term.sendKeyCode(KeyEvent.KEYCODE_ESCAPE), false);
        key(row, "TAB", () -> term.sendKeyCode(KeyEvent.KEYCODE_TAB), false);
        ctrlKey = key(row, "CTRL", term::toggleCtrl, false);
        altKey = key(row, "ALT", term::toggleAlt, false);
        key(row, "↑", () -> term.sendKeyCode(KeyEvent.KEYCODE_DPAD_UP), true);
        key(row, "↓", () -> term.sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN), true);
        key(row, "←", () -> term.sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT), true);
        key(row, "→", () -> term.sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT), true);
        key(row, "^C", () -> term.sendBytes(new byte[] { 3 }), false);
        key(row, "^D", () -> term.sendBytes(new byte[] { 4 }), false);
        key(row, "|", () -> term.sendText("|"), false);
        key(row, "/", () -> term.sendText("/"), false);
        key(row, "-", () -> term.sendText("-"), false);
        key(row, "~", () -> term.sendText("~"), false);
        key(row, "HOME", () -> term.sendKeyCode(KeyEvent.KEYCODE_MOVE_HOME), false);
        key(row, "END", () -> term.sendKeyCode(KeyEvent.KEYCODE_MOVE_END), false);
        key(row, "PGUP", () -> term.sendKeyCode(KeyEvent.KEYCODE_PAGE_UP), true);
        key(row, "PGDN", () -> term.sendKeyCode(KeyEvent.KEYCODE_PAGE_DOWN), true);
        refreshModifiers();
    }

    private TextView key(LinearLayout row, String label, Runnable action, boolean repeat) {
        Context c = getContext();
        TextView t = Ui.text(c, label, 13, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Ui.dp(c, 44));
        t.setPadding(Ui.dp(c, 10), Ui.dp(c, 9), Ui.dp(c, 10), Ui.dp(c, 9));
        t.setBackground(Ui.ripple(c, Ui.SURFACE2, 8));
        t.setClickable(true);
        t.setFocusable(false);                       // never steal the focus (and the keyboard) from the terminal
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(c, 2), 0, Ui.dp(c, 2), 0);
        row.addView(t, lp);
        if (repeat) {
            final Runnable again = new Runnable() {
                @Override
                public void run() {
                    action.run();
                    handler.postDelayed(this, REPEAT_EVERY_MS);
                }
            };
            t.setOnTouchListener((v, ev) -> {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        action.run();
                        handler.postDelayed(again, REPEAT_DELAY_MS);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        handler.removeCallbacks(again);
                        return true;
                    default:
                        return true;
                }
            });
        } else {
            t.setOnClickListener((View v) -> action.run());
        }
        return t;
    }

    /** Highlights Ctrl / Alt while they are armed for the next key. */
    void refreshModifiers() {
        tint(ctrlKey, term.isStickyCtrl());
        tint(altKey, term.isStickyAlt());
    }

    private void tint(TextView t, boolean on) {
        Context c = getContext();
        t.setTextColor(on ? Ui.BG : Ui.TEXT);
        t.setBackground(Ui.ripple(c, on ? Ui.ACCENT : Ui.SURFACE2, 8));
    }
}
