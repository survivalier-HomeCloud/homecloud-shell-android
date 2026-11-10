package com.homecloud.shell;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process-wide list of open sessions. ShellService only keeps the process alive; the sessions live here. */
final class SessionManager {
    interface Listener {
        void onSessionsChanged();
    }

    private static final SessionManager INSTANCE = new SessionManager();

    static SessionManager get() {
        return INSTANCE;
    }

    private final List<Session> sessions = new ArrayList<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private int nextId = 1;

    private SessionManager() {}

    void addListener(Listener l) { listeners.addIfAbsent(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    /** Tells the listeners (on the main thread) that a session appeared, disappeared or changed state or title. */
    void changed() {
        main.post(() -> {
            for (Listener l : listeners) l.onSessionsChanged();
        });
    }

    synchronized int count() { return sessions.size(); }

    synchronized List<Session> list() { return new ArrayList<>(sessions); }

    synchronized Session find(int id) {
        for (Session s : sessions) if (s.id == id) return s;
        return null;
    }

    Session open(Context ctx, Profile profile, String password, int rows, int cols) {
        Session s;
        synchronized (this) {
            s = new Session(nextId++, profile, password);
            sessions.add(s);
        }
        s.connect(rows, cols);
        ShellService.sync(ctx);
        changed();
        return s;
    }

    void close(Context ctx, Session s) {
        s.close();
        synchronized (this) {
            sessions.remove(s);
        }
        ShellService.sync(ctx);
        changed();
    }

    void closeAll(Context ctx) {
        List<Session> all = list();
        for (Session s : all) s.close();
        synchronized (this) {
            sessions.clear();
        }
        ShellService.sync(ctx);
        changed();
    }
}
