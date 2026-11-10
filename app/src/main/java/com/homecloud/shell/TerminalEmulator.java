package com.homecloud.shell;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * VT100 / xterm terminal emulator: the screen model and the escape sequence parser.
 * No Android dependency. All public methods are synchronized on the emulator: the network thread feeds it,
 * the UI thread reads it (the view holds the lock while it draws).
 */
final class TerminalEmulator {

    /** What the emulator needs from the outside world. Called with the emulator lock held. */
    interface Host {
        /** Answers to terminal queries (cursor position, device attributes...). */
        void write(byte[] data);

        void onTitle(String title);

        void onBell();
    }

    static final int DEFAULT = -1;               // default foreground / background
    static final int TRUECOLOR = 0x01000000;     // flag: the low 24 bits are 0xRRGGBB; otherwise a palette index 0..255
    static final int A_BOLD = 1, A_DIM = 2, A_ITALIC = 4, A_UNDER = 8, A_BLINK = 16, A_INVERSE = 32, A_INVIS = 64, A_STRIKE = 128;
    static final int WIDE_TAIL = -1;             // second cell of a double-width character
    static final int MAX_SCROLLBACK = 5000;

    /** One screen row, stored as parallel arrays (code point, colours, attributes). */
    static final class Line {
        final int[] ch;
        final int[] fg;
        final int[] bg;
        final short[] at;
        boolean wrapped;     // soft-wrapped into the next line (used when copying text)

        Line(int cols, int bgColor) {
            ch = new int[cols];
            fg = new int[cols];
            bg = new int[cols];
            at = new short[cols];
            Arrays.fill(ch, ' ');
            Arrays.fill(fg, DEFAULT);
            Arrays.fill(bg, bgColor);
        }

        void erase(int from, int to, int bgColor) {
            from = Math.max(0, from);
            to = Math.min(to, ch.length);
            for (int i = from; i < to; i++) {
                ch[i] = ' ';
                fg[i] = DEFAULT;
                bg[i] = bgColor;
                at[i] = 0;
            }
        }

        Line resized(int cols) {
            Line n = new Line(cols, DEFAULT);
            int m = Math.min(cols, ch.length);
            System.arraycopy(ch, 0, n.ch, 0, m);
            System.arraycopy(fg, 0, n.fg, 0, m);
            System.arraycopy(bg, 0, n.bg, 0, m);
            System.arraycopy(at, 0, n.at, 0, m);
            n.wrapped = wrapped;
            return n;
        }

        boolean blank() {
            for (int i = 0; i < ch.length; i++) if (ch[i] != ' ' || bg[i] != DEFAULT) return false;
            return true;
        }
    }

    private static final class Saved {
        int x, y, fg = DEFAULT, bg = DEFAULT, at, gl;
        final int[] cs = new int[2];
        boolean wrap, origin;
    }

    // DEC special graphics (ESC ( 0), characters 0x60..0x7E
    private static final int[] DEC_GRAPHICS = {
        0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0xB0, 0xB1, 0x2424, 0x240B, 0x2518, 0x2510, 0x250C, 0x2514, 0x253C, 0x23BA,
        0x23BB, 0x2500, 0x23BC, 0x23BD, 0x251C, 0x2524, 0x2534, 0x252C, 0x2502, 0x2264, 0x2265, 0x3C0, 0x2260, 0xA3, 0xB7
    };

    private static final int S_GROUND = 0, S_ESC = 1, S_ESC_INT = 2, S_CSI = 3, S_OSC = 4, S_STR = 5;

    final Host host;
    int cols, rows;
    int defaultFgRgb = 0xE7E9EE, defaultBgRgb = 0x0F1117;   // answers to OSC 10/11 colour queries

    private Line[] main, alt, screen;
    private final Line[] sb = new Line[MAX_SCROLLBACK];
    private int sbHead, sbCount;

    private int cx, cy;
    private boolean wrapPending;
    private int top, bottom;
    private int curFg = DEFAULT, curBg = DEFAULT, curAt;
    private final Saved saved = new Saved(), saved1049 = new Saved();
    private final int[] charsets = new int[2];
    private int gl;
    private boolean[] tabs;

    private boolean autoWrap = true, origin, insert, newline, cursorVisible = true, appCursor, bracketed, altActive;
    private int mouseMode;
    private boolean mouseSgr;
    private int lastChar;

