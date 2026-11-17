package io.github.sarukibrowser.tv;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pop-up and redirect shield. Pure decision logic (no Android classes) so it can be tested on a computer.
 *
 * Strict mode, the default, only lets a page open a new window or leave for another site when you clicked a real,
 * visible link that goes there. Everything else (ad pop-ups and pop-unders fired by a click on a video player,
 * "click anywhere" layers, timed redirects, pages trying to open the Play Store) is stopped, and the last blocked
 * address is kept so MENU can still open it with one press.
 */
final class Shield {
    static final int ALLOW = 0, BLOCK = 1, BLOCK_AD = 2;

    /** A click counts as the reason for a pop-up or a page change for this long. */
    static final long CLICK_WINDOW_MS = 2500;

    private static final Set<String> TWO_PART_SUFFIX = new HashSet<>(Arrays.asList(
            "co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go", "gob", "mil", "nom", "sch", "ltd", "plc"));

    /** Link shorteners and "you are leaving" pages: their whole job is to send you on to another site. */
    private static final Set<String> REDIRECTORS = new HashSet<>(Arrays.asList(
            "t.co", "bit.ly", "tinyurl.com", "goo.gl", "ow.ly", "buff.ly", "lnkd.in", "is.gd", "rebrand.ly", "cutt.ly",
            "shorturl.at", "tiny.cc", "rb.gy", "l.facebook.com", "lm.facebook.com", "l.instagram.com", "out.reddit.com",
            "google.com", "news.google.com", "youtube.com", "m.youtube.com", "duckduckgo.com", "bing.com", "search.brave.com",
            "linktr.ee", "href.li", "away.vk.com", "slack-redir.net", "outlook.live.com", "safelinks.protection.outlook.com"));

    /** Sign-in pages a site may send you to (and that send you back) without a click. */
    private static final Set<String> AUTH = new HashSet<>(Arrays.asList(
            "accounts.google.com", "login.microsoftonline.com", "login.live.com", "appleid.apple.com", "facebook.com",
            "m.facebook.com", "api.twitter.com", "x.com", "twitter.com", "github.com", "auth0.com", "okta.com", "paypal.com",
            "id.atlassian.com", "discord.com", "login.yahoo.com"));

    static String host(String url) {
        if (url == null) return "";
        try {
            String h = new URI(url.trim().replace(" ", "%20")).getHost();
            if (h == null) return "";
            h = h.toLowerCase(Locale.US);
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) {
            return "";
        }
    }

    /** The "site" a host belongs to: espn.com for www.espn.com and cdn.espn.com, bbc.co.uk for news.bbc.co.uk. */
    static String site(String host) {
        if (host == null) return "";
        String h = host.toLowerCase(Locale.US);
        if (h.isEmpty() || h.matches("[0-9.:\\[\\]]+")) return h;   // IP addresses
        String[] p = h.split("\\.");
        if (p.length <= 2) return h;
        String last = p[p.length - 1], second = p[p.length - 2];
        int keep = (last.length() == 2 && TWO_PART_SUFFIX.contains(second)) ? 3 : 2;
        StringBuilder b = new StringBuilder();
        for (int i = p.length - keep; i < p.length; i++) { if (b.length() > 0) b.append('.'); b.append(p[i]); }
        return b.toString();
    }

    static boolean sameSite(String hostA, String hostB) {
        String a = site(hostA), b = site(hostB);
        return !a.isEmpty() && a.equals(b);
    }

    static boolean isRedirector(String host) { return host != null && (REDIRECTORS.contains(host) || REDIRECTORS.contains(site(host))); }

    static boolean isAuth(String host) { return host != null && (AUTH.contains(host) || AUTH.contains(site(host))); }

    /** True when the click was recent and on a real link whose address matches where we are being sent. */
    static boolean clickedLinkGoesTo(String clickedHref, long clickAgeMs, String toUrl) {
        if (clickedHref == null || clickedHref.isEmpty() || clickAgeMs > CLICK_WINDOW_MS || clickAgeMs < 0) return false;
        String ch = host(clickedHref), th = host(toUrl);
        if (!ch.isEmpty() && sameSite(ch, th)) return true;
        return !ch.isEmpty() && isRedirector(ch);      // a link through a shortener lands somewhere else, that's fine
    }

    /**
     * A page asks to open a new window.
     * @param gesture  the request came during a click
     * @param clickedHref the real link that was clicked ("" when the click wasn't on a visible link)
     */
    static int popup(boolean strict, boolean siteAllowed, boolean adHost, boolean gesture, String popupUrl,
                     String clickedHref, long clickAgeMs) {
        if (adHost) return BLOCK_AD;
        if (siteAllowed) return ALLOW;
        if (!strict) return gesture ? ALLOW : BLOCK;
        if (!gesture) return BLOCK;
        if (isAuth(host(popupUrl))) return ALLOW;          // "Sign in with…" / "Pay with PayPal" windows
        return clickedLinkGoesTo(clickedHref, clickAgeMs, popupUrl) ? ALLOW : BLOCK;
    }

    /**
     * The main page is about to change to another address.
     * @param redirect the server answered the previous request with a redirect (part of a page load already allowed)
     */
    static int navigation(boolean strict, boolean siteAllowed, boolean adHost, boolean gesture, boolean redirect,
                          String method, String fromHost, String toUrl, String clickedHref, long clickAgeMs) {
        String to = host(toUrl);
        boolean same = sameSite(fromHost, to);
        if (adHost && !same) {
            if (!strict && gesture) return ALLOW;
            if (strict && clickedLinkGoesTo(clickedHref, clickAgeMs, toUrl) && host(clickedHref).equals(to)) return ALLOW;
            return BLOCK_AD;
        }
        if (siteAllowed || fromHost == null || fromHost.isEmpty() || same || !strict) return ALLOW;
        if (redirect) return ALLOW;
        if (isRedirector(fromHost) || isAuth(to) || isAuth(fromHost)) return ALLOW;
        if (gesture && "POST".equalsIgnoreCase(method)) return ALLOW;              // a form you submitted
        if (gesture && clickedLinkGoesTo(clickedHref, clickAgeMs, toUrl)) return ALLOW;
        return BLOCK;
    }

    /** A page asks to open another app (Play Store, intent:, market:, tel:...). Only a click on such a link may do that. */
    static int externalApp(boolean strict, boolean siteAllowed, boolean gesture, String url, String clickedHref, long clickAgeMs) {
        if (siteAllowed || !strict) return gesture ? ALLOW : BLOCK;
        if (!gesture || clickedHref == null || clickAgeMs > CLICK_WINDOW_MS || clickAgeMs < 0) return BLOCK;
        int c = url.indexOf(':');
        String scheme = c > 0 ? url.substring(0, c + 1).toLowerCase(Locale.US) : url;
        return clickedHref.toLowerCase(Locale.US).startsWith(scheme) ? ALLOW : BLOCK;
    }
}
