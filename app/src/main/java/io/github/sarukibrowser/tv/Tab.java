package io.github.sarukibrowser.tv;

import android.graphics.Bitmap;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/** One tab. Only the active tab owns a live WebView; the others keep their saved state and a thumbnail. */
final class Tab {
    private static int nextId = 1;

    /** A video stream or file spotted on the page. */
    static final class Media {
        final String url, referer, kind;   // kind: hls, dash or file
        Media(String url, String referer, String kind) { this.url = url; this.referer = referer; this.kind = kind; }
        String label() {
            String u = url;
            int q = u.indexOf('?');
            if (q > 0) u = u.substring(0, q);
            int s = u.lastIndexOf('/');
            String name = s >= 0 && s < u.length() - 1 ? u.substring(s + 1) : u;
            return (kind.equals("hls") ? "Live/stream · " : kind.equals("dash") ? "Stream · " : "File · ") + name;
        }
    }

    final int id = nextId++;
    int parentId;            // set when the tab was opened by a pop-up/new window, so Back can return to the opener
    String url;
    String title = "New tab";
    Bundle state;
    Bitmap thumb;
    final List<Media> media = new ArrayList<>();
    int blockedCount;

    Tab(String url) { this.url = url; }

    synchronized void addMedia(Media m) {
        for (Media x : media) if (x.url.equals(m.url)) return;
        if (media.size() > 30) return;
        media.add(m);
    }

    synchronized List<Media> mediaSnapshot() {
        // Streams first (a master playlist plays every quality), then files.
        List<Media> out = new ArrayList<>();
        for (Media m : media) if (m.kind.equals("hls")) out.add(m);
        for (Media m : media) if (m.kind.equals("dash")) out.add(m);
        for (Media m : media) if (m.kind.equals("file")) out.add(m);
        return out;
    }

    synchronized void clearMedia() { media.clear(); blockedCount = 0; }
}