    // parser
    private int state = S_GROUND;
    private int u8need, u8cp, u8min;
    private final int[] params = new int[32];
    private final boolean[] colonBefore = new boolean[32];
    private int nparams, curParam = -1;
    private boolean pendingColon;
    private int csiPrefix, csiInter, escInter;
    private final StringBuilder osc = new StringBuilder();
    private boolean strEsc, oscBel;

    TerminalEmulator(int cols, int rows, Host host) {
        this.host = host;
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        main = newScreen();
        alt = newScreen();
        screen = main;
        top = 0;
        bottom = this.rows - 1;
        initTabs();
    }

    // ------------------------------------------------------------------ input

    synchronized void feed(byte[] b, int off, int len) {
        for (int i = off; i < off + len; i++) process(b[i] & 0xFF);
    }

    synchronized void feed(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        feed(b, 0, b.length);
    }

    private void process(int b) {
        if (u8need > 0) {
            if ((b & 0xC0) == 0x80) {
                u8cp = (u8cp << 6) | (b & 0x3F);
                if (--u8need == 0) {
                    int cp = u8cp;
                    if (cp < u8min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) cp = 0xFFFD;
                    handle(cp);
                }
                return;
            }
            u8need = 0;
            handle(0xFFFD);          // truncated sequence: report it, then treat b as a fresh byte
        }
        if (b < 0x80) handle(b);
        else if (b >= 0xC2 && b <= 0xDF) { u8need = 1; u8cp = b & 0x1F; u8min = 0x80; }
        else if (b >= 0xE0 && b <= 0xEF) { u8need = 2; u8cp = b & 0x0F; u8min = 0x800; }
        else if (b >= 0xF0 && b <= 0xF4) { u8need = 3; u8cp = b & 0x07; u8min = 0x10000; }
        else handle(0xFFFD);
    }

    private void handle(int cp) {
        if (state == S_OSC || state == S_STR) {
            if (strEsc) {
                strEsc = false;
                if (cp == '\\') {
                    if (state == S_OSC) endOsc();
                    state = S_GROUND;
                    return;
                }
                state = S_ESC;
                handle(cp);
                return;
            }
            if (cp == 0x1B) { strEsc = true; return; }
            if (cp == 7) {
                oscBel = true;
                if (state == S_OSC) endOsc();
                state = S_GROUND;
                return;
            }
            if (cp == 0x18 || cp == 0x1A) { state = S_GROUND; return; }
            if (state == S_OSC && osc.length() < 4096) osc.appendCodePoint(cp);
            return;
        }
        if (cp < 0x20 || cp == 0x7F) {
            control(cp);
            return;
        }
        switch (state) {
            case S_GROUND: printChar(cp); break;
            case S_ESC: escDispatch(cp); break;
            case S_ESC_INT: escIntDispatch(cp); break;
            case S_CSI: csiByte(cp); break;
            default: state = S_GROUND; break;
        }
    }

    private void control(int c) {
        switch (c) {
            case 0x1B: state = S_ESC; break;
            case 0x18: case 0x1A: state = S_GROUND; break;
            case 0x07: host.onBell(); break;
            case 0x08: if (cx > 0) cx--; wrapPending = false; break;
            case 0x09: nextTab(); wrapPending = false; break;
            case 0x0A: case 0x0B: case 0x0C:
                index();
                if (newline) cx = 0;
                break;
            case 0x0D: cx = 0; wrapPending = false; break;
            case 0x0E: gl = 1; break;
            case 0x0F: gl = 0; break;
            default: break;
        }
    }

    private void escDispatch(int cp) {
        switch (cp) {
            case '[': state = S_CSI; resetCsi(); return;
            case ']': state = S_OSC; osc.setLength(0); strEsc = false; oscBel = false; return;
            case 'P': case 'X': case '^': case '_': state = S_STR; strEsc = false; return;
            case '(': case ')': case '*': case '+': case '-': case '.': case '/': case ' ': case '#': case '%':
                escInter = cp;
                state = S_ESC_INT;
                return;
            case '7': save(saved); break;
            case '8': restore(saved); break;
            case 'D': index(); break;
            case 'E': cx = 0; index(); break;
            case 'M': reverseIndex(); break;
            case 'H': tabs[Math.min(cx, cols - 1)] = true; break;
            case 'c': reset(); break;
            default: break;          // '=' '>' keypad modes, stray ST...
        }
        state = S_GROUND;
    }

