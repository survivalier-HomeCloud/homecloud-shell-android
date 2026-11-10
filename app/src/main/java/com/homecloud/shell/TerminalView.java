package com.homecloud.shell;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.OverScroller;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Draws a TerminalEmulator and turns touches, keys and IME text into terminal input. */
final class TerminalView extends View {
    interface Client {
        /** The visible grid changed (size, font, orientation, keyboard). */
        void onGrid(int cols, int rows);

        void onOpenUrl(String url);

        /** Ctrl / Alt sticky state changed. */
        void onModifiersChanged();
    }

    private static final float MIN_SP = 7f, MAX_SP = 32f;
    private static final int DEFAULT_FG = 0xFFE7E9EE, DEFAULT_BG = 0xFF0F1117;
    private static final int STYLE_MASK = TerminalEmulator.A_BOLD | TerminalEmulator.A_ITALIC | TerminalEmulator.A_UNDER | TerminalEmulator.A_STRIKE;
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'`]+");

    private Client client;
    private Session session;
    private TerminalEmulator emu;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint();
    private final Typeface regular, bold;
    private final int[] palette = new int[256];
    private final char[] runBuf = new char[512];

    private float textSp = ProfileStore.DEFAULT_FONT_SP;
    private float cellW = 10, cellH = 20, baseline = 15;
    private int cols, rows;
    private int scrollLines;          // lines scrolled back from the live bottom
    private int lastTotal;
    private float scrollRemainder;

    private boolean stickyCtrl, stickyAlt;

    private boolean selecting, hasSelection;
    private int selAr, selAc, selBr, selBc;       // anchor and end, absolute line index + column
    private ActionMode actionMode;

    private final GestureDetector gestures;
    private final ScaleGestureDetector scaler;
    private final OverScroller flinger;

