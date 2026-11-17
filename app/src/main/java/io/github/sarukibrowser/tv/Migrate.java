package io.github.sarukibrowser.tv;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Moving Saruki's own data (favorites, history, open tabs, settings, per-site choices, phone pairing) from the
 * earlier Saruki build to this one. The earlier build (1.7.2) hands it over through a content provider that only apps
 * signed with Saruki's key can read; this build reads it once, on first start or from Settings → Import.
 *
 * <p>The data is one JSON object: {"format":"saruki-export","v":1,"prefs":{key:{"t":type,"v":value}},
 * "fav":[{url,title,pos}],"history":[{url,title,last,visits}]}. Site sign-ins and cookies live inside the WebView and
 * can't be moved.
 */
final class Migrate {
    static final String FORMAT = "saruki-export";
    /** The earlier build's export provider. Reading it needs the signature permission below. */
    static final String AUTHORITY = "io.github.sarukibrowser.legacy.export";
    static final int HISTORY_MAX = 3000;

    private Migrate() { }

    /** Settings that belong to this one install and must not travel (download ids, icon retry times). */
    static boolean skip(String key) {
        return key.equals("downloads") || key.startsWith("icontry_") || key.startsWith("migrate");
    }

    // ---------------------------------------------------------------- settings <-> JSON (plain Java, unit tested)
    static JSONObject pack(Map<String, ?> all) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (k == null || v == null || skip(k)) continue;
            JSONObject x = new JSONObject();
            if (v instanceof String) x.put("t", "s").put("v", v);
            else if (v instanceof Integer) x.put("t", "i").put("v", v);
            else if (v instanceof Long) x.put("t", "l").put("v", v);
            else if (v instanceof Boolean) x.put("t", "b").put("v", v);
            else if (v instanceof Float) x.put("t", "f").put("v", ((Float) v).doubleValue());
            else if (v instanceof Set) {
                JSONArray a = new JSONArray();
                for (Object s : (Set<?>) v) if (s != null) a.put(s.toString());
                x.put("t", "ss").put("v", a);
            } else continue;
            o.put(k, x);
        }
        return o;
    }

    /** Puts every packed setting into the editor. Returns how many were written. */
    static int unpack(JSONObject prefs, SharedPreferences.Editor ed) throws JSONException {
        int n = 0;
        JSONArray names = prefs.names();
        if (names == null) return 0;
        for (int i = 0; i < names.length(); i++) {
            String k = names.getString(i);
            if (skip(k)) continue;
            JSONObject x = prefs.optJSONObject(k);
            if (x == null) continue;
            switch (x.optString("t")) {
                case "s": ed.putString(k, x.getString("v")); break;
                case "i": ed.putInt(k, x.getInt("v")); break;
                case "l": ed.putLong(k, x.getLong("v")); break;
                case "b": ed.putBoolean(k, x.getBoolean("v")); break;
                case "f": ed.putFloat(k, (float) x.getDouble("v")); break;
                case "ss": {
                    JSONArray a = x.getJSONArray("v");
                    Set<String> s = new HashSet<>();
                    for (int j = 0; j < a.length(); j++) s.add(a.getString(j));
                    ed.putStringSet(k, s);
                    break;
                }
                default: continue;
            }
            n++;
        }
        return n;
    }

    static boolean valid(JSONObject o) { return o != null && FORMAT.equals(o.optString("format")) && o.optInt("v") == 1; }

    /** Open tabs in an export (the "session" setting). */
    static int tabs(JSONObject o) {
        try {
            JSONObject s = o.getJSONObject("prefs").optJSONObject("session");
            return s == null ? 0 : new JSONArray(s.getString("v")).length();
        } catch (JSONException e) { return 0; }
    }

    static int favs(JSONObject o) { JSONArray a = o.optJSONArray("fav"); return a == null ? 0 : a.length(); }

    static boolean paired(JSONObject o) {
        JSONObject p = o.optJSONObject("prefs");
        return p != null && p.has("remoteCode") && p.has("remoteId");
    }

    // ---------------------------------------------------------------- reading from the earlier build
    /** Whether the earlier build (with its export provider) is installed on this TV. */
    static boolean available(Context c) {
        try { return c.getPackageManager().resolveContentProvider(AUTHORITY, 0) != null; } catch (RuntimeException e) { return false; }
    }

    /** Reads the earlier build's data. Slow (another app, a few hundred KB): call off the main thread. Null if none. */
    static JSONObject fetch(Context c) {
        try (InputStream in = c.getContentResolver().openInputStream(Uri.parse("content://" + AUTHORITY + "/data"))) {
            if (in == null) return null;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) {
                b.write(buf, 0, r);
                if (b.size() > 20 * 1024 * 1024) return null;   // far beyond any real export
            }
            JSONObject o = new JSONObject(b.toString("UTF-8"));
            return valid(o) ? o : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- writing into this build
    /**
     * Replaces this build's settings, favorites and history with the export. Settings are committed (not applied) so
     * they are on disk before the app restarts. Returns false if nothing was changed.
     */
    static boolean apply(Context c, JSONObject o) {
        if (!valid(o)) return false;
        try {
            Prefs.init(c);
            JSONObject prefs = o.getJSONObject("prefs");
            SharedPreferences.Editor ed = Prefs.p.edit().clear();
            unpack(prefs, ed);
            ed.putBoolean("welcomed", true);
            ed.putLong("migrated", System.currentTimeMillis());
            if (!ed.commit()) return false;

            SQLiteDatabase db = Db.get(c).getWritableDatabase();
            db.beginTransaction();
            try {
                JSONArray fav = o.optJSONArray("fav");
                if (fav != null) {
                    db.delete("fav", null, null);
                    for (int i = 0; i < fav.length(); i++) {
                        JSONObject f = fav.getJSONObject(i);
                        ContentValues v = new ContentValues();
                        v.put("url", f.getString("url"));
                        v.put("title", f.optString("title", f.getString("url")));
                        v.put("pos", f.optInt("pos", i));
                        db.insertWithOnConflict("fav", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                    }
                }
                JSONArray hist = o.optJSONArray("history");
                if (hist != null) {
                    db.delete("history", null, null);
                    for (int i = 0; i < hist.length(); i++) {
                        JSONObject h = hist.getJSONObject(i);
                        ContentValues v = new ContentValues();
                        v.put("url", h.getString("url"));
                        v.put("title", h.optString("title", h.getString("url")));
                        v.put("last", h.optLong("last", 0));
                        v.put("visits", h.optInt("visits", 1));
                        db.insertWithOnConflict("history", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