    private void escIntDispatch(int cp) {
        if (escInter == '(') charsets[0] = cp == '0' ? 1 : 0;
        else if (escInter == ')') charsets[1] = cp == '0' ? 1 : 0;
        else if (escInter == '#' && cp == '8') {
            for (int r = 0; r < rows; r++) Arrays.fill(screen[r].ch, 'E');
        }
        state = S_GROUND;
    }

    // ------------------------------------------------------------------ CSI

    private void resetCsi() {
        nparams = 0;
        curParam = -1;
        pendingColon = false;
        csiPrefix = 0;
        csiInter = 0;
    }

    private void pushParam() {
        if (nparams < params.length) {
            params[nparams] = curParam;
            colonBefore[nparams] = pendingColon;
            nparams++;
        }
        curParam = -1;
    }

    private void csiByte(int cp) {
        if (cp >= '0' && cp <= '9') {
            curParam = (curParam < 0 ? 0 : curParam) * 10 + (cp - '0');
            if (curParam > 65535) curParam = 65535;
        } else if (cp == ';' || cp == ':') {
            pushParam();
            pendingColon = cp == ':';
        } else if (cp >= 0x3C && cp <= 0x3F) {
            csiPrefix = cp;
        } else if (cp >= 0x20 && cp <= 0x2F) {
            csiInter = cp;
        } else if (cp >= 0x40 && cp <= 0x7E) {
            if (curParam >= 0 || nparams > 0) pushParam();
            state = S_GROUND;
            csiDispatch(cp);
        } else {
            state = S_GROUND;
        }
    }

    /** Parameter i, or def when absent. */
    private int p(int i, int def) {
        return (i < nparams && params[i] >= 0) ? params[i] : def;
    }

    /** Count parameter: absent or 0 means 1. */
    private int pn(int i) {
        int v = p(i, 1);
        return v == 0 ? 1 : v;
    }

    private void csiDispatch(int f) {
        if (csiPrefix == '?') {
            privateSeq(f);
            return;
        }
        if (csiPrefix == '>') {
            if (f == 'c') reply("\033[>1;10;0c");
            return;
        }
        if (csiPrefix != 0) return;
        if (csiInter != 0) {
            if (csiInter == '!' && f == 'p') softReset();
            return;
        }
        switch (f) {
            case '@': insertChars(pn(0)); break;
            case 'A': moveRel(0, -pn(0)); break;
            case 'B': case 'e': moveRel(0, pn(0)); break;
            case 'C': case 'a': moveRel(pn(0), 0); break;
            case 'D': moveRel(-pn(0), 0); break;
            case 'E': moveRel(0, pn(0)); cx = 0; break;
            case 'F': moveRel(0, -pn(0)); cx = 0; break;
            case 'G': case '`': cx = clamp(pn(0) - 1, 0, cols - 1); wrapPending = false; break;
            case 'H': case 'f': cursorTo(pn(0) - 1, pn(1) - 1); break;
            case 'I': for (int n = pn(0); n > 0; n--) nextTab(); wrapPending = false; break;
            case 'J': eraseDisplay(p(0, 0)); break;
            case 'K': eraseLine(p(0, 0)); break;
            case 'L': insertLines(pn(0)); break;
            case 'M': deleteLines(pn(0)); break;
            case 'P': deleteChars(pn(0)); break;
            case 'S': scrollUp(top, bottom, pn(0), true); break;
            case 'T': scrollDown(top, bottom, pn(0)); break;
            case 'X': eraseChars(pn(0)); break;
            case 'Z': for (int n = pn(0); n > 0; n--) prevTab(); wrapPending = false; break;
            case 'b': if (lastChar > 0) for (int n = Math.min(pn(0), 4096); n > 0; n--) printChar(lastChar); break;
            case 'c': if (p(0, 0) == 0) reply("\033[?62;22c"); break;
            case 'd': cursorTo(pn(0) - 1, cx); break;
            case 'g':
                if (p(0, 0) == 0) tabs[Math.min(cx, cols - 1)] = false;
                else if (p(0, 0) == 3) Arrays.fill(tabs, false);
                break;
            case 'h': setModes(true); break;
            case 'l': setModes(false); break;
            case 'm': sgr(); break;
            case 'n': deviceStatus(false); break;
            case 'r': setScrollRegion(); break;
            case 's': save(saved); break;
            case 'u': restore(saved); break;
            case 't': if (p(0, 0) == 18) reply("\033[8;" + rows + ";" + cols + "t"); break;
            default: break;
        }
    }

