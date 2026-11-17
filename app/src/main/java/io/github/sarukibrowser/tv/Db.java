package io.github.sarukibrowser.tv;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** History and favourites (favourites are the home-screen tiles). */
final class Db extends SQLiteOpenHelper {
    static final class Entry {
        final String url, title;
        Entry(String url, String title) { this.url = url; this.title = title == null || title.isEmpty() ? url : title; }
    }

    private static Db inst;
    static Db get(Context c) { if (inst == null) inst = new Db(c.getApplicationContext()); return inst; }

    private Db(Context c) { super(c, "saruki.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE history(url TEXT PRIMARY KEY, title TEXT, last INTEGER, visits INTEGER)");
        db.execSQL("CREATE TABLE fav(url TEXT PRIMARY KEY, title TEXT, pos INTEGER)");
        String[][] seed = {
                {"https://www.google.com/", "Google"}, {"https://www.youtube.com/", "YouTube"},
                {"https://www.wikipedia.org/", "Wikipedia"}, {"https://www.reddit.com/", "Reddit"},
                {"https://www.espn.com/", "ESPN"}, {"https://weather.com/", "Weather"},
                {"https://www.amazon.com/", "Amazon"}, {"https://news.google.com/", "News"}};
        for (int i = 0; i < seed.length; i++) {
            ContentValues v = new ContentValues();
            v.put("url", seed[i][0]); v.put("title", seed[i][1]); v.put("pos", i);
            db.insert("fav", null, v);
        }
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) { }

    void visit(String url, String title) {
        if (url == null || !url.startsWith("http")) return;
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        db.execSQL("UPDATE history SET title=?, last=?, visits=visits+1 WHERE url=?", new Object[]{title, now, url});
        db.execSQL("INSERT OR IGNORE INTO history(url,title,last,visits) VALUES(?,?,?,1)", new Object[]{url, title, now});
    }

    List<Entry> history(int limit) {
        List<Entry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT url,title FROM history ORDER BY last DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) out.add(new Entry(c.getString(0), c.getString(1)));
        }
        return out;
    }

    void deleteHistory(String url) { getWritableDatabase().delete("history", "url=?", new String[]{url}); }
    void clearHistory() { getWritableDatabase().delete("history", null, null); }

    /** Address-bar matches: favourites first, then the most visited history. */
    List<Entry> search(String q, int limit) {
        LinkedHashMap<String, Entry> m = new LinkedHashMap<>();
        String like = "%" + q.replace("%", "") + "%";
        try (Cursor c = getReadableDatabase().rawQuery("SELECT url,title FROM fav WHERE url LIKE ? OR title LIKE ? ORDER BY pos LIMIT ?",
                new String[]{like, like, String.valueOf(limit)})) {
            while (c.moveToNext()) m.put(c.getString(0), new Entry(c.getString(0), c.getString(1)));
        }
        try (Cursor c = getReadableDatabase().rawQuery("SELECT url,title FROM history WHERE url LIKE ? OR title LIKE ? ORDER BY visits DESC, last DESC LIMIT ?",
                new String[]{like, like, String.valueOf(limit)})) {
            while (c.moveToNext() && m.size() < limit) if (!m.containsKey(c.getString(0))) m.put(c.getString(0), new Entry(c.getString(0), c.getString(1)));
        }
        return new ArrayList<>(m.values());
    }

    List<Entry> favs() {
        List<Entry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT url,title FROM fav ORDER BY pos", null)) {
            while (c.moveToNext()) out.add(new Entry(c.getString(0), c.getString(1)));
        }
        return out;
    }

    boolean isFav(String url) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM fav WHERE url=?", new String[]{url})) { return c.moveToFirst(); }
    }

    void addFav(String url, String title) {
        ContentValues v = new ContentValues();
        v.put("url", url); v.put("title", title);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(MAX(pos),0)+1 FROM fav", null)) { c.moveToFirst(); v.put("pos", c.getInt(0)); }
        getWritableDatabase().insertWithOnConflict("fav", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void removeFav(String url) { getWritableDatabase().delete("fav", "url=?", new String[]{url}); }

    void moveFavFirst(String url) {
        getWritableDatabase().execSQL("UPDATE fav SET pos=(SELECT COALESCE(MIN(pos),0)-1 FROM fav) WHERE url=?", new Object[]{url});
    }
}
