package com.homecloud.shell;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** Saved servers and the few app settings, in private SharedPreferences. */
final class ProfileStore {
    private static final String KEY_PROFILES = "profiles";
    private static final String KEY_WAKELOCK = "wakelock";
    private static final String KEY_FONT = "font_sp";
    private static final String KEY_KEEP_SCREEN = "keep_screen";
    static final float DEFAULT_FONT_SP = 13f;

    private final SharedPreferences sp;

    ProfileStore(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("hcshell", Context.MODE_PRIVATE);
    }

    synchronized List<Profile> all() {
        List<Profile> list = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(KEY_PROFILES, "[]"));
            for (int i = 0; i < a.length(); i++) list.add(Profile.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
            // corrupted settings: start from an empty list
        }
        return list;
    }

    private void write(List<Profile> list) {
        JSONArray a = new JSONArray();
        try {
            for (Profile p : list) a.put(p.toJson());
        } catch (JSONException e) {
            return;
        }
        sp.edit().putString(KEY_PROFILES, a.toString()).apply();
    }

    synchronized void save(Profile p) {
        List<Profile> list = all();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(p.id)) {
                list.set(i, p);
                found = true;
                break;
            }
        }
        if (!found) list.add(p);
        write(list);
    }

    synchronized void delete(String id) {
        List<Profile> list = all();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                break;
            }
        }
        write(list);
    }

    /** The remembered password, or null. */
    String password(Profile p) {
        return p.pwEnc == null ? null : SecretBox.decrypt(p.pwEnc);
    }

    boolean wakeLock() { return sp.getBoolean(KEY_WAKELOCK, false); }

    void setWakeLock(boolean v) { sp.edit().putBoolean(KEY_WAKELOCK, v).apply(); }

    float fontSp() { return sp.getFloat(KEY_FONT, DEFAULT_FONT_SP); }

    void setFontSp(float v) { sp.edit().putFloat(KEY_FONT, v).apply(); }

    boolean keepScreenOn() { return sp.getBoolean(KEY_KEEP_SCREEN, false); }

    void setKeepScreenOn(boolean v) { sp.edit().putBoolean(KEY_KEEP_SCREEN, v).apply(); }
}