    private void privateSeq(int f) {
        if (f == 'n') {
            deviceStatus(true);
            return;
        }
        if (f != 'h' && f != 'l') return;
        boolean on = f == 'h';
        for (int i = 0; i < nparams; i++) {
            int m = p(i, 0);
            switch (m) {
                case 1: appCursor = on; break;
                case 6: origin = on; cursorTo(0, 0); break;
                case 7: autoWrap = on; break;
                case 25: cursorVisible = on; break;
                case 47: case 1047: if (on) enterAlt(false); else leaveAlt(false); break;
                case 1048: if (on) save(saved); else restore(saved); break;
                case 1049: if (on) enterAlt(true); else leaveAlt(true); break;
                case 1000: case 1002: case 1003: mouseMode = on ? m : 0; break;
                case 1006: mouseSgr = on; break;
                case 2004: bracketed = on; break;
                default: break;
            }
        }
    }

    private void setModes(boolean on) {
        for (int i = 0; i < nparams; i++) {
            int m = p(i, 0);
            if (m == 4) insert = on;
            else if (m == 20) newline = on;
        }
    }

    private void deviceStatus(boolean priv) {
        int n = p(0, 0);
        int row = (origin ? cy - top : cy) + 1, col = cx + 1;
        if (priv) {
            if (n == 6) reply("\033[?" + row + ";" + col + "R");
            return;
        }
        if (n == 5) reply("\033[0n");
        else if (n == 6) reply("\033[" + row + ";" + col + "R");
    }

    private void sgr() {
        if (nparams == 0) {
            curFg = DEFAULT;
            curBg = DEFAULT;
            curAt = 0;
            return;
        }
        for (int i = 0; i < nparams; i++) {
            int v = params[i] < 0 ? 0 : params[i];
            switch (v) {
                case 0: curFg = DEFAULT; curBg = DEFAULT; curAt = 0; break;
                case 1: curAt |= A_BOLD; break;
                case 2: curAt |= A_DIM; break;
                case 3: curAt |= A_ITALIC; break;
                case 4:
                    if (i + 1 < nparams && colonBefore[i + 1]) {      // 4:0 .. 4:5 underline styles
                        if (params[i + 1] == 0) curAt &= ~A_UNDER; else curAt |= A_UNDER;
                        i++;
                    } else {
                        curAt |= A_UNDER;
                    }
                    break;
                case 5: case 6: curAt |= A_BLINK; break;
                case 7: curAt |= A_INVERSE; break;
                case 8: curAt |= A_INVIS; break;
                case 9: curAt |= A_STRIKE; break;
                case 21: curAt |= A_UNDER; break;
                case 22: curAt &= ~(A_BOLD | A_DIM); break;
                case 23: curAt &= ~A_ITALIC; break;
                case 24: curAt &= ~A_UNDER; break;
                case 25: curAt &= ~A_BLINK; break;
                case 27: curAt &= ~A_INVERSE; break;
                case 28: curAt &= ~A_INVIS; break;
                case 29: curAt &= ~A_STRIKE; break;
                case 38: i = extColor(i, 0); break;
                case 48: i = extColor(i, 1); break;
                case 58: i = extColor(i, 2); break;
                case 39: curFg = DEFAULT; break;
                case 49: curBg = DEFAULT; break;
                case 59: break;
                default:
                    if (v >= 30 && v <= 37) curFg = v - 30;
                    else if (v >= 40 && v <= 47) curBg = v - 40;
                    else if (v >= 90 && v <= 97) curFg = v - 90 + 8;
                    else if (v >= 100 && v <= 107) curBg = v - 100 + 8;
                    break;
            }
        }
    }

