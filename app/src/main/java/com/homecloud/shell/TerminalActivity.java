package com.homecloud.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.List;

/** The terminal screen: tabs (one per session), the terminal itself, the extra keys and a connection banner. */
public class TerminalActivity extends Activity implements TerminalView.Client, SessionManager.Listener, Session.UiListener {
    static final String EXTRA_SESSION = "session";

    private final SessionManager sessions = SessionManager.get();
    private ProfileStore store;
    private TerminalView term;
    private ExtraKeysBar keys;
    private LinearLayout tabs;
    private HorizontalScrollView tabScroll;
    private LinearLayout banner;
    private TextView bannerText, bannerReconnect, bannerClose;
    private Session current;
    private int gridCols = 80, gridRows = 24;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new ProfileStore(this);
        buildUi();
        Session s = sessions.find(getIntent().getIntExtra(EXTRA_SESSION, -1));
        if (s == null) {
            List<Session> all = sessions.list();
            if (all.isEmpty()) {
                finish();
                return;
            }
            s = all.get(all.size() - 1);
        }
        term.setTextSizeSp(store.fontSp());
        applyKeepScreenOn();
        switchTo(s);
        term.requestFocus();
        term.postDelayed(term::showKeyboard, 350);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Session s = sessions.find(intent.getIntExtra(EXTRA_SESSION, -1));
        if (s != null) switchTo(s);
    }

    @Override
    protected void onStart() {
        super.onStart();
        sessions.addListener(this);
        if (current != null) {
            current.setUi(this);
            term.invalidate();
        }
        refreshTabs();
        refreshBanner();
    }

    @Override
    protected void onStop() {
        sessions.removeListener(this);
        if (current != null) current.setUi(null);
        store.setFontSp(term.textSizeSp());
        super.onStop();
    }

    // ------------------------------------------------------------------ layout

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.SURFACE);
        tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabScroll.addView(tabs);
        bar.addView(tabScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = barButton("+");
        add.setOnClickListener(v -> newTab());
        bar.addView(add);
        TextView more = barButton("⋮");
        more.setOnClickListener(this::showMenu);
        bar.addView(more);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46)));

        FrameLayout frame = new FrameLayout(this);
        term = new TerminalView(this);
        term.setClient(this);
        frame.addView(term, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        banner.setBackground(Ui.round(this, Ui.SURFACE2, 14));
        bannerText = Ui.text(this, "", 14, Ui.TEXT, false);
        banner.addView(bannerText);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, Ui.dp(this, 10), 0, 0);
        bannerReconnect = Ui.button(this, "Reconnecter", Ui.ACCENT, Ui.BG);
        bannerReconnect.setOnClickListener(v -> {
            if (current != null) current.reconnect();
        });
        bannerClose = Ui.button(this, "Fermer l'onglet", Ui.SURFACE, Ui.TEXT);
        bannerClose.setOnClickListener(v -> closeCurrent(false));
        buttons.addView(bannerReconnect);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = Ui.dp(this, 8);
        buttons.addView(bannerClose, gap);
        banner.addView(buttons);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bp.setMargins(Ui.dp(this, 10), 0, Ui.dp(this, 10), Ui.dp(this, 10));
        frame.addView(banner, bp);
        banner.setVisibility(View.GONE);

        root.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        keys = new ExtraKeysBar(this, term);
        root.addView(keys, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private TextView barButton(String label) {
        TextView t = Ui.text(this, label, 20, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.ripple(this, Ui.SURFACE, 0));
        t.setClickable(true);
        t.setMinWidth(Ui.dp(this, 46));
        t.setMinHeight(Ui.dp(this, 46));
        return t;
    }

    // ------------------------------------------------------------------ sessions and tabs

    private void switchTo(Session s) {
        if (current != null && current != s) current.setUi(null);
        current = s;
        s.setUi(this);
        getIntent().putExtra(EXTRA_SESSION, s.id);
        term.attach(s);
        refreshTabs();
        refreshBanner();
    }

    private void refreshTabs() {
        tabs.removeAllViews();
        for (Session s : sessions.list()) {
            boolean selected = s == current;
            int dot = s.state() == Session.OPEN ? Ui.OK : (s.state() == Session.CONNECTING ? Ui.WARN : Ui.DANGER);
            String label = s.tabLabel();
            if (label.length() > 22) label = label.substring(0, 21) + "…";
            TextView t = Ui.text(this, "● " + label, 13, selected ? Ui.TEXT : Ui.MUTED, selected);
            android.text.SpannableString sp = new android.text.SpannableString(t.getText());
            sp.setSpan(new android.text.style.ForegroundColorSpan(dot), 0, 1, 0);
            t.setText(sp);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
            t.setBackground(Ui.ripple(this, selected ? Ui.BG : Ui.SURFACE, 0));
            t.setOnClickListener(v -> switchTo(s));
            t.setOnLongClickListener(v -> {
                confirmClose(s);
                return true;
            });
            tabs.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void refreshBanner() {
        Session s = current;
        if (s == null || s.state() == Session.OPEN) {
            banner.setVisibility(View.GONE);
            return;
        }
        banner.setVisibility(View.VISIBLE);
        if (s.state() == Session.CONNECTING) {
            bannerText.setText("Connexion à " + s.profile.host + ":" + s.profile.port + "…");
            bannerReconnect.setVisibility(View.GONE);
        } else {
            String err = s.error();
            bannerText.setText(err != null ? err : "Session terminée (code " + s.exitCode() + ").");
            bannerReconnect.setVisibility(View.VISIBLE);
        }
    }

    private void newTab() {
        Session cur = current;
        if (cur == null) return;
        Session s = sessions.open(this, cur.profile, cur.password(), gridRows, gridCols);
        switchTo(s);
    }

    private void confirmClose(Session s) {
        Ui.dialog(this)
                .setTitle("Fermer cette session ?")
                .setMessage("Les programmes lancés dans ce shell seront arrêtés.")
                .setPositiveButton("Fermer", (d, w) -> closeSession(s))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void closeCurrent(boolean ask) {
        if (current == null) return;
        if (ask) confirmClose(current);
        else closeSession(current);
    }

    private void closeSession(Session s) {
        sessions.close(this, s);
        if (s == current) {
            current = null;
            term.detach();
            List<Session> rest = sessions.list();
            if (rest.isEmpty()) finish();
            else switchTo(rest.get(rest.size() - 1));
        }
        refreshTabs();
    }

    // ------------------------------------------------------------------ menu

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Ports ouverts");
        m.getMenu().add(0, 2, 1, "Coller");
        m.getMenu().add(0, 3, 2, "Copier tout l'écran");
        m.getMenu().add(0, 4, 3, "Texte plus grand");
        m.getMenu().add(0, 5, 4, "Texte plus petit");
        m.getMenu().add(0, 6, 5, store.keepScreenOn() ? "Laisser l'écran s'éteindre" : "Garder l'écran allumé");
        m.getMenu().add(0, 7, 6, "Fermer cette session");
        m.getMenu().add(0, 8, 7, "Tout déconnecter");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: showPorts(); break;
                case 2: term.paste(); break;
                case 3: term.copyAll(); break;
                case 4: term.setTextSizeSp(term.textSizeSp() + 1); break;
                case 5: term.setTextSizeSp(term.textSizeSp() - 1); break;
                case 6:
                    store.setKeepScreenOn(!store.keepScreenOn());
                    applyKeepScreenOn();
                    break;
                case 7: closeCurrent(true); break;
                case 8:
                    Ui.dialog(this)
                            .setTitle("Tout déconnecter ?")
                            .setMessage("Toutes les sessions seront fermées et leurs programmes arrêtés.")
                            .setPositiveButton("Déconnecter", (d, w) -> {
                                sessions.closeAll(this);
                                finish();
                            })
                            .setNegativeButton("Annuler", null)
                            .show();
                    break;
                default: break;
            }
            return true;
        });
        m.show();
    }

    private void applyKeepScreenOn() {
        if (store.keepScreenOn()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showPorts() {
        Session s = current;
        if (s == null) return;
        List<Integer> ports = s.announcedPorts();
        if (ports.isEmpty()) {
            Ui.dialog(this)
                    .setTitle("Ports ouverts")
                    .setMessage("Aucun service n'a été lancé dans ce shell. Dès qu'un programme écoute sur un port, son lien apparaît ici et dans le terminal.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        String[] items = new String[ports.size()];
        for (int i = 0; i < items.length; i++) items[i] = "Port " + ports.get(i) + "\n" + s.urlFor(ports.get(i));
        Ui.dialog(this)
                .setTitle("Ports ouverts")
                .setItems(items, (d, which) -> onOpenUrl(s.urlFor(ports.get(which))))
                .setNegativeButton("Fermer", null)
                .show();
    }

    // ------------------------------------------------------------------ TerminalView.Client

    @Override
    public void onGrid(int cols, int rows) {
        gridCols = cols;
        gridRows = rows;
        if (current != null) current.resize(cols, rows);
    }

    @Override
    public void onOpenUrl(String url) {
        AlertDialog.Builder b = Ui.dialog(this)
                .setTitle("Ouvrir ce lien ?")
                .setMessage(url)
                .setPositiveButton("Ouvrir", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (ActivityNotFoundException e) {
                        Ui.toast(this, "Aucun navigateur disponible");
                    }
                })
                .setNeutralButton("Copier", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Lien", url));
                    Ui.toast(this, "Lien copié");
                })
                .setNegativeButton("Annuler", null);
        b.show();
    }

    @Override
    public void onModifiersChanged() {
        keys.refreshModifiers();
    }

    // ------------------------------------------------------------------ Session.UiListener and SessionManager.Listener

    @Override
    public void onScreen() {
        term.invalidate();
    }

    @Override
    public void onState() {
        refreshTabs();
        refreshBanner();
    }

    @Override
    public void onTitle() {
        refreshTabs();
    }

    @Override
    public void onBell() {
        term.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    @Override
    public void onSessionsChanged() {
        if (current != null && sessions.find(current.id) == null) {     // closed from elsewhere (notification, other screen)
            current = null;
            term.detach();
            List<Session> rest = sessions.list();
            if (rest.isEmpty()) {
                finish();
                return;
            }
            switchTo(rest.get(rest.size() - 1));
            return;
        }
        refreshTabs();
        refreshBanner();
    }
}
