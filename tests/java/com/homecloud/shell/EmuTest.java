package com.homecloud.shell;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class EmuTest {
    static int fails = 0;
    static StringBuilder replies = new StringBuilder();
    static String title = null; static int bells = 0;
    static TerminalEmulator mk(int c, int r) {
        replies.setLength(0);
        return new TerminalEmulator(c, r, new TerminalEmulator.Host() {
            public void write(byte[] d) { replies.append(new String(d, StandardCharsets.ISO_8859_1)); }
            public void onTitle(String t) { title = t; }
            public void onBell() { bells++; }
        });
    }
    static String row(TerminalEmulator e, int r) {   // screen row (not history)
        TerminalEmulator.Line l = e.lineAt(e.historySize() + r);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < l.ch.length; i++) if (l.ch[i] != TerminalEmulator.WIDE_TAIL) s.appendCodePoint(l.ch[i]);
        return s.toString().replaceAll(" +$", "");
    }
    static void eq(String name, Object got, Object want) {
        if (!Objects.equals(got, want)) { fails++; System.out.println("FAIL " + name + ": got [" + got + "] want [" + want + "]"); }
        else System.out.println("ok   " + name);
    }
    public static void main(String[] a) throws Exception {
        TerminalEmulator e = mk(20, 5);
        e.feed("hello\r\nworld");
        eq("basic row0", row(e, 0), "hello"); eq("basic row1", row(e, 1), "world");
        eq("cursor", e.cursorX() + "," + e.cursorY(), "5,1");

        e = mk(10, 4);
        e.feed("0123456789AB");
        eq("autowrap row0", row(e, 0), "0123456789"); eq("autowrap row1", row(e, 1), "AB");
        eq("wrapped flag", e.lineAt(0).wrapped, true);

        // exactly cols chars: no wrap until next char
        e = mk(5, 3); e.feed("abcde");
        eq("pending wrap cursor", e.cursorX() + "," + e.cursorY(), "4,0");
        e.feed("f"); eq("pending wrap flushed", row(e, 1), "f");

        // scrolling pushes history
        e = mk(10, 3);
        e.feed("1\r\n2\r\n3\r\n4\r\n5");
        eq("history size", e.historySize(), 2); eq("screen 0", row(e, 0), "3"); eq("screen 2", row(e, 2), "5");
        eq("history text", e.textBetween(0, 0, 0, 9), "1");

        // CUP, EL, ED
        e = mk(10, 4); e.feed("aaaaaaaaaa\r\nbbbbbbbbbb\r\ncccccccccc");
        e.feed("\033[2;4H\033[K"); eq("EL0", row(e, 1), "bbb");
        e.feed("\033[1J"); eq("ED1 row0 cleared", row(e, 0), ""); eq("ED1 cur row", row(e, 1), "");
        e.feed("\033[2J"); eq("ED2", row(e, 2), "");

        // UTF-8 incl. split across feeds, wide chars, emoji
        e = mk(10, 3);
        byte[] u = "é€日本😀x".getBytes(StandardCharsets.UTF_8);
        for (byte b : u) e.feed(new byte[]{b}, 0, 1);
        eq("utf8 split", row(e, 0), "é€日本😀x");
        eq("wide cursor", e.cursorX(), 1 + 1 + 2 + 2 + 2 + 1);
        eq("wide tail marker", e.lineAt(0).ch[3], TerminalEmulator.WIDE_TAIL);

        // wide char at the last column wraps as a whole
        e = mk(5, 3); e.feed("abcd日");
        eq("wide wrap r0", row(e, 0), "abcd"); eq("wide wrap r1", row(e, 1), "日");

        // SGR: basic, bright, 256, truecolor semicolon + colon, reset, attributes
        e = mk(20, 2);
        e.feed("\033[31;1mR\033[0m\033[38;5;75mB\033[38;2;1;2;3mT\033[48:2::9:8:7mC\033[0m\033[4mU\033[24mN");
        TerminalEmulator.Line l = e.lineAt(0);
        eq("sgr red", l.fg[0], 1); eq("sgr bold", (int) l.at[0] & 1, 1);
        eq("sgr 256", l.fg[1], 75);
        eq("sgr truecolor", l.fg[2], TerminalEmulator.TRUECOLOR | 0x010203);
        eq("sgr colon bg", l.bg[3], TerminalEmulator.TRUECOLOR | 0x090807);
        eq("sgr underline on", (int) l.at[4] & 8, 8); eq("sgr underline off", (int) l.at[5] & 8, 0);
        eq("sgr 'm' only resets", mkSgrReset(), true);

        // DECSTBM region scrolling
        e = mk(5, 5);
        e.feed("A\r\nB\r\nC\r\nD\r\nE");
        e.feed("\033[2;4r");               // region rows 2..4
        e.feed("\033[4;1H\n");              // LF at bottom of region
        eq("region keeps row0", row(e, 0), "A"); eq("region scrolled 1", row(e, 1), "C"); eq("region scrolled 2", row(e, 2), "D");
        eq("region blank", row(e, 3), ""); eq("region keeps last", row(e, 4), "E");
        eq("region no history", e.historySize(), 0);
        e.feed("\033[1;1H\033[L"); // IL outside region start? cursor at row 1 (outside region) -> ignored
        eq("IL outside region ignored", row(e, 0), "A");

        // IL / DL / ICH / DCH / ECH
        e = mk(10, 4); e.feed("1\r\n2\r\n3\r\n4"); e.feed("\033[2;1H\033[L");
        eq("IL", row(e, 1) + "|" + row(e, 2), "|2");
        e.feed("\033[M"); eq("DL", row(e, 1), "2");
        e = mk(10, 2); e.feed("abcdef\033[1;3H\033[2@");
        eq("ICH", row(e, 0), "ab  cdef");
        e.feed("\033[2P"); eq("DCH", row(e, 0), "abcdef");
        e.feed("\033[3X"); eq("ECH", row(e, 0), "ab   f");

        // alt screen 1049 restores the main screen and cursor
        e = mk(10, 3); e.feed("main\r\nline2");
        e.feed("\033[?1049h"); eq("alt active", e.isAltScreen(), true);
        e.feed("\033[2J\033[Halt!"); eq("alt text", row(e, 0), "alt!"); eq("alt no history", e.totalLines(), 3);
        e.feed("\033[?1049l"); eq("alt left", e.isAltScreen(), false);
        eq("main restored", row(e, 0), "main"); eq("main cursor restored", e.cursorX() + "," + e.cursorY(), "5,1");

        // save/restore cursor, tabs, backspace, CR
        e = mk(20, 2); e.feed("ab\0337\033[2;5Hxy\0338Z");
        eq("DECSC/DECRC", row(e, 0) + "|" + row(e, 1), "abZ|    xy");
        e = mk(20, 2); e.feed("a\tb\tc"); eq("tabs", row(e, 0), "a       b       c");

        // DSR / DA replies, title, bell
        e = mk(20, 5); e.feed("\033[3;7H\033[6n"); eq("CPR", replies.toString(), "\033[3;7R");
        replies.setLength(0); e.feed("\033[c"); eq("DA", replies.toString(), "\033[?62;22c");
        e.feed("\033]0;mon titre\007"); eq("OSC title BEL", title, "mon titre");
        e.feed("\033]2;autre\033\\"); eq("OSC title ST", title, "autre");
        e.feed("\007\007"); eq("bell", bells, 2);

        // DEC line drawing
        e = mk(10, 2); e.feed("\033(0lqk\033(B"); eq("dec graphics", row(e, 0), "┌─┐");

        // private modes
        e = mk(10, 2); e.feed("\033[?25l\033[?1h\033[?2004h\033[?1000h\033[?1006h");
        eq("cursor hidden", e.cursorVisible(), false); eq("app cursor", e.appCursorKeys(), true);
        eq("bracketed", e.bracketedPaste(), true); eq("mouse", e.mouseMode(), 1000); eq("mouse sgr", e.mouseSgr(), true);
        e.feed("\033[?25h"); eq("cursor shown", e.cursorVisible(), true);

        // REP, split CSI across feeds, garbage
        e = mk(10, 2); e.feed("x\033[3"); e.feed("b"); eq("REP split", row(e, 0), "xxxx");
        e = mk(10, 2); byte[] bad = {(byte) 0xFF, 'o', 'k', (byte) 0xC3}; e.feed(bad, 0, bad.length); e.feed(new byte[]{'a'}, 0, 1); eq("bad utf8", row(e, 0), "\uFFFDok\uFFFDa");

        // resize keeps content and cursor; grow pulls history back
        e = mk(10, 3); e.feed("1\r\n2\r\n3\r\n4\r\n5");        // history: 1,2 ; screen 3,4,5
        e.resize(10, 5);
        eq("grow rows pulls history", row(e, 0) + row(e, 1) + row(e, 2) + row(e, 3) + row(e, 4), "12345");
        eq("grow cursor", e.cursorY(), 4);
        e.resize(10, 2); eq("shrink rows", row(e, 0) + row(e, 1), "45"); eq("shrink history", e.historySize(), 3);
        e.resize(4, 2); eq("shrink cols", e.cols, 4);
        e.resize(30, 2); eq("grow cols", row(e, 1), "5");

        // selection text, soft wrap join
        e = mk(5, 3); e.feed("abcdefgh\r\nxy");
        eq("textBetween joined wrap", e.textBetween(0, 0, 1, 4), "abcdefgh");
        eq("textBetween lines", e.textBetween(0, 0, 2, 1), "abcdefgh\nxy");
        eq("rowString", e.rowString(2).trim(), "xy");

        // reset
        e = mk(10, 2); e.feed("abc\033[?1049h\033c"); eq("RIS", row(e, 0) + e.isAltScreen(), "false");

        // OSC 11 query answer
        e = mk(10, 2); replies.setLength(0); e.feed("\033]11;?\007");
        eq("OSC 11 reply", replies.toString(), "\033]11;rgb:0f0f/1111/1717\007");

        // stress: random bytes must never throw
        Random rnd = new Random(42);
        e = mk(80, 24);
        for (int i = 0; i < 2000; i++) { byte[] junk = new byte[rnd.nextInt(200)]; rnd.nextBytes(junk); e.feed(junk, 0, junk.length); if (i % 97 == 0) e.resize(20 + rnd.nextInt(100), 5 + rnd.nextInt(40)); }
        eq("fuzz survives", true, true);
        // throughput
        e = mk(120, 40); byte[] chunk = "The quick brown fox \033[32mjumps\033[0m over the lazy dog 0123456789 ░▒▓ 日本語\r\n".getBytes(StandardCharsets.UTF_8);
        long t0 = System.nanoTime(); for (int i = 0; i < 100000; i++) e.feed(chunk, 0, chunk.length);
        System.out.printf("throughput: %.1f MB/s%n", (100000.0 * chunk.length / 1e6) / ((System.nanoTime() - t0) / 1e9));
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
    static boolean mkSgrReset() {
        TerminalEmulator e = mk(10, 2); e.feed("\033[31m\033[mX");
        return e.lineAt(0).fg[0] == TerminalEmulator.DEFAULT;
    }
}
