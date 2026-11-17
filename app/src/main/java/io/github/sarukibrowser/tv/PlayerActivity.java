package io.github.sarukibrowser.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;

/**
 * Full-screen player for streams and files found on pages, downloads and links sent from other apps.
 * Uses the TV's hardware decoders first and falls back to FFmpeg (NextLib) for codecs the TV can't decode.
 */
public class PlayerActivity extends Activity {
    private ExoPlayer player;
    private PlayerView view;
    private FrameLayout root;
    private LinearLayout bar, menu;
    private TextView title, time, hint, toast, center;
    private Progress progress;
    private final Handler h = new Handler(Looper.getMainLooper());
    private String url, ua, referer;
    private long pendingSeek = -1;
    private int resize = 0;
    private static final String[] RESIZE_NAMES = {"Fit", "Fill (crop)", "Stretch", "Zoom"};
    private static final int[] RESIZE_MODES = {AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH};

    private final Runnable hideBar = () -> { if (menu.getVisibility() != View.VISIBLE && player != null && player.isPlaying()) bar.animate().alpha(0).setDuration(450).setInterpolator(Ui.EASE).start(); };
    private final Runnable tick = new Runnable() { @Override public void run() { update(); h.postDelayed(this, 500); } };
    private final Runnable commitSeek = () -> {
        if (player != null && pendingSeek >= 0) player.seekTo(pendingSeek);
        pendingSeek = -1;
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.init(this);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Intent i = getIntent();
        url = i.getStringExtra("url");
        if (url == null && i.getData() != null) url = i.getData().toString();
        if (url == null) { finish(); return; }
        ua = i.getStringExtra("ua");
        referer = i.getStringExtra("referer");
        String cookie = i.getStringExtra("cookie");
        String kind = i.getStringExtra("kind");
        String name = i.getStringExtra("title");
        if (name == null) name = Uri.parse(url).getLastPathSegment();

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        view = new PlayerView(this);
        view.setUseController(false);
        view.setKeepScreenOn(true);
        view.setShutterBackgroundColor(0xFF000000);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        center = Ui.text(this, "", Ui.TITLE, Ui.TEXT, true);
        center.setGravity(Gravity.CENTER);
        center.setPadding(Ui.dp(28), Ui.dp(16), Ui.dp(28), Ui.dp(16));
        center.setBackground(Ui.round(0xCC000000, 16, 0, 0));
        root.addView(center, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        center.setText("Loading…");

        buildBar(name);
        buildToast();
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(Ui.dp(20), Ui.dp(20), Ui.dp(20), Ui.dp(20));
        menu.setBackground(Ui.round(Ui.PANEL, 0, 0, 0));
        menu.setVisibility(View.GONE);
        root.addView(menu, new FrameLayout.LayoutParams(Ui.dp(380), -1, Gravity.END));
        setContentView(root);

        cookieHdr = cookie;
        kindHint = kind;
        buildPlayer(false);
        long resume = Prefs.resumeAt(url);
        if (resume > 10000) { player.seekTo(resume); show("Resumed at " + fmt(resume) + " · ⏪ to start over"); }
        h.post(tick);
        showBar();
    }

    private String cookieHdr, kindHint;

    /** ffmpegFirst = use the FFmpeg decoders before the TV's own ones (used when the TV's decoder fails). */
    private void buildPlayer(boolean ffmpegFirst) {
        Map<String, String> headers = new HashMap<>();
        if (referer != null) headers.put("Referer", referer);
        if (cookieHdr != null && !cookieHdr.isEmpty()) headers.put("Cookie", cookieHdr);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000).setReadTimeoutMs(25000)
                .setDefaultRequestProperties(headers);
        if (ua != null) http.setUserAgent(ua);
        DefaultMediaSourceFactory msf = new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http));
        NextRenderersFactory rf = new NextRenderersFactory(this);
        rf.setEnableDecoderFallback(true);
        if (ffmpegFirst) rf.setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        player = new ExoPlayer.Builder(this, rf).setMediaSourceFactory(msf).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        view.setPlayer(player);
        MediaItem.Builder mi = new MediaItem.Builder().setUri(url);
        String low = url.toLowerCase(Locale.US);
        if ("hls".equals(kindHint) || low.contains(".m3u8")) mi.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if ("dash".equals(kindHint) || low.contains(".mpd")) mi.setMimeType(MimeTypes.APPLICATION_MPD);
        player.setMediaItem(mi.build());
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int st) {
                center.setVisibility(st == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (st == Player.STATE_BUFFERING) center.setText("Loading…");
                if (st == Player.STATE_ENDED) { Prefs.saveResume(url, 0); finish(); }
                update();
            }
            @Override public void onIsPlayingChanged(boolean playing) { showBar(); }
            @Override public void onPlayerError(PlaybackException e) { showError(e); }
        });
        player.prepare();
        player.play();
    }

    private int retries;

    // ---------- UI ----------
    private void buildBar(String name) {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(Ui.dp(40), Ui.dp(40), Ui.dp(40), Ui.dp(26));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x00000000});
        bar.setBackground(g);
        title = Ui.text(this, name, Ui.TITLE, Ui.TEXT, true);
        title.setSingleLine(true);
        bar.addView(title);
        progress = new Progress(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, Ui.dp(18));
        pp.topMargin = Ui.dp(12);
        bar.addView(progress, pp);
        LinearLayout row = new LinearLayout(this);
        time = Ui.text(this, "", Ui.BODY, Ui.TEXT, false);
        hint = Ui.text(this, "OK pause · ◀ ▶ seek (hold = faster) · ▲ options · BACK close", Ui.CAPTION, Ui.DIM, false);
        hint.setGravity(Gravity.END);
        row.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(hint, new LinearLayout.LayoutParams(0, -2, 2));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = Ui.dp(6);
        bar.addView(row, rp);
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    private void buildToast() {
        toast = Ui.text(this, "", Ui.BODY, Ui.TEXT, false);
        toast.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(16), Ui.dp(10));
        toast.setBackground(Ui.round(0xDD1B212B, 20, 0, 0));
        toast.setAlpha(0);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = Ui.dp(30);
        root.addView(toast, lp);
    }

    private void show(String s) {
        toast.setText(s);
        toast.animate().cancel();
        toast.setAlpha(1);
        toast.animate().alpha(0).setStartDelay(3200).setDuration(500).start();
    }

    private void showBar() {
        bar.animate().cancel();
        if (bar.getAlpha() < 1f) bar.animate().alpha(1).setDuration(150).setInterpolator(Ui.EASE).start();
        h.removeCallbacks(hideBar);
        h.postDelayed(hideBar, 3500);
        update();
    }

    private void update() {
        if (player == null) return;
        long dur = player.getDuration(), pos = pendingSeek >= 0 ? pendingSeek : player.getCurrentPosition();
        boolean live = player.isCurrentMediaItemLive() || dur == C.TIME_UNSET;
        progress.set(live ? -1 : (dur > 0 ? pos / (float) dur : 0), dur > 0 ? player.getBufferedPosition() / (float) dur : 0);
        String state = player.isPlaying() ? "" : (player.getPlaybackState() == Player.STATE_BUFFERING ? "" : "❚❚  ");
        time.setText(state + (live ? "LIVE" : fmt(pos) + " / " + fmt(dur)) + (pendingSeek >= 0 ? "   →" : ""));
    }

    static String fmt(long ms) {
        if (ms < 0 || ms == C.TIME_UNSET) return "--:--";
        long s = ms / 1000, hh = s / 3600, mm = s / 60 % 60, ss = s % 60;
        return hh > 0 ? String.format(Locale.US, "%d:%02d:%02d", hh, mm, ss) : String.format(Locale.US, "%d:%02d", mm, ss);
    }

    // ---------- keys ----------
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (menu.getVisibility() == View.VISIBLE) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
                if (e.getAction() == KeyEvent.ACTION_UP) closeMenu();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_SPACE:
                if (down && e.getRepeatCount() == 0) { if (player.isPlaying()) player.pause(); else player.play(); showBar(); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY: if (down) { player.play(); showBar(); } return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: if (down) { player.pause(); showBar(); } return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) {
                    int dir = (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_MEDIA_REWIND) ? -1 : 1;
                    int r = e.getRepeatCount();
                    long step = (k == KeyEvent.KEYCODE_MEDIA_REWIND || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) ? 30000
                            : r < 4 ? 10000 : r < 12 ? 30000 : 60000;
                    if (r > 0 && r % 2 == 1 && step == 10000) return true;   // keep the first second of holding gentle
                    seekBy(dir * step);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_MENU:
                if (down && e.getRepeatCount() == 0) openMenu();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_INFO:
                if (down) showBar();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) {
                    if (pendingSeek >= 0) { pendingSeek = -1; h.removeCallbacks(commitSeek); update(); }
                    else finish();
                }
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void seekBy(long d) {
        long dur = player.getDuration();
        long base = pendingSeek >= 0 ? pendingSeek : player.getCurrentPosition();
        long t = Math.max(0, base + d);
        if (dur != C.TIME_UNSET && dur > 0) t = Math.min(t, dur - 1000);
        pendingSeek = t;
        h.removeCallbacks(commitSeek);
        h.postDelayed(commitSeek, 450);
        showBar();
    }

    // ---------- options menu ----------
    private interface Pick { void run(int i); }

    private void list(String heading, List<String> items, int selected, Pick pick) {
        menu.removeAllViews();
        TextView t = Ui.text(this, heading, Ui.TITLE, Ui.TEXT, true);
        t.setPadding(Ui.dp(6), 0, 0, Ui.dp(12));
        menu.addView(t);
        ScrollView sv = Ui.vScroll(this, 8);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col);
        menu.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        View first = null;
        for (int i = 0; i < items.size(); i++) {
            TextView row = Ui.text(this, (i == selected ? "● " : "    ") + items.get(i), Ui.BODY, Ui.TEXT, false);
            row.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
            Ui.focusable(row, 10, 1f);   // v1.7.1: full-width row, ring inside
            final int idx = i;
            row.setOnClickListener(v -> pick.run(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(6);
            col.addView(row, lp);
            if (first == null || i == selected) first = row;
        }
        menu.setVisibility(View.VISIBLE);
        bar.setAlpha(1);
        if (first != null) first.requestFocus();
    }

    private void closeMenu() { menu.setVisibility(View.GONE); showBar(); }

    private void openMenu() {
        List<String> items = new ArrayList<>();
        items.add("Audio track");
        items.add("Subtitles");
        items.add("Speed · " + player.getPlaybackParameters().speed + "x");
        items.add("Picture · " + RESIZE_NAMES[resize]);
        items.add("Start over");
        items.add("Open in another player app");
        list("Options", items, -1, i -> {
            if (i == 0) tracks(C.TRACK_TYPE_AUDIO);
            else if (i == 1) tracks(C.TRACK_TYPE_TEXT);
            else if (i == 2) {
                float[] sp = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
                List<String> l = new ArrayList<>();
                int sel = 2;
                for (int j = 0; j < sp.length; j++) { l.add(sp[j] + "x"); if (sp[j] == player.getPlaybackParameters().speed) sel = j; }
                list("Speed", l, sel, j -> { player.setPlaybackSpeed(sp[j]); closeMenu(); });
            } else if (i == 3) {
                List<String> l = new ArrayList<>();
                for (String s : RESIZE_NAMES) l.add(s);
                list("Picture", l, resize, j -> { resize = j; view.setResizeMode(RESIZE_MODES[j]); closeMenu(); });
            } else if (i == 4) { player.seekTo(0); closeMenu(); }
            else externalPlayer();
        });
    }

    private void tracks(int type) {
        List<String> names = new ArrayList<>();
        List<Tracks.Group> groups = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        int sel = -1;
        if (type == C.TRACK_TYPE_TEXT) { names.add("Off"); groups.add(null); idx.add(-1); }
        boolean textOff = player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
        if (type == C.TRACK_TYPE_TEXT && textOff) sel = 0;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                Format f = g.getTrackFormat(i);
                StringBuilder n = new StringBuilder();
                if (f.label != null) n.append(f.label).append(' ');
                if (f.language != null && !"und".equals(f.language)) n.append(new Locale(f.language).getDisplayLanguage(Locale.ENGLISH)).append(' ');
                if (f.channelCount > 0) n.append(f.channelCount == 6 ? "5.1 " : f.channelCount == 8 ? "7.1 " : f.channelCount == 2 ? "stereo " : f.channelCount + "ch ");
                String codec = f.sampleMimeType == null ? "" : f.sampleMimeType.replace("audio/", "").replace("text/", "").replace("application/", "");
                n.append('(').append(codec).append(')');
                if (!g.isTrackSupported(i)) n.append(" · not supported");
                if (n.length() == 0) n.append("Track ").append(names.size() + 1);
                if (g.isTrackSelected(i) && !(type == C.TRACK_TYPE_TEXT && textOff)) sel = names.size();
                names.add(n.toString().trim());
                groups.add(g);
                idx.add(i);
            }
        }
        if (names.isEmpty() || (type == C.TRACK_TYPE_TEXT && names.size() == 1)) {
            show(type == C.TRACK_TYPE_AUDIO ? "Only one audio track" : "No subtitles in this video");
            closeMenu();
            return;
        }
        list(type == C.TRACK_TYPE_AUDIO ? "Audio track" : "Subtitles", names, sel, j -> {
            Tracks.Group g = groups.get(j);
            if (g == null) {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            } else {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), idx.get(j))).build());
            }
            closeMenu();
        });
    }

    private void externalPlayer() {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(url), "video/*");
        List<String> hdr = new ArrayList<>();
        if (ua != null) { hdr.add("User-Agent"); hdr.add(ua); i.putExtra("http-user-agent", ua); }
        if (referer != null) { hdr.add("Referer"); hdr.add(referer); }
        i.putExtra("headers", hdr.toArray(new String[0]));
        i.putExtra("title", title.getText().toString());
        if (player != null) i.putExtra("position", (int) player.getCurrentPosition());
        try {
            startActivity(Intent.createChooser(i, "Play with"));
        } catch (Exception e) {
            show("No other video player app is installed (VLC works well)");
        }
    }

    private boolean triedWithoutSound;

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (player != null) { player.release(); player = null; }
        setIntent(i);
        recreate();
    }

    private static boolean audioProblem(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String n = c.getClass().getName() + " " + c.getMessage() + " " + java.util.Arrays.toString(c.getStackTrace()).substring(0, Math.min(400, java.util.Arrays.toString(c.getStackTrace()).length()));
            if (n.contains("MediaCodecAudioRenderer") || n.contains("AudioSink") || n.contains("audio/") || n.contains("Ffmpeg") && n.contains("Audio")) return true;
        }
        return false;
    }

    private void showError(PlaybackException e) {
        boolean decoder = e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED || e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                || e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED;
        if (retries == 0 && (decoder || audioProblem(e))) {
            // first retry: rebuild the player with FFmpeg decoders in front of the TV's own
            retries = 1;
            long pos = player.getCurrentPosition();
            player.release();
            buildPlayer(true);
            player.seekTo(pos);
            return;
        }
        if (!triedWithoutSound && audioProblem(e)) {
            triedWithoutSound = true;
            long pos = player.getCurrentPosition();
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build());
            player.seekTo(pos);
            player.prepare();
            player.play();
            show("This video's sound format can't play on this TV · playing without sound · ▲ Options → Open in another player app");
            return;
        }
        String why = e.getErrorCodeName().replace("ERROR_CODE_", "").replace('_', ' ').toLowerCase(Locale.US);
        if (e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED || e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                || e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES) why = "the TV can't decode this video format";
        else if (e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) why = "unknown file type";
        else if (e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED || e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) why = "network problem";
        if (e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) why = "the site refused the video request";
        else if (e.errorCode == PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR || why.contains("drm")) why = "this video is copy-protected (DRM)";
        center.setVisibility(View.GONE);
        List<String> items = new ArrayList<>();
        items.add("Try again");
        items.add("Open in another player app");
        items.add("Close");
        list("Can't play this video (" + why + ")", items, -1, i -> {
            if (i == 0) { closeMenu(); player.prepare(); player.play(); }
            else if (i == 1) externalPlayer();
            else finish();
        });
    }

    @Override protected void onPause() {
        super.onPause();
        if (player != null) {
            long d = player.getDuration(), p = player.getCurrentPosition();
            if (d != C.TIME_UNSET && d > 120000 && p > 15000 && p < d - 60000) Prefs.saveResume(url, p);
            else if (d != C.TIME_UNSET && p >= d - 60000) Prefs.saveResume(url, 0);
            player.pause();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
    }

    /** Thin seek bar: played part in cyan, buffered part in grey. */
    static final class Progress extends View {
        float played, buffered;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Progress(android.content.Context c) { super(c); }
        void set(float pl, float bu) { played = pl; buffered = bu; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float hgt = Ui.dp(5), y = getHeight() / 2f, r = hgt / 2;
            p.setColor(0x55FFFFFF);
            c.drawRoundRect(0, y - r, getWidth(), y + r, r, r, p);
            if (played < 0) { p.setColor(0xFFEF5350); c.drawRoundRect(0, y - r, getWidth(), y + r, r, r, p); return; }
            p.setColor(0x88FFFFFF);
            c.drawRoundRect(0, y - r, getWidth() * Math.min(1, buffered), y + r, r, r, p);
            p.setColor(Ui.ACCENT);
            float x = getWidth() * Math.min(1, played);
            c.drawRoundRect(0, y - r, x, y + r, r, r, p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(x, y, Ui.dp(8), p);
        }
    }
}
