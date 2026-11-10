package com.homecloud.shell;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

/** Home screen: saved servers, active sessions and the few settings. */
public class MainActivity extends Activity implements SessionManager.Listener {
    private ProfileStore store;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new ProfileStore(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        content.setPadding(pad, Ui.dp(this, 28), pad, pad);
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1);   // lets the "sessions open" notification show
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionManager.get().addListener(this);
        render();
    }

    @Override
    protected void onStop() {
        SessionManager.get().removeListener(this);
        super.onStop();
    }

    @Override
    public void onSessionsChanged() {
        render();
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        content.removeAllViews();

        TextView title = Ui.text(this, "›_ HomeCloud Shell", 28, Ui.TEXT, true);
        content.addView(title);
        TextView sub = Ui.text(this, "Votre terminal HomeCloud, partout.", 14, Ui.MUTED, false);
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 24));
        content.addView(sub);

        List<Session> active = SessionManager.get().list();
        if (!active.isEmpty()) {
            content.addView(sectionLabel("SESSIONS ACTIVES"));
            for (Session s : active) content.addView(sessionCard(s));
            content.addView(spacer(14));
        }

        content.addView(sectionLabel("SERVEURS"));
        List<Profile> profiles = store.all();
        if (profiles.isEmpty()) {
            TextView hint = Ui.text(this, "Ajoutez votre serveur HomeCloud : l'adresse de HomeCloud Web (port 8680 par défaut) et votre compte. HomeCloud Shell doit être installé et démarré sur le serveur.", 14, Ui.MUTED, false);
            hint.setPadding(Ui.dp(this, 2), 0, Ui.dp(this, 2), Ui.dp(this, 14));
            content.addView(hint);
        }
        for (Profile p : profiles) content.addView(profileCard(p));

        TextView add = Ui.button(this, "+  Ajouter un serveur", Ui.ACCENT, Ui.BG);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = Ui.dp(this, 4);
        add.setOnClickListener(v -> editProfile(null));
        content.addView(add, ap);

        content.addView(spacer(26));
        content.addView(sectionLabel("RÉGLAGES"));
        content.addView(wakeLockRow());

        TextView foot = Ui.text(this, "HomeCloud Shell Android " + versionName() + " · protocole hcshell", 12, Ui.MUTED, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, Ui.dp(this, 30), 0, 0);
        content.addView(foot);
    }

    private TextView sectionLabel(String s) {
        TextView t = Ui.text(this, s, 12, Ui.ACCENT, true);
        t.setLetterSpacing(0.12f);
        t.setPadding(Ui.dp(this, 2), 0, 0, Ui.dp(this, 10));
        return t;
    }

    private View spacer(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(this, dp)));
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14));
        c.setBackground(Ui.ripple(this, Ui.SURFACE, 14));
        c.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 10);
        c.setLayoutParams(lp);
        return c;
    }

    private LinearLayout twoLines(String a, String b) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, a, 16, Ui.TEXT, true));
        TextView t = Ui.text(this, b, 13, Ui.MUTED, false);
        t.setPadding(0, Ui.dp(this, 2), 0, 0);
        col.addView(t);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return col;
    }

    private TextView smallButton(String label) {
        TextView t = Ui.text(this, label, 18, Ui.MUTED, true);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Ui.dp(this, 44));
        t.setMinHeight(Ui.dp(this, 44));
        t.setBackground(Ui.ripple(this, 0x00000000, 22));
        t.setClickable(true);
        return t;
    }

    private View sessionCard(Session s) {
        LinearLayout c = card();
        int dot = s.state() == Session.OPEN ? Ui.OK : (s.state() == Session.CONNECTING ? Ui.WARN : Ui.DANGER);
        TextView d = Ui.text(this, "●", 14, dot, false);
        d.setPadding(0, 0, Ui.dp(this, 12), 0);
        c.addView(d);
        c.addView(twoLines(s.tabLabel(), s.profile.address()));
        TextView close = smallButton("✕");
        close.setOnClickListener(v -> Ui.dialog(this)
                .setTitle("Fermer cette session ?")
                .setMessage("Les programmes lancés dans ce shell seront arrêtés.")
                .setPositiveButton("Fermer", (dlg, w) -> SessionManager.get().close(this, s))
                .setNegativeButton("Annuler", null)
                .show());
        c.addView(close);
        c.setOnClickListener(v -> startActivity(new Intent(this, TerminalActivity.class).putExtra(TerminalActivity.EXTRA_SESSION, s.id)));
        return c;
    }

    private View profileCard(Profile p) {
        LinearLayout c = card();
        String sub = p.address() + (p.pwEnc != null ? "  ·  mot de passe mémorisé" : "");
        c.addView(twoLines(p.label(), sub));
        TextView more = smallButton("⋮");
        more.setOnClickListener(v -> profileMenu(p));
        c.addView(more);
        c.setOnClickListener(v -> connect(p));
        c.setOnLongClickListener(v -> {
            profileMenu(p);
            return true;
        });
        return c;
    }

    private View wakeLockRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        row.setBackground(Ui.round(this, Ui.SURFACE, 14));
        LinearLayout col = twoLines("Garder le processeur actif", "Évite que la veille coupe les connexions longues, au prix de la batterie.");
        row.addView(col);
        Switch sw = new Switch(this);
        sw.setChecked(store.wakeLock());
        sw.setOnCheckedChangeListener((b, on) -> {
            store.setWakeLock(on);
            if (SessionManager.get().count() > 0) ShellService.sync(this);
        });
        row.addView(sw);
        return row;
    }

    @SuppressWarnings("deprecation")
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ actions

    private void profileMenu(Profile p) {
        Ui.dialog(this)
                .setTitle(p.label())
                .setItems(new String[] { "Modifier", "Supprimer" }, (d, which) -> {
                    if (which == 0) {
                        editProfile(p);
                    } else {
                        Ui.dialog(this)
                                .setTitle("Supprimer ce serveur ?")
                                .setMessage(p.address())
                                .setPositiveButton("Supprimer", (dd, w) -> {
                                    store.delete(p.id);
                                    render();
                                })
                                .setNegativeButton("Annuler", null)
                                .show();
                    }
                })
                .show();
    }

    private void connect(Profile p) {
        String pw = store.password(p);
        if (pw != null) startSession(p, pw);
        else askPassword(p);
    }

    private void startSession(Profile p, String pw) {
        Session s = SessionManager.get().open(this, p, pw, 24, 80);
        startActivity(new Intent(this, TerminalActivity.class).putExtra(TerminalActivity.EXTRA_SESSION, s.id));
    }

    private LinearLayout form() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int h = Ui.dp(this, 22);
        box.setPadding(h, Ui.dp(this, 8), h, 0);
        return box;
    }

    private void askPassword(Profile p) {
        EditText pw = Ui.input(this, "Mot de passe", "", Ui.TYPE_PASSWORD);
        CheckBox remember = new CheckBox(this);
        remember.setText("Mémoriser sur cet appareil (chiffré)");
        remember.setTextColor(Ui.TEXT);
        LinearLayout box = form();
        box.addView(pw);
        box.addView(remember);
        AlertDialog dlg = Ui.dialog(this)
                .setTitle(p.user + "@" + p.host)
                .setView(box)
                .setPositiveButton("Se connecter", (d, w) -> {
                    String v = pw.getText().toString();
                    if (remember.isChecked()) {
                        String enc = SecretBox.encrypt(v);
                        if (enc != null) {
                            p.pwEnc = enc;
                            store.save(p);
                        } else {
                            Ui.toast(this, "Chiffrement indisponible : mot de passe non mémorisé");
                        }
                    }
                    startSession(p, v);
                })
                .setNegativeButton("Annuler", null)
                .create();
        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
    }

    /** Adds a server (existing == null) or edits one. */
    private void editProfile(Profile existing) {
        Profile p = existing == null ? new Profile() : existing.copy();
        EditText name = Ui.input(this, "Nom (facultatif)", p.name, Ui.TYPE_TEXT);
        EditText host = Ui.input(this, "Adresse (ex. 192.168.1.78)", p.host, Ui.TYPE_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        EditText port = Ui.input(this, "Port de HomeCloud Web", Integer.toString(p.port), Ui.TYPE_NUMBER);
        EditText user = Ui.input(this, "Compte HomeCloud", p.user, Ui.TYPE_TEXT);
        EditText pw = Ui.input(this, p.pwEnc != null ? "Mot de passe mémorisé (vide : conserver)" : "Mot de passe (facultatif)", "", Ui.TYPE_PASSWORD);
        CheckBox forget = new CheckBox(this);
        forget.setText("Oublier le mot de passe mémorisé");
        forget.setTextColor(Ui.TEXT);
        LinearLayout box = form();
        for (View v : new View[] { name, host, port, user, pw }) box.addView(v);
        if (p.pwEnc != null) box.addView(forget);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        AlertDialog dlg = Ui.dialog(this)
                .setTitle(existing == null ? "Nouveau serveur" : "Modifier le serveur")
                .setView(sv)
                .setPositiveButton("Enregistrer", null)
                .setNegativeButton("Annuler", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String h = host.getText().toString().trim();
            String u = user.getText().toString().trim();
            int prt;
            try {
                prt = Integer.parseInt(port.getText().toString().trim());
            } catch (NumberFormatException e) {
                prt = -1;
            }
            boolean ok = true;
            if (h.isEmpty() || h.contains(" ")) {
                host.setError("Adresse invalide");
                ok = false;
            }
            if (prt < 1 || prt > 65535) {
                port.setError("Port entre 1 et 65535");
                ok = false;
            }
            if (!Profile.USER.matcher(u).matches()) {
                user.setError("1 à 32 caractères : lettres, chiffres, . _ -");
                ok = false;
            }
            if (!ok) return;
            p.name = name.getText().toString().trim();
            p.host = h;
            p.port = prt;
            p.user = u;
            String typed = pw.getText().toString();
            if (!typed.isEmpty()) {
                String enc = SecretBox.encrypt(typed);
                if (enc != null) p.pwEnc = enc;
                else Ui.toast(this, "Chiffrement indisponible : mot de passe non mémorisé");
            } else if (forget.isChecked()) {
                p.pwEnc = null;
            }
            store.save(p);
            dlg.dismiss();
            render();
        });
    }
}
