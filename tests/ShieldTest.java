package io.github.sarukibrowser.tv;

public class ShieldTest {
    static int fails = 0, n = 0;
    static void eq(String name, int got, int want) { n++; if (got != want) { fails++; System.out.println("FAIL " + name + " got " + got + " want " + want); } }
    static void eq(String name, String got, String want) { n++; if (!got.equals(want)) { fails++; System.out.println("FAIL " + name + " got " + got + " want " + want); } }
    static void tru(String name, boolean b) { n++; if (!b) { fails++; System.out.println("FAIL " + name); } }

    public static void main(String[] a) {
        final int A = Shield.ALLOW, B = Shield.BLOCK, AD = Shield.BLOCK_AD;
        // site()
        eq("site www", Shield.site("cdn.espn.com"), "espn.com");
        eq("site co.uk", Shield.site("news.bbc.co.uk"), "bbc.co.uk");
        eq("site com.br", Shield.site("www.globo.com.br"), "globo.com.br");
        eq("site 2part", Shield.site("espn.com"), "espn.com");
        eq("site ip", Shield.site("192.168.1.50"), "192.168.1.50");
        eq("host", Shield.host("https://www.ESPN.com/watch?x=1"), "espn.com");
        eq("host bad", Shield.host("not a url"), "");
        tru("same site", Shield.sameSite("streams.example.net", "player.example.net"));
        tru("diff site", !Shield.sameSite("example.net", "adsite.com"));

        // pop-ups, strict
        eq("popup no gesture", Shield.popup(true, false, false, false, "https://ad.com/x", "", 100), B);
        eq("popup click on player (no link)", Shield.popup(true, false, false, true, "https://ad.com/x", "", 100), B);
        eq("popup real target=_blank link", Shield.popup(true, false, false, true, "https://wiki.org/a", "https://wiki.org/a", 300), A);
        eq("popup link but goes elsewhere", Shield.popup(true, false, false, true, "https://ad.com/x", "https://wiki.org/a", 300), B);
        eq("popup old click", Shield.popup(true, false, false, true, "https://wiki.org/a", "https://wiki.org/a", 9000), B);
        eq("popup ad host always", Shield.popup(true, true, true, true, "https://doubleclick.net/x", "https://doubleclick.net/x", 10), AD);
        eq("popup site allowed", Shield.popup(true, true, false, false, "https://x.com", "", 99999), A);
        eq("popup standard + gesture", Shield.popup(false, false, false, true, "https://ad.com", "", 50), A);
        eq("popup standard no gesture", Shield.popup(false, false, false, false, "https://ad.com", "", 50), B);
        eq("popup via shortener link", Shield.popup(true, false, false, true, "https://news.site.com/a", "https://bit.ly/abc", 200), A);

        eq("popup sign-in window", Shield.popup(true, false, false, true, "https://www.paypal.com/checkout", "", 200), A);
        eq("popup sign-in without click", Shield.popup(true, false, false, false, "https://accounts.google.com/x", "", 200), B);
        // navigation, strict
        eq("nav same site", Shield.navigation(true, false, false, false, false, "GET", "streams.com", "https://live.streams.com/x", "", 99999), A);
        eq("nav timed redirect", Shield.navigation(true, false, false, false, false, "GET", "streams.com", "https://casino.io/", "", 99999), B);
        eq("nav click on player -> ad", Shield.navigation(true, false, false, true, false, "GET", "streams.com", "https://casino.io/", "", 200), B);
        eq("nav real link click", Shield.navigation(true, false, false, true, false, "GET", "reddit.com", "https://espn.com/nba", "https://www.espn.com/nba", 300), A);
        eq("nav server redirect", Shield.navigation(true, false, false, false, true, "GET", "espn.com", "https://cdn.other.com/x", "", 99999), A);
        eq("nav from shortener", Shield.navigation(true, false, false, false, false, "GET", "t.co", "https://espn.com/", "", 99999), A);
        eq("nav to sign-in", Shield.navigation(true, false, false, false, false, "GET", "espn.com", "https://accounts.google.com/o/oauth2", "", 99999), A);
        eq("nav back from sign-in", Shield.navigation(true, false, false, false, false, "GET", "accounts.google.com", "https://espn.com/cb", "", 99999), A);
        eq("nav form post", Shield.navigation(true, false, false, true, false, "POST", "shop.com", "https://pay.bank.com/", "", 300), A);
        eq("nav from blank", Shield.navigation(true, false, false, false, false, "GET", "", "https://anything.com", "", 99999), A);
        eq("nav site allowed", Shield.navigation(true, true, false, false, false, "GET", "streams.com", "https://casino.io/", "", 99999), A);
        eq("nav ad host no gesture", Shield.navigation(true, false, true, false, false, "GET", "streams.com", "https://adserver.com/", "", 99999), AD);
        eq("nav ad host even with site allowed", Shield.navigation(true, true, true, false, false, "GET", "streams.com", "https://adserver.com/", "", 99999), AD);
        eq("nav ad host clicked link", Shield.navigation(true, false, true, true, false, "GET", "news.com", "https://adserver.com/c", "https://adserver.com/c", 300), A);
        eq("nav standard cross-site", Shield.navigation(false, false, false, false, false, "GET", "streams.com", "https://casino.io/", "", 99999), A);
        eq("nav standard ad no gesture", Shield.navigation(false, false, true, false, false, "GET", "streams.com", "https://adserver.com/", "", 99999), AD);
        eq("nav standard ad gesture", Shield.navigation(false, false, true, true, false, "GET", "streams.com", "https://adserver.com/", "", 100), A);

        // other apps
        eq("app no gesture", Shield.externalApp(true, false, false, "market://details?id=x", "", 100), B);
        eq("app click on player", Shield.externalApp(true, false, true, "intent://x#Intent;end", "", 100), B);
        eq("app real market link", Shield.externalApp(true, false, true, "market://details?id=x", "market://details?id=x", 100), A);
        eq("app standard gesture", Shield.externalApp(false, false, true, "tel:123", "", 100), A);

        System.out.println(n + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