    TerminalView(Context ctx) {
        super(ctx);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(DEFAULT_BG);
        regular = loadFont(ctx, "fonts/JetBrainsMono-Regular.ttf", Typeface.MONOSPACE);
        bold = loadFont(ctx, "fonts/JetBrainsMono-Bold.ttf", Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        buildPalette();
        flinger = new OverScroller(ctx);
        gestures = new GestureDetector(ctx, new GestureListener());
        gestures.setIsLongpressEnabled(true);
        scaler = new ScaleGestureDetector(ctx, new ScaleListener());
        applyMetrics();
    }

    private static Typeface loadFont(Context ctx, String asset, Typeface fallback) {
        try {
            return Typeface.createFromAsset(ctx.getAssets(), asset);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private void buildPalette() {
        int[] ansi = {
            0x1B1E27, 0xE5534B, 0x57AB5A, 0xC69026, 0x539BF5, 0xB083F0, 0x39C5CF, 0xADBAC7,
            0x545D68, 0xFF938A, 0x6BC46D, 0xDAAA3F, 0x6CB6FF, 0xDCBDFB, 0x56D4DD, 0xCDD9E5
        };
        for (int i = 0; i < 16; i++) palette[i] = 0xFF000000 | ansi[i];
        for (int i = 16; i < 232; i++) {
            int n = i - 16, r = n / 36, g = (n / 6) % 6, b = n % 6;
            palette[i] = 0xFF000000 | (level(r) << 16) | (level(g) << 8) | level(b);
        }
        for (int i = 232; i < 256; i++) {
            int v = 8 + 10 * (i - 232);
            palette[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
    }

    private static int level(int v) {
        return v == 0 ? 0 : 55 + 40 * v;
    }

    // ------------------------------------------------------------------ setup

    void setClient(Client c) { client = c; }

    float textSizeSp() { return textSp; }

    void setTextSizeSp(float sp) {
        textSp = Math.max(MIN_SP, Math.min(MAX_SP, sp));
        applyMetrics();
        recomputeGrid(false);
        invalidate();
    }

    private void applyMetrics() {
        float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSp, getResources().getDisplayMetrics());
        paint.setTextSize(px);
        paint.setTypeface(regular);
        cellW = paint.measureText("M");
        Paint.FontMetrics fm = paint.getFontMetrics();
        cellH = (float) Math.ceil(fm.descent - fm.ascent);
        baseline = -fm.ascent;
    }

    void attach(Session s) {
        session = s;
        emu = s.emu;
        emu.defaultFgRgb = DEFAULT_FG & 0xFFFFFF;
        emu.defaultBgRgb = DEFAULT_BG & 0xFFFFFF;
        scrollLines = 0;
        lastTotal = emu.totalLines();
        clearSelection();
        recomputeGrid(true);
        invalidate();
    }

    void detach() {
        session = null;
        emu = null;
        clearSelection();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recomputeGrid(false);
    }

    private void recomputeGrid(boolean force) {
        int nc = Math.max(2, (int) (getWidth() / cellW));
        int nr = Math.max(1, (int) (getHeight() / cellH));
        if (getWidth() == 0 || getHeight() == 0) return;
        if (force || nc != cols || nr != rows) {
            cols = nc;
            rows = nr;
            if (client != null) client.onGrid(cols, rows);
        }
    }

    // ------------------------------------------------------------------ drawing

    private int resolve(int c, boolean isFg, boolean boldText) {
        if (c == TerminalEmulator.DEFAULT) return isFg ? DEFAULT_FG : DEFAULT_BG;
        if ((c & TerminalEmulator.TRUECOLOR) != 0) return 0xFF000000 | (c & 0xFFFFFF);
        if (isFg && boldText && c < 8) c += 8;
        return palette[c & 0xFF];
    }

    private int cellFg(TerminalEmulator.Line l, int i) {
        int at = l.at[i];
        boolean inv = (at & TerminalEmulator.A_INVERSE) != 0;
        int c = inv ? resolve(l.bg[i], false, false) : resolve(l.fg[i], true, (at & TerminalEmulator.A_BOLD) != 0);
        if ((at & TerminalEmulator.A_DIM) != 0) c = blend(c, inv ? resolve(l.fg[i], true, false) : resolve(l.bg[i], false, false));
        return c;
    }

    private int cellBg(TerminalEmulator.Line l, int i) {
        boolean inv = (l.at[i] & TerminalEmulator.A_INVERSE) != 0;
        return inv ? resolve(l.fg[i], true, (l.at[i] & TerminalEmulator.A_BOLD) != 0) : resolve(l.bg[i], false, false);
    }

    private static int blend(int a, int b) {
        int r = (((a >> 16) & 0xFF) + ((b >> 16) & 0xFF)) / 2;
        int g = (((a >> 8) & 0xFF) + ((b >> 8) & 0xFF)) / 2;
        int bl = ((a & 0xFF) + (b & 0xFF)) / 2;
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(DEFAULT_BG);
        TerminalEmulator e = emu;
        if (e == null) return;
        synchronized (e) {
            int total = e.totalLines();
            int hist = e.historySize();
            if (scrollLines > 0 && total > lastTotal) scrollLines += total - lastTotal;    // keep the viewed lines in place
            lastTotal = total;
            scrollLines = Math.max(0, Math.min(scrollLines, hist));
            int er = e.rows, ec = e.cols;
            int first = total - er - scrollLines;
            int visibleRows = Math.min(er, (int) Math.ceil(getHeight() / cellH));
            for (int r = 0; r < visibleRows; r++) {
                TerminalEmulator.Line line = e.lineAt(first + r);
                if (line != null) drawLine(c, line, Math.min(ec, line.ch.length), r * cellH);
            }
            if (scrollLines == 0 && e.cursorVisible()) drawCursor(c, e);
            if (hasSelection) drawSelection(c, first, visibleRows, ec);
        }
    }

    private void drawLine(Canvas c, TerminalEmulator.Line line, int n, float y) {
        for (int i = 0; i < n; ) {                                  // backgrounds, merged per run
            int bg = cellBg(line, i);
            int j = i + 1;
            while (j < n && cellBg(line, j) == bg) j++;
            if (bg != DEFAULT_BG) {
                fill.setColor(bg);
                c.drawRect(i * cellW, y, j * cellW, y + cellH, fill);
            }
            i = j;
        }
        for (int i = 0; i < n; ) {                                  // text
            int ch = line.ch[i];
            int at = line.at[i];
            if (ch == TerminalEmulator.WIDE_TAIL || (at & TerminalEmulator.A_INVIS) != 0) {
                i++;
                continue;
            }
            int fg = cellFg(line, i);
            if (!simple(line, i, n)) {
                int w = (i + 1 < n && line.ch[i + 1] == TerminalEmulator.WIDE_TAIL) ? 2 : 1;
                if (ch != ' ') drawGlyph(c, ch, at, fg, i * cellW, y);
                i += w;
                continue;
            }
            int len = 0, j = i;
            while (j < n && len < runBuf.length && simple(line, j, n)
                    && (line.at[j] & STYLE_MASK) == (at & STYLE_MASK) && (line.at[j] & TerminalEmulator.A_INVIS) == 0
                    && cellFg(line, j) == fg) {
                runBuf[len++] = (char) line.ch[j];
                j++;
            }
            drawRun(c, len, at, fg, i * cellW, y);
            i = j;
        }
    }

    /** A cell that can be drawn as part of a text run: one BMP char, single width. */
    private static boolean simple(TerminalEmulator.Line l, int i, int n) {
        int ch = l.ch[i];
        if (ch < 0x20 || ch >= 0x10000 || (ch >= 0xD800 && ch <= 0xDFFF)) return false;
        return !(i + 1 < n && l.ch[i + 1] == TerminalEmulator.WIDE_TAIL);
    }

    private void style(int at, int fg) {
        paint.setTypeface((at & TerminalEmulator.A_BOLD) != 0 ? bold : regular);
        paint.setTextSkewX((at & TerminalEmulator.A_ITALIC) != 0 ? -0.2f : 0f);
        paint.setColor(fg);
    }

    private void drawRun(Canvas c, int len, int at, int fg, float x, float y) {
        style(at, fg);
        c.drawText(runBuf, 0, len, x, y + baseline, paint);
        decorations(c, at, fg, x, y, len * cellW);
    }

    private void drawGlyph(Canvas c, int cp, int at, int fg, float x, float y) {
        style(at, fg);
        c.drawText(new String(Character.toChars(cp)), x, y + baseline, paint);
    }

    private void decorations(Canvas c, int at, int fg, float x, float y, float w) {
        float t = Math.max(1f, cellH / 14f);
        fill.setColor(fg);
        if ((at & TerminalEmulator.A_UNDER) != 0) c.drawRect(x, y + baseline + t, x + w, y + baseline + 2 * t, fill);
        if ((at & TerminalEmulator.A_STRIKE) != 0) c.drawRect(x, y + cellH * 0.5f, x + w, y + cellH * 0.5f + t, fill);
    }

    private void drawCursor(Canvas c, TerminalEmulator e) {
        int cx = e.cursorX(), cy = e.cursorY();
        TerminalEmulator.Line l = e.lineAt(e.historySize() + cy);
        boolean wide = l != null && cx + 1 < l.ch.length && l.ch[cx + 1] == TerminalEmulator.WIDE_TAIL;
        float x = cx * cellW, y = cy * cellH, w = (wide ? 2 : 1) * cellW;
        if (hasFocus()) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0xB35FAFFF);
            c.drawRect(x, y, x + w, y + cellH, fill);
        } else {
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(Math.max(1f, cellH / 14f));
            fill.setColor(0xFF5FAFFF);
            c.drawRect(x + 1, y + 1, x + w - 1, y + cellH - 1, fill);
            fill.setStyle(Paint.Style.FILL);
        }
    }

    private void drawSelection(Canvas c, int first, int visibleRows, int ec) {
        int[] s = orderedSelection();
        fill.setColor(0x665FAFFF);
        for (int r = 0; r < visibleRows; r++) {
            int idx = first + r;
            if (idx < s[0] || idx > s[2]) continue;
            int from = idx == s[0] ? s[1] : 0;
            int to = idx == s[2] ? s[3] : ec - 1;
            c.drawRect(from * cellW, r * cellH, (to + 1) * cellW, (r + 1) * cellH, fill);
        }
    }

    // ------------------------------------------------------------------ coordinates

    /** First visible absolute line index. */
    private int firstLine() {
        TerminalEmulator e = emu;
        if (e == null) return 0;
        synchronized (e) {
            return e.totalLines() - e.rows - Math.max(0, Math.min(scrollLines, e.historySize()));
        }
    }

    private int rowAt(float y) {
        return firstLine() + Math.max(0, (int) (y / cellH));
    }

    private int colAt(float x) {
        return Math.max(0, Math.min(cols - 1, (int) (x / cellW)));
    }

    private int[] orderedSelection() {
        boolean swap = selBr < selAr || (selBr == selAr && selBc < selAc);
        return swap ? new int[] { selBr, selBc, selAr, selAc } : new int[] { selAr, selAc, selBr, selBc };
    }

    // ------------------------------------------------------------------ scrolling

    private void scrollBy(int lines) {
        TerminalEmulator e = emu;
        if (e == null || lines == 0) return;
        int max = e.historySize();
        int n = Math.max(0, Math.min(scrollLines + lines, max));
        if (n != scrollLines) {
            scrollLines = n;
            invalidate();
        }
    }

    @Override
    public void computeScroll() {
        if (flinger.computeScrollOffset()) {
            int target = (int) (flinger.getCurrY() / cellH);
            TerminalEmulator e = emu;
            int max = e == null ? 0 : e.historySize();
            scrollLines = Math.max(0, Math.min(target, max));
            invalidate();
        }
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        scaler.onTouchEvent(ev);
        if (selecting) {
            if (ev.getAction() == MotionEvent.ACTION_MOVE) {
                selBr = rowAt(ev.getY());
                selBc = colAt(ev.getX());
                invalidate();
            } else if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                selecting = false;
                showSelectionMenu();
            }
            return true;
        }
        if (!scaler.isInProgress()) gestures.onTouchEvent(ev);
        return true;
    }

    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            flinger.forceFinished(true);
            return true;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            if (hasSelection) {
                clearSelection();
                return true;
            }
            requestFocus();
            showKeyboard();
            TerminalEmulator em = emu;
            if (em == null) return true;
            int row = rowAt(e.getY()), col = colAt(e.getX());
            String url = urlAt(row, col);
            if (url != null && client != null) {
                client.onOpenUrl(url);
                return true;
            }
            if (em.mouseMode() != 0 && scrollLines == 0) {
                int r = Math.max(0, row - firstLine());
                sendMouse(0, col, r, true);
                sendMouse(0, col, r, false);
            }
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            TerminalEmulator em = emu;
            if (em == null) return true;
            scrollRemainder += dy / cellH;           // finger up (dy > 0) shows newer lines
            int lines = (int) scrollRemainder;
            if (lines == 0) return true;
            scrollRemainder -= lines;
            if (em.isAltScreen()) {
                wheel(lines);          // finger up = read further down
            } else {
                scrollBy(-lines);
            }
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            TerminalEmulator em = emu;
            if (em == null || em.isAltScreen()) return true;
            int max = (int) (em.historySize() * cellH);
            flinger.fling(0, (int) (scrollLines * cellH), 0, (int) vy, 0, 0, 0, max);
            postInvalidateOnAnimation();
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            if (emu == null) return;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            selecting = true;
            hasSelection = true;
            selAr = selBr = rowAt(e.getY());
            selAc = selBc = colAt(e.getX());
            invalidate();
        }
    }

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector d) {
            float next = textSp * d.getScaleFactor();
            if (Math.abs(next - textSp) >= 0.25f) setTextSizeSp(Math.round(next * 2) / 2f);
            return true;
        }
    }

    /** Mouse wheel (when the program asked for the mouse) or arrow keys, for full-screen programs like less or vim. */
    private void wheel(int lines) {
        TerminalEmulator em = emu;
        if (em == null) return;
        int n = Math.min(Math.abs(lines), 20);
        boolean up = lines < 0;
        for (int i = 0; i < n; i++) {
            if (em.mouseMode() != 0) sendMouse(up ? 64 : 65, cols / 2, rows / 2, true);
            else sendRaw(csiKey(up ? 'A' : 'B', 1));
        }
    }

    private void sendMouse(int button, int col, int row, boolean press) {
        TerminalEmulator em = emu;
        if (em == null) return;
        if (em.mouseSgr()) {
            sendRaw("\033[<" + button + ";" + (col + 1) + ";" + (row + 1) + (press ? "M" : "m"));
        } else if (press || button < 64) {
            if (col > 222 || row > 222) return;
            int b = press ? button : 3;
            sendRaw("\033[M" + (char) (32 + b) + (char) (33 + col) + (char) (33 + row));
        }
    }

    // ------------------------------------------------------------------ links

    private String urlAt(int row, int col) {
        TerminalEmulator e = emu;
        if (e == null) return null;
        int s0 = row;
        while (s0 > 0) {
            TerminalEmulator.Line p = e.lineAt(s0 - 1);
            if (p == null || !p.wrapped) break;
            s0--;
        }
        StringBuilder sb = new StringBuilder();
        int offset = -1;
        for (int r = s0; ; r++) {
            TerminalEmulator.Line l = e.lineAt(r);
            if (l == null) break;
            if (r == row) offset = sb.length() + col;
            sb.append(e.rowString(r));
            if (!l.wrapped) break;
        }
        if (offset < 0) return null;
        Matcher m = URL.matcher(sb);
        while (m.find()) {
            if (offset >= m.start() && offset < m.end()) {
                String u = m.group();
                int n = u.length();
                while (n > 0 && ".,;:!?)]}'\"".indexOf(u.charAt(n - 1)) >= 0) n--;
                return u.substring(0, n);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ selection and clipboard

    private void clearSelection() {
        selecting = false;
        hasSelection = false;
        if (actionMode != null) {
            ActionMode m = actionMode;
            actionMode = null;
            m.finish();
        }
        invalidate();
    }

    private String selectedText() {
        TerminalEmulator e = emu;
        if (e == null || !hasSelection) return "";
        int[] s = orderedSelection();
        return e.textBetween(s[0], s[1], s[2], s[3]);
    }

    private void showSelectionMenu() {
        if (!hasSelection) return;
        actionMode = startActionMode(new ActionMode.Callback2() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(0, 1, 0, "Copier");
                menu.add(0, 2, 1, "Coller");
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() == 1) copyToClipboard(selectedText());
                else paste();
                mode.finish();
                return true;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                if (actionMode == mode) {
                    actionMode = null;
                    hasSelection = false;
                    invalidate();
                }
            }

            @Override
            public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                int[] s = orderedSelection();
                int first = firstLine();
                float top = Math.max(0, (s[0] - first) * cellH);
                float bottom = Math.max(cellH, (s[2] - first + 1) * cellH);
                outRect.set(0, (int) top, getWidth(), (int) Math.min(getHeight(), bottom));
            }
        }, ActionMode.TYPE_FLOATING);
    }

    private void copyToClipboard(String s) {
        if (s.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("HomeCloud Shell", s));
        Ui.toast(getContext(), "Copié");
    }

    void copyAll() {
        TerminalEmulator e = emu;
        if (e == null) return;
        String s = e.textBetween(0, 0, e.totalLines() - 1, e.cols - 1);
        copyToClipboard(s.replaceAll("\\s+$", ""));
    }

    void paste() {
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (!cm.hasPrimaryClip() || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) return;
        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(getContext());
        if (t == null || t.length() == 0) return;
        String s = t.toString().replace("\r\n", "\n").replace('\n', '\r');
        TerminalEmulator e = emu;
        if (e != null && e.bracketedPaste()) s = "\033[200~" + s + "\033[201~";
        sendRaw(s);
    }

    void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(this, 0);
    }

    // ------------------------------------------------------------------ input

    boolean isStickyCtrl() { return stickyCtrl; }

    boolean isStickyAlt() { return stickyAlt; }

    void toggleCtrl() {
        stickyCtrl = !stickyCtrl;
        if (client != null) client.onModifiersChanged();
    }

    void toggleAlt() {
        stickyAlt = !stickyAlt;
        if (client != null) client.onModifiersChanged();
    }

    private void clearSticky() {
        if (stickyCtrl || stickyAlt) {
            stickyCtrl = false;
            stickyAlt = false;
            if (client != null) client.onModifiersChanged();
        }
    }

    /** Sends raw terminal bytes and jumps back to the live screen. */
    void sendRaw(String s) {
        sendBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    void sendBytes(byte[] b) {
        Session s = session;
        if (s == null) return;
        s.sendInput(b);
        if (scrollLines != 0) {
            scrollLines = 0;
            invalidate();
        }
        if (hasSelection && !selecting) clearSelection();
    }

    /** Typed text (soft keyboard, extra keys): applies the sticky Ctrl / Alt. */
    void sendText(CharSequence text) {
        if (text.length() == 0) return;
        String s = text.toString().replace("\r\n", "\r").replace('\n', '\r');
        if (stickyCtrl && s.length() == 1) {
            int c = ctrlChar(s.charAt(0));
            if (c >= 0) s = String.valueOf((char) c);
        }
        if (stickyAlt) s = "\033" + s;
        clearSticky();
        sendRaw(s);
    }

    private static int ctrlChar(int u) {
        if (u >= 'a' && u <= 'z') return u - 'a' + 1;
        if (u >= 'A' && u <= 'Z') return u - 'A' + 1;
        if (u >= '@' && u <= '_') return u - '@';
        if (u == ' ') return 0;
        if (u == '?') return 127;
        return -1;
    }

    private String csiKey(char f, int mod) {
        TerminalEmulator e = emu;
        boolean app = e != null && e.appCursorKeys();
        if (mod == 1) return (app ? "\033O" : "\033[") + f;
        return "\033[1;" + mod + f;
    }

    private static String tilde(int n, int mod) {
        return mod == 1 ? "\033[" + n + "~" : "\033[" + n + ";" + mod + "~";
    }

    private static String fkey(int n, int mod) {
        if (n <= 4) {
            char f = (char) ('P' + n - 1);
            return mod == 1 ? "\033O" + f : "\033[1;" + mod + f;
        }
        int[] code = { 15, 17, 18, 19, 20, 21, 23, 24 };
        return tilde(code[n - 5], mod);
    }

    /** Synthetic key press (extra keys bar). */
    void sendKeyCode(int keyCode) {
        handleKey(keyCode, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
    }

    /** Maps an Android key event to terminal bytes. Returns false when the key is not ours (system keys). */
    private boolean handleKey(int keyCode, KeyEvent ev) {
        boolean shift = ev.isShiftPressed();
        boolean ctrl = ev.isCtrlPressed() || stickyCtrl;
        boolean alt = ev.isAltPressed() || stickyAlt;
        int mod = 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0);
        String seq = null;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: seq = csiKey('A', mod); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: seq = csiKey('B', mod); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: seq = csiKey('C', mod); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: seq = csiKey('D', mod); break;
            case KeyEvent.KEYCODE_MOVE_HOME: seq = csiKey('H', mod); break;
            case KeyEvent.KEYCODE_MOVE_END: seq = csiKey('F', mod); break;
            case KeyEvent.KEYCODE_INSERT: seq = tilde(2, mod); break;
            case KeyEvent.KEYCODE_FORWARD_DEL: seq = tilde(3, mod); break;
            case KeyEvent.KEYCODE_PAGE_UP: seq = tilde(5, mod); break;
            case KeyEvent.KEYCODE_PAGE_DOWN: seq = tilde(6, mod); break;
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: seq = alt ? "\033\r" : "\r"; break;
            case KeyEvent.KEYCODE_TAB: seq = shift ? "\033[Z" : "\t"; break;
            case KeyEvent.KEYCODE_ESCAPE: seq = "\033"; break;
            case KeyEvent.KEYCODE_DEL: seq = alt ? "\033\177" : (ctrl ? "\010" : "\177"); break;
            default:
                if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
                    seq = fkey(keyCode - KeyEvent.KEYCODE_F1 + 1, mod);
                }
                break;
        }
        if (seq != null) {
            clearSticky();
            sendRaw(seq);
            return true;
        }
        int meta = ev.getMetaState() & ~(KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK | KeyEvent.META_META_MASK);
        int u = ev.getUnicodeChar(meta);
        if ((u & KeyCharacterMap.COMBINING_ACCENT) != 0) return true;       // dead key: wait for the next one
        if (u <= 0) return false;
        String s = new String(Character.toChars(u));
        if (ctrl) {
            int c = ctrlChar(u);
            if (c >= 0) s = String.valueOf((char) c);
        }
        if (alt) s = "\033" + s;
        clearSticky();
        sendRaw(s);
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE: case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_APP_SWITCH: case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_CAMERA:
                return super.onKeyDown(keyCode, event);
            default:
                return handleKey(keyCode, event) || super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
        return new TerminalInputConnection(this);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        invalidate();
    }

    /**
     * Soft keyboards compose words; the terminal has no text buffer to compose in, so composing text is sent as it
     * changes (backspaces for what was replaced) and a commit that matches it sends nothing more.
     */
    private static final class TerminalInputConnection extends BaseInputConnection {
        private final TerminalView view;
        private String composing = "";

        TerminalInputConnection(TerminalView v) {
            super(v, false);
            view = v;
        }

        private void replaceComposing(String next) {
            int common = 0;
            int max = Math.min(composing.length(), next.length());
            while (common < max && composing.charAt(common) == next.charAt(common)) common++;
            StringBuilder sb = new StringBuilder();
            for (int i = composing.length() - common; i > 0; i--) sb.append('\177');
            sb.append(next, common, next.length());
            if (sb.length() > 0) view.sendRaw(sb.toString());
            composing = next;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            replaceComposing(text.toString());
            return true;
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            String s = text.toString();
            if (!composing.isEmpty()) {
                if (!s.equals(composing)) replaceComposing(s);
                composing = "";
            } else {
                view.sendText(s);
            }
            return true;
        }

        @Override
        public boolean finishComposingText() {
            composing = "";
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int before, int after) {
            if (!composing.isEmpty()) composing = composing.substring(0, Math.max(0, composing.length() - before));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < before; i++) sb.append('\177');
            if (sb.length() > 0) view.sendRaw(sb.toString());
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                composing = "";
                view.handleKey(event.getKeyCode(), event);
            }
            return true;
        }
    }
}
