package com.homecloud.shell;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;
import java.util.regex.Pattern;

/** A saved server: where HomeCloud Web listens and which HomeCloud account to use. */
final class Profile {
    static final int DEFAULT_PORT = 8680;
    /** Same rule as the server: 1-32 characters among letters, digits, dot, underscore, dash. */
    static final Pattern USER = Pattern.compile("[A-Za-z0-9._-]{1,32}");

    String id = UUID.randomUUID().toString();
    String name = "";
    String host = "";
    int port = DEFAULT_PORT;
    String user = "";
    /** Password encrypted with the Android Keystore (SecretBox), or null when it is not remembered. */
    String pwEnc;

    String label() {
        return name.isEmpty() ? host : name;
    }

    String address() {
        return user + "@" + host + ":" + port;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("host", host);
        o.put("port", port);
        o.put("user", user);
        if (pwEnc != null) o.put("pw", pwEnc);
        return o;
    }

    static Profile fromJson(JSONObject o) {
        Profile p = new Profile();
        p.id = o.optString("id", p.id);
        p.name = o.optString("name", "");
        p.host = o.optString("host", "");
        p.port = o.optInt("port", DEFAULT_PORT);
        p.user = o.optString("user", "");
        p.pwEnc = o.has("pw") ? o.optString("pw", null) : null;
        return p;
    }

    Profile copy() {
        Profile p = new Profile();
        p.id = id;
        p.name = name;
        p.host = host;
        p.port = port;
        p.user = user;
        p.pwEnc = pwEnc;
        return p;
    }
}
