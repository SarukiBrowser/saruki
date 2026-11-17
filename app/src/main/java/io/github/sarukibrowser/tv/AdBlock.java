package io.github.sarukibrowser.tv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/**
 * Host-list ad and tracker blocker (StevenBlack unified hosts, MIT). The list is kept as one sorted array and
 * looked up with a binary search on the host and each parent domain, which keeps memory to a few MB on a 2 GB TV.
 */
final class AdBlock {
    private static volatile String[] hosts = new String[0];
    private static boolean loading;

    static synchronized void load(Context c) {
        if (loading) return;
        loading = true;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            ArrayList<String> list = new ArrayList<>(80000);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(app.getAssets().open("adhosts.gz"))))) {
                String l;
                while ((l = r.readLine()) != null) if (!l.isEmpty()) list.add(l.intern());
            } catch (Exception ignored) { }
            String[] a = list.toArray(new String[0]);
            Arrays.sort(a);
            hosts = a;
        }, "adblock-load").start();
    }

    static int count() { return hosts.length; }

    static boolean blocked(String host) {
        if (host == null || host.isEmpty()) return false;
        String[] a = hosts;
        if (a.length == 0) return false;
        String h = host.toLowerCase();
        while (true) {
            if (Arrays.binarySearch(a, h) >= 0) return true;
            int dot = h.indexOf('.');
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false;   // stop at the registrable domain
            h = h.substring(dot + 1);
        }
    }
}