    /** Extended colour (38/48/58): 5;n or 2;r;g;b, with ';' or ':' separators. target: 0 fg, 1 bg, 2 ignored. Returns the last consumed index. */
    private int extColor(int i, int target) {
        if (i + 1 >= nparams) return i;
        int mode = params[i + 1];
        boolean colon = colonBefore[i + 1];
        int color;
        int last;
        if (mode == 5) {
            if (i + 2 >= nparams) return i + 1;
            color = params[i + 2] < 0 ? 0 : params[i + 2] & 0xFF;
            last = i + 2;
        } else if (mode == 2) {
            int start = i + 2;
            if (colon && nparams - start >= 4) start++;           // 38:2::r:g:b carries a colour space id
            if (start + 2 >= nparams) return nparams - 1;
            int r = Math.max(0, params[start]) & 0xFF, g = Math.max(0, params[start + 1]) & 0xFF, b = Math.max(0, params[start + 2]) & 0xFF;
            color = TRUECOLOR | (r << 16) | (g << 8) | b;
            last = start + 2;
        } else {
            return i + 1;
        }
        if (target == 0) curFg = color;
        else if (target == 1) curBg = color;
        return last;
    }

    private void setScrollRegion() {
        int t = p(0, 1) - 1;
        int b = p(1, rows) - 1;
        if (t < 0) t = 0;
        if (b > rows - 1) b = rows - 1;
        if (t < b) {
            top = t;
            bottom = b;
        } else {
            top = 0;
            bottom = rows - 1;
        }
        cursorTo(0, 0);
    }

    // ------------------------------------------------------------------ OSC

    private void endOsc() {
        String s = osc.toString();
        int semi = s.indexOf(';');
        if (semi <= 0) return;
        int n;
        try {
            n = Integer.parseInt(s.substring(0, semi));
        } catch (NumberFormatException e) {
            return;
        }
        String arg = s.substring(semi + 1);
        if (n == 0 || n == 2) {
            host.onTitle(arg);
        } else if ((n == 10 || n == 11) && arg.equals("?")) {
            int rgb = n == 10 ? defaultFgRgb : defaultBgRgb;
            String c = String.format("rgb:%04x/%04x/%04x", ((rgb >> 16) & 0xFF) * 257, ((rgb >> 8) & 0xFF) * 257, (rgb & 0xFF) * 257);
            reply("\033]" + n + ";" + c + (oscBel ? "\007" : "\033\\"));
        }
    }

