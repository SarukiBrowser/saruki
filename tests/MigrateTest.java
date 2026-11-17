package io.github.sarukibrowser.tv;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * v1.8: the settings hand-over format (Migrate.pack → JSON text → Migrate.unpack) keeps every value and type, and
 * leaves out the settings that belong to one install. Runs on a computer with org.json on the class path, before
 * android.jar (see tests/README.md).
 */
public final class MigrateTest {
    static int fails, checks;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) fails++;
        System.out.println((ok ? "PASS " : "FAIL ") + what);
    }

    /** SharedPreferences.Editor that just fills a map. */
    static final class MapEditor implements SharedPreferences.Editor {
        final Map<String, Object> m = new HashMap<>();
        public SharedPreferences.Editor putString(String k, String v) { m.put(k, v); return this; }
        public SharedPreferences.Editor putStringSet(String k, Set<String> v) { m.put(k, v); return this; }
        public SharedPreferences.Editor putInt(String k, int v) { m.put(k, v); return this; }
        public SharedPreferences.Editor putLong(String k, long v) { m.put(k, v); return this; }
        public SharedPreferences.Editor putFloat(String k, float v) { m.put(k, v); return this; }
        public SharedPreferences.Editor putBoolean(String k, boolean v) { m.put(k, v); return this; }
        public SharedPreferences.Editor remove(String k) { m.remove(k); return this; }
        public SharedPreferences.Editor clear() { m.clear(); return this; }
        public boolean commit() { return true; }
        public void apply() { }
    }

    public static void main(String[] args) throws Exception {
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("engine", 2);
        src.put("desktopDefault", false);
        src.put("zoomD", 175);
        src.put("m_espn.com", "m");
        src.put("zd_espn.com", 125);
        src.put("pop_streams.example", true);
        src.put("ads_example.org", true);
        src.put("call_meet.jit.si", "y");
        src.put("callCam", 2);
        src.put("remoteCode", "K7Q2MX");
        src.put("remoteId", "abcdefghjk23");
        src.put("phoneToken", "t0k3n");
        src.put("phonePort", 8766);
        src.put("pos_12345", 987654321012L);
        src.put("someFloat", 1.5f);
        Set<String> set = new HashSet<>();
        set.add("a"); set.add("b \"quoted\" ✓");
        src.put("someSet", set);
        String session = new JSONArray()
                .put(new JSONObject().put("u", "https://www.espn.com/").put("t", "ESPN").put("c", false))
                .put(new JSONObject().put("u", "https://example.org/a?b=1&c=ñ").put("t", "Página \"uno\"").put("c", true))
                .toString();
        src.put("session", session);
        src.put("welcomed", true);
        // belong to one install: must not travel
        src.put("downloads", "12,13");
        src.put("icontry_espn.com", 1700000000000L);
        src.put("migrateAsked", true);

        JSONObject export = new JSONObject();
        export.put("format", Migrate.FORMAT).put("v", 1).put("prefs", Migrate.pack(src))
                .put("fav", new JSONArray().put(new JSONObject().put("url", "https://www.espn.com/").put("title", "ESPN").put("pos", 0)))
                .put("history", new JSONArray());
        // through text, as it travels between the two apps
        JSONObject back = new JSONObject(export.toString());

        check(Migrate.valid(back), "export is recognised");
        check(!Migrate.valid(new JSONObject().put("format", "other").put("v", 1)), "other files are refused");
        check(Migrate.tabs(back) == 2, "2 open tabs counted");
        check(Migrate.favs(back) == 1, "1 favorite counted");
        check(Migrate.paired(back), "phone pairing found");

        MapEditor ed = new MapEditor();
        int n = Migrate.unpack(back.getJSONObject("prefs"), ed);
        Map<String, Object> got = ed.m;
        check(!got.containsKey("downloads"), "download ids stay behind");
        check(!got.containsKey("icontry_espn.com"), "icon retry times stay behind");
        check(!got.containsKey("migrateAsked"), "import bookkeeping stays behind");
        check(n == src.size() - 3, "every other setting arrives (" + n + ")");
        for (Map.Entry<String, Object> e : src.entrySet()) {
            String k = e.getKey();
            if (Migrate.skip(k)) continue;
            Object v = got.get(k);
            check(v != null && v.getClass() == e.getValue().getClass() && v.equals(e.getValue()),
                    "same value and type: " + k + " (" + e.getValue().getClass().getSimpleName() + ")");
        }
        check(session.equals(got.get("session")), "tabs text (quotes, accents, &) unchanged");

        System.out.println(checks + " checks, " + fails + " failed");
        System.out.println(fails == 0 ? "ALL PASS" : "SOME TESTS FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