    private void reply(String s) {
        host.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    // ------------------------------------------------------------------ printing

    private void printChar(int cp) {
        if (charsets[gl] == 1 && cp >= 0x60 && cp <= 0x7E) cp = DEC_GRAPHICS[cp - 0x60];
        int w = width(cp);
        if (w == 0 || w > cols) return;
        if (wrapPending) {
            if (autoWrap) {
                screen[cy].wrapped = true;
                cx = 0;
                index();
            }
            wrapPending = false;
        }
        if (w == 2 && cx == cols - 1) {
            if (autoWrap) {
                Line l = screen[cy];
                l.erase(cx, cx + 1, curBg);
                l.wrapped = true;
                cx = 0;
                index();
            } else {
                return;
            }
        }
        Line l = screen[cy];
        if (insert) insertChars(w);
        if (l.ch[cx] == WIDE_TAIL && cx > 0) l.ch[cx - 1] = ' ';
        if (cx + w < cols && l.ch[cx + w] == WIDE_TAIL) l.ch[cx + w] = ' ';
        l.ch[cx] = cp;
        l.fg[cx] = curFg;
        l.bg[cx] = curBg;
        l.at[cx] = (short) curAt;
        if (w == 2) {
            l.ch[cx + 1] = WIDE_TAIL;
            l.fg[cx + 1] = curFg;
            l.bg[cx + 1] = curBg;
            l.at[cx + 1] = (short) curAt;
        }
        lastChar = cp;
        if (cx + w >= cols) {
            cx = cols - 1;
            wrapPending = true;
        } else {
            cx += w;
        }
    }

    /** Display width of a code point: 0 for combining / zero-width, 2 for East Asian wide and emoji, else 1. */
    static int width(int c) {
        if (c < 0x20 || (c >= 0x7F && c < 0xA0)) return 0;
        if (c < 0x300) return 1;
        if ((c >= 0x300 && c <= 0x36F) || (c >= 0x200B && c <= 0x200F) || (c >= 0x2028 && c <= 0x202E)
                || (c >= 0x2060 && c <= 0x2064) || (c >= 0x20D0 && c <= 0x20FF) || (c >= 0xFE00 && c <= 0xFE0F)
                || (c >= 0xFE20 && c <= 0xFE2F) || c == 0xFEFF || (c >= 0x1AB0 && c <= 0x1AFF)
                || (c >= 0x1DC0 && c <= 0x1DFF) || (c >= 0xE0100 && c <= 0xE01EF)) return 0;
        if ((c >= 0x1100 && c <= 0x115F) || (c >= 0x2E80 && c <= 0x303E) || (c >= 0x3041 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xFE30 && c <= 0xFE6F)
                || (c >= 0xFF00 && c <= 0xFF60) || (c >= 0xFFE0 && c <= 0xFFE6) || (c >= 0x1F300 && c <= 0x1F64F)
                || (c >= 0x1F680 && c <= 0x1F6FF) || (c >= 0x1F900 && c <= 0x1F9FF) || (c >= 0x20000 && c <= 0x3FFFD)) return 2;
        return 1;
    }

    // ------------------------------------------------------------------ cursor and scrolling

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void cursorTo(int row, int col) {
        cy = origin ? clamp(row + top, top, bottom) : clamp(row, 0, rows - 1);
        cx = clamp(col, 0, cols - 1);
        wrapPending = false;
    }

    private void moveRel(int dx, int dy) {
        if (dy < 0) cy = Math.max(cy >= top ? top : 0, cy + dy);
        else if (dy > 0) cy = Math.min(cy <= bottom ? bottom : rows - 1, cy + dy);
        cx = clamp(cx + dx, 0, cols - 1);
        wrapPending = false;
    }

    private void index() {
        if (cy == bottom) scrollUp(top, bottom, 1, true);
        else if (cy < rows - 1) cy++;
        wrapPending = false;
    }

    private void reverseIndex() {
        if (cy == top) scrollDown(top, bottom, 1);
        else if (cy > 0) cy--;
        wrapPending = false;
    }

    private Line blankLine() {
        return new Line(cols, curBg);
    }

    private void scrollUp(int t, int b, int n, boolean history) {
        n = Math.min(n, b - t + 1);
        for (int k = 0; k < n; k++) {
            Line removed = screen[t];
            if (history && t == 0 && b == rows - 1 && !altActive) pushScrollback(removed);
            System.arraycopy(screen, t + 1, screen, t, b - t);
            screen[b] = blankLine();
        }
    }

    private void scrollDown(int t, int b, int n) {
        n = Math.min(n, b - t + 1);
        for (int k = 0; k < n; k++) {
            System.arraycopy(screen, t, screen, t + 1, b - t);
            screen[t] = blankLine();
        }
    }

    private void pushScrollback(Line l) {
        if (sbCount < MAX_SCROLLBACK) {
            sb[(sbHead + sbCount) % MAX_SCROLLBACK] = l;
            sbCount++;
        } else {
            sb[sbHead] = l;
            sbHead = (sbHead + 1) % MAX_SCROLLBACK;
        }
    }

    private Line popScrollback() {
        sbCount--;
        int i = (sbHead + sbCount) % MAX_SCROLLBACK;
        Line l = sb[i];
        sb[i] = null;
        return l;
    }

    private void clearScrollback() {
        Arrays.fill(sb, null);
        sbHead = 0;
        sbCount = 0;
    }

    private void insertLines(int n) {
        if (cy < top || cy > bottom) return;
        scrollDown(cy, bottom, n);
        cx = 0;
        wrapPending = false;
    }

    private void deleteLines(int n) {
        if (cy < top || cy > bottom) return;
        scrollUp(cy, bottom, n, false);
        cx = 0;
        wrapPending = false;
    }

    private void insertChars(int n) {
        Line l = screen[cy];
        n = Math.min(n, cols - cx);
        int move = cols - cx - n;
        if (move > 0) {
            System.arraycopy(l.ch, cx, l.ch, cx + n, move);
            System.arraycopy(l.fg, cx, l.fg, cx + n, move);
            System.arraycopy(l.bg, cx, l.bg, cx + n, move);
            System.arraycopy(l.at, cx, l.at, cx + n, move);
        }
        l.erase(cx, cx + n, curBg);
    }

    private void deleteChars(int n) {
        Line l = screen[cy];
        n = Math.min(n, cols - cx);
        int move = cols - cx - n;
        if (move > 0) {
            System.arraycopy(l.ch, cx + n, l.ch, cx, move);
            System.arraycopy(l.fg, cx + n, l.fg, cx, move);
            System.arraycopy(l.bg, cx + n, l.bg, cx, move);
            System.arraycopy(l.at, cx + n, l.at, cx, move);
        }
        l.erase(cols - n, cols, curBg);
    }

    private void eraseChars(int n) {
        screen[cy].erase(cx, Math.min(cols, cx + n), curBg);
    }

    private void eraseLine(int mode) {
        Line l = screen[cy];
        if (mode == 0) l.erase(cx, cols, curBg);
        else if (mode == 1) l.erase(0, cx + 1, curBg);
        else if (mode == 2) l.erase(0, cols, curBg);
        if (mode != 1) l.wrapped = false;
    }

    private void eraseDisplay(int mode) {
        switch (mode) {
            case 0:
                eraseLine(0);
                for (int r = cy + 1; r < rows; r++) screen[r] = blankLine();
                break;
            case 1:
                for (int r = 0; r < cy; r++) screen[r] = blankLine();
                eraseLine(1);
                break;
            case 2:
                for (int r = 0; r < rows; r++) screen[r] = blankLine();
                break;
            case 3:
                clearScrollback();
                break;
            default:
                break;
        }
    }

    private void initTabs() {
        tabs = new boolean[cols];
        for (int i = 8; i < cols; i += 8) tabs[i] = true;
    }

    private void nextTab() {
        int x = cx + 1;
        while (x < cols - 1 && !tabs[x]) x++;
        cx = Math.min(x, cols - 1);
    }

    private void prevTab() {
        int x = cx - 1;
        while (x > 0 && !tabs[x]) x--;
        cx = Math.max(x, 0);
    }

    private void save(Saved s) {
        s.x = cx;
        s.y = cy;
        s.fg = curFg;
        s.bg = curBg;
        s.at = curAt;
        s.gl = gl;
        s.cs[0] = charsets[0];
        s.cs[1] = charsets[1];
        s.wrap = wrapPending;
        s.origin = origin;
    }

    private void restore(Saved s) {
        cx = Math.min(s.x, cols - 1);
        cy = Math.min(s.y, rows - 1);
        curFg = s.fg;
        curBg = s.bg;
        curAt = s.at;
        gl = s.gl;
        charsets[0] = s.cs[0];
        charsets[1] = s.cs[1];
        wrapPending = s.wrap;
        origin = s.origin;
    }

    private void enterAlt(boolean saveCursor) {
        if (altActive) return;
        if (saveCursor) save(saved1049);
        screen = alt;
        altActive = true;
        top = 0;
        bottom = rows - 1;
        for (int r = 0; r < rows; r++) alt[r] = new Line(cols, DEFAULT);
    }

    private void leaveAlt(boolean restoreCursor) {
        if (!altActive) return;
        screen = main;
        altActive = false;
        top = 0;
        bottom = rows - 1;
        if (restoreCursor) restore(saved1049);
    }

    private void softReset() {
        curFg = DEFAULT;
        curBg = DEFAULT;
        curAt = 0;
        autoWrap = true;
        origin = false;
        insert = false;
        newline = false;
        cursorVisible = true;
        appCursor = false;
        gl = 0;
        charsets[0] = 0;
        charsets[1] = 0;
        top = 0;
        bottom = rows - 1;
        save(saved);
    }

    private void reset() {
        if (altActive) leaveAlt(false);
        softReset();
        bracketed = false;
        mouseMode = 0;
        mouseSgr = false;
        cx = 0;
        cy = 0;
        wrapPending = false;
        state = S_GROUND;
        initTabs();
        for (int r = 0; r < rows; r++) main[r] = new Line(cols, DEFAULT);
    }

    private Line[] newScreen() {
        Line[] s = new Line[rows];
        for (int r = 0; r < rows; r++) s[r] = new Line(cols, DEFAULT);
        return s;
    }

    // ------------------------------------------------------------------ resize

    synchronized void resize(int newCols, int newRows) {
        if (newCols < 1 || newRows < 1 || (newCols == cols && newRows == rows)) return;
        boolean onMain = !altActive;
        Line[] a = adjust(screen, newCols, newRows, true, onMain);
        Line[] o = adjust(onMain ? alt : main, newCols, newRows, false, false);
        if (onMain) {
            main = a;
            alt = o;
            screen = main;
        } else {
            alt = a;
            main = o;
            screen = alt;
        }
        cols = newCols;
        rows = newRows;
        top = 0;
        bottom = rows - 1;
        cx = Math.min(cx, cols - 1);
        cy = clamp(cy, 0, rows - 1);
        wrapPending = false;
        initTabs();
    }

    private Line[] adjust(Line[] old, int nc, int nr, boolean active, boolean useHistory) {
        ArrayList<Line> list = new ArrayList<>(Arrays.asList(old));
        if (nr < list.size()) {
            int remove = list.size() - nr;
            while (remove > 0) {
                int last = list.size() - 1;
                if (!active || (last > cy && list.get(last).blank())) {
                    list.remove(last);
                } else {
                    Line first = list.remove(0);
                    if (useHistory) pushScrollback(first);
                    cy--;
                }
                remove--;
            }
            if (cy < 0) cy = 0;
        } else if (nr > list.size()) {
            int add = nr - list.size();
            if (active && useHistory) {
                while (add > 0 && sbCount > 0) {
                    list.add(0, popScrollback());
                    cy++;
                    add--;
                }
            }
            while (add-- > 0) list.add(new Line(nc, DEFAULT));
        }
        Line[] res = new Line[nr];
        for (int i = 0; i < nr; i++) {
            Line l = list.get(i);
            res[i] = l.ch.length == nc ? l : l.resized(nc);
        }
        return res;
    }

    // ------------------------------------------------------------------ read access for the view

    /** Number of lines the view can scroll through (history + screen; the alternate screen has no history). */
    synchronized int totalLines() {
        return altActive ? rows : sbCount + rows;
    }

    /** Line by absolute index: 0 is the oldest history line. Null when out of range. */
    synchronized Line lineAt(int idx) {
        int off = altActive ? 0 : sbCount;
        if (idx < 0 || idx >= off + rows) return null;
        if (idx < off) return sb[(sbHead + idx) % MAX_SCROLLBACK];
        return screen[idx - off];
    }

    synchronized int historySize() {
        return altActive ? 0 : sbCount;
    }

    synchronized int cursorX() { return cx; }

    synchronized int cursorY() { return cy; }

    synchronized boolean cursorVisible() { return cursorVisible; }

    synchronized boolean appCursorKeys() { return appCursor; }

    synchronized boolean bracketedPaste() { return bracketed; }

    synchronized boolean isAltScreen() { return altActive; }

    synchronized int mouseMode() { return mouseMode; }

    synchronized boolean mouseSgr() { return mouseSgr; }

    /** Characters of one line as a string, one char per cell (for URL detection). */
    synchronized String rowString(int idx) {
        Line l = lineAt(idx);
        if (l == null) return "";
        char[] c = new char[l.ch.length];
        for (int i = 0; i < c.length; i++) {
            int v = l.ch[i];
            c[i] = (v == WIDE_TAIL) ? ' ' : (v >= 0x10000 ? '?' : (char) v);
        }
        return new String(c);
    }

    /** Text between two cells (inclusive, absolute line indices, already ordered). Soft-wrapped lines are joined. */
    synchronized String textBetween(int r1, int c1, int r2, int c2) {
        StringBuilder out = new StringBuilder();
        for (int r = r1; r <= r2; r++) {
            Line l = lineAt(r);
            if (l == null) continue;
            int from = r == r1 ? Math.max(0, c1) : 0;
            int to = r == r2 ? Math.min(c2, l.ch.length - 1) : l.ch.length - 1;
            StringBuilder row = new StringBuilder();
            for (int i = from; i <= to; i++) {
                if (l.ch[i] != WIDE_TAIL) row.appendCodePoint(l.ch[i]);
            }
            if (r != r2 || to == l.ch.length - 1) {
                int n = row.length();
                while (n > 0 && row.charAt(n - 1) == ' ') n--;
                row.setLength(n);
            }
            out.append(row);
            if (r != r2 && !l.wrapped) out.append('\n');
        }
        return out.toString();
    }
}
