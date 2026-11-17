# Saruki TV Browser: changes

## v1.8.2 (versionCode 13): safer buttons, clearer menus, real icons
Saruki Remote goes to **1.0.1** with it (see the end of this entry). Same app ids and same key: installs over 1.8.1
and keeps everything. Import, the pop-up shield, ad blocking, calls, the phone link, the TV Player and the icon and
banner art are untouched.
1. **"Are you sure?" before anything one press can't undo.** Clear all history, Clear browsing data, Clear cookies and
   site data, a held OK that deletes a download, and New pairing code now ask first. The question shows two rows:
   **Cancel** (already highlighted, so a stray OK does nothing) and the "Yes, ..." row. BACK also cancels.
   **History** now lists the pages first (newest highlighted) and "Clear all history" last, so OK on opening a
   page can no longer land on it.
2. **Edge cues on the quick menu.** When a row of tiles is wider than the screen, a dark fade and a small arrow show
   on the side where more tiles are. They never take focus or clicks. Tiles are a little bigger (112 x 100) so two
   lines of label always fit.
3. **One icon family everywhere:** Material Symbols (Apache 2.0) in the quick menu, the top bar (Back, Forward, Stop,
   Reload) and the lock in the address. Every quick-menu tile has its own icon. Two tiles differ from the plan's
   table because it gave them the same icon as another tile: "Switch to Mobile site" uses a phone-held-upright icon
   and "Open pop-up" uses a new-window icon.
4. **The top bar works with link jumping.** Press UP at the top of a page and the pointer goes to the bar; LEFT and
   RIGHT hop between Back, Forward, Stop, Reload and the address (a button that can't be used is skipped); DOWN goes
   back to the first link on the page.
5. **Text rows are just text.** Lines that do nothing (Welcome guide, About, the "No history yet" line) are plain text
   with no card and no pink ring, so nothing looks pressable that isn't. UP and DOWN scroll the text so every word can
   be read with the remote. Buttons they belong to ("Got it", "Close") are pinned under the list. In a side list the
   arrow keys now stay in the list; they no longer wander to Home behind it.
6. **The address panel and the on-screen keyboard.** The list of suggestions stops above the keyboard (never shorter
   than three rows) and goes back to full height when the keyboard closes.
7. **One type scale** (14 / 16 / 20 / 24 / 28) instead of a size per screen.
8. **Pick from a list instead of pressing OK to step through values:** Search engine, Pointer speed, Arrows move,
   New sites open as, Desktop page size, Pop-up & redirect shield (Strict, Standard, Off, each explained), Screen edge
   margin, Camera and Microphone. The current choice has a dot and is highlighted; OK picks and goes back to the same
   row; BACK changes nothing. On/Off settings still switch with one press.
9. **Closing tabs:** after Close, the highlight stays on a Close button (the tab that took its place, or New tab when
   none is left), so OK, OK, OK closes tab after tab.
10. **Wording:** the quick menu names the blocked site ("Go to redirect" / "Open pop-up" and its address); a shorter
    "Switch phone camera"; the Welcome guide has a tip about the top bar; "How to use the phone" is rewritten; About says
    what Saruki is for and credits the icons. No personal names or addresses anywhere users can see.

**Saruki Remote 1.0.1 (versionCode 2):**
- **Forget this TV asks first** ("Forget this TV? The trackpad, typing and camera stop until you pair again.").
  Only Forget does it.
- The subtitle is now "Your phone as the trackpad, keyboard and camera for Saruki, THE tv browser."

## v1.8.1 (versionCode 12): new launcher icon and TV banner
- **TV banner (the tile in Google TV's apps row):** the new lockup: the bolder S-in-TV mark beside the "saruki"
  wordmark, with "**THE** tv browser" under it, plum (#2B0D1F) on pink (#E8579B).
- **App icon:** the same bolder mark, plum on pink, as the square icon, the Android adaptive icon and the one-colour
  themed icon.
- Nothing else changes. Same app id and key: installs over 1.8 and keeps everything. On a TV that still has only the
  earlier Saruki (1.7.2), it installs next to it and offers Import, exactly like 1.8.

## v1.8 (versionCode 11): new app id, import, 64-bit
- **New app id `io.github.sarukibrowser.tv`** (Java package and namespace too), ready for the open-source release.
  Android treats it as a new app: it installs next to the earlier Saruki and starts empty.
- **Import:** on first start, if the earlier Saruki (1.7.2) is on the TV, Saruki offers "Import your favorites, tabs
  and settings": favorites, history, open tabs, settings, per-site choices (desktop/mobile, zoom, pop-ups, ads,
  camera/mic) and the phone pairing (paired phones keep working). Saruki restarts with them. Also in Settings → Import
  while the earlier Saruki is installed. Site sign-ins and cookies can't move: sign in again where needed. Saruki Remote
  asks once more before it lends the phone's camera, because the TV app's id changed.
- **One APK for 32-bit and 64-bit:** `armeabi-v7a` and `arm64-v8a`, so 64-bit streaming sticks can install it too
  (about 15 MB).
- TV Overlay Suite's trackpad, typing and camera link work as before (the Suite accepts any TV app).
- New test `tests/MigrateTest.java`.

## v1.7.2 (versionCode 10): the bridge to Saruki 1.8
- **Hands your data to Saruki 1.8.** Saruki 1.8 has a new app id, so Android installs it as a separate app that starts
  empty. 1.7.2 lets it read this copy's favorites, history, open tabs, settings, per-site choices (desktop/mobile,
  zoom, pop-ups, ads, camera/mic) and phone pairing, once, when you accept Import in 1.8. Only apps signed with
  Saruki's own key can read it (a signature permission on the provider `io.github.sarukibrowser.legacy.export`).
- **Settings → Export favorites, tabs and settings:** saves the same data as a backup file in the TV's Downloads folder.
- Site sign-ins and cookies stay inside this copy's WebView; they can't be moved.
- Nothing else changes. Same key, installs over 1.7.1.

## v1.7.1 (versionCode 9): fixes, centered Home, arrow pointer
- **Pink focus border is complete again (also in v1.6):** the home search bar and full-width list rows (Settings and
  the other side lists, address suggestions, TV Player menus) no longer grow when selected; growing pushed their
  rounded ends past what their list can draw, so the ends were cut flat. The pink ring is now drawn inside the box.
  Small tiles (chips, favorites, Continue, quick menu, tabs) still grow a little, and their lists now scroll a selected
  tile into view with room to spare, so a tile at the edge keeps its whole ring. The drop shadow under a selected box
  is gone: lists clipped it into small marks at their left edge.
- **Home is centered:** the chips row, the Favorites grid and the Continue row are each centered on the screen; the
  "Favorites" and "Continue" labels sit on the left edge of their block. Spacing around chips, tiles and cards is now
  even on both sides. The search bar stays full width; logo and clock stay as they were.
- **Pointer on Home:** the pointer no longer disappears on Saruki's Home. The phone trackpad (Saruki Remote or TV
  Overlay Suite) moves it freely, the button under it gets the pink ring, and a tap or OK clicks it; pushing past the
  top or bottom edge, or a two-finger scroll, scrolls Home. The remote's arrows still jump between buttons (the pointer
  steps aside until the trackpad moves it again). Before, the trackpad could only step the highlight between buttons.
- **Classic arrow pointer:** the pink dot is now a desktop-style arrow, black inside with a Saruki-pink (#FF5CA8)
  border and a soft shadow. Its **tip is the click point** (the tip is drawn at the exact spot clicks, hovers, edge
  scrolling and the trackpad all use, and size changes scale around the tip). Over links the border gets brighter and
  thicker and the arrow a little bigger; over text boxes it is an I-beam in the same look; while dragging the border is
  gold; a click sends a pink ring out from the tip.

## v1.7 (versionCode 8): Saruki Remote, and a top bar
- **Saruki Remote, Saruki's own phone app (v1.0, new):** trackpad, keyboard and camera for Saruki, with nothing else
  installed on the TV. TV Overlay Suite is no longer needed for any of it (it still works too, if it's installed).
  - **Pairing:** MENU → Phone remote shows a QR code. The phone's camera opens a page served by the TV: "Open Saruki
    Remote" pairs the app in one tap; a phone without the app installs it **straight from the TV** (Saruki carries the
    phone app inside). The 6-character code is also shown for pairing by hand. Settings → Saruki Remote: pair a phone,
    phones connected, new pairing code (drops every phone).
  - **Trackpad:** the same fast trackpad as the Suite's (acceleration, tap, two-finger scroll, drag, Back / Home / Menu /
    Play keys), straight from the phone to Saruki. The phone opens it by itself when Saruki comes to the front.
  - **Keyboard:** click a text box on a page and the phone's typing bar pops up with that box's text; every letter,
    swipe-typed word, autocorrection and cursor move goes into the page box live, as real typing (search suggestions,
    sign-in boxes and React / Vue forms all see it). Search / Go / Enter on the phone presses it on the page. With no box
    open, "Type" on the phone opens Saruki's address bar and types there (a site or a search). Works in boxes inside
    frames and in rich text editors. A box that only got the cursor because the page loaded doesn't pop the phone.
  - **Camera:** video calls use the phone's camera and microphone through Saruki Remote (preferred) or TV Overlay Suite.
  - The link: WebSocket on port 8790 (next free one up to 8799), path /remote, paired with a 6-character code (5 wrong
    codes from one address lock it out for a minute). The TV announces itself on the home Wi-Fi as `_saruki._tcp`, so
    the phone finds it again when the TV's address changes. Low-latency Wi-Fi while a phone is linked and Saruki is in
    front. Protocols adapted from TV Overlay Suite (MIT): `shared/java/io/github/sarukibrowser/link`.
  - The old QR touchpad page (no app, in the phone's Chrome) is still there: "No app: simple touchpad page".
- **Top bar (new):** a slim bar above every page with **Back, Forward, Stop and Reload** and the page address. Click
  them with the pointer (remote OK or the phone trackpad). Buttons dim when they can't be used (Stop only while loading).
  Clicking the address opens the address bar. Pages now start below the bar; the site chip, the ▶ Play pill and the call
  bar moved down with it. Hidden on Home and on full-screen video.
- New tests: `tests/test_type.py` (typing into page boxes, headless Chrome), `tests/link/` (link end to end, protocols).

## v1.6 (versionCode 7): new look, S-Line in Pointer Pink
- **New logo:** the S-Line mark (an S breaking out of a TV outline) on Pointer Pink #FF5CA8. App icon, Android adaptive icon (pink background, black mark), a one-colour themed icon for phones, and a new TV apps-row banner ("saruki · the browser for your tv.").
- **Home screen:** the mark and the lowercase "saruki" wordmark in Archivo Black (bundled, SIL Open Font License) replace the plain "Saruki" title.
- **Pink replaces sky blue everywhere:** the pointer, focus rings, the highlight around page items, the page-loading bar, the video seek bar, the "Play in TV Player" button (black text on pink) and the phone remote page.
- No behaviour changes.

## v1.5 (versionCode 6): video calls
- **Video calls on call websites** (Google Meet, Zoom, Jitsi, FaceTime links, Whereby, Teams). The camera and microphone come from:
  - a **USB webcam** on the TV (the Pixel in Webcam mode is one: plug it in, USB notification → Webcam), or
  - the **phone over Wi-Fi** through TV Overlay Suite 1.7 (its phone app opens a Phone camera screen by itself; the video goes straight from the phone to Saruki with WebRTC);
  - **Bluetooth earbuds** that support calls are used as the microphone (and the call sound goes to them). If the earbuds can't be a microphone on this TV, the phone's microphone is used instead.
  - Settings → **Video calls** picks the camera (Automatic / USB webcam / Phone) and microphone (Automatic / Earbuds / Phone / TV input) and lists the sites you allowed or blocked.
- **Asks once per site** (Allow / Allow this time / Block). Sites ask through a page script that stands in for `getUserMedia`; it also lists "Phone camera (Wi-Fi)" and "Phone microphone (Wi-Fi)" in the site's own device menu.
- **On a call:** a bar at the top right shows the call, camera and microphone. **⏯ Play/Pause** mutes and unmutes the microphone (also from the phone trackpad's Play key). **MENU** has Mute, Camera off, Switch phone camera (front / back) and **Hang up** (closes the call tab). The TV doesn't go to sleep during a call.
- Call sites open as desktop sites by default (you can still switch them in the menu).
- New permissions: camera, microphone, audio settings, Bluetooth (for the earbuds' microphone). The install script grants them; otherwise Android asks on the first call.
- New test `tests/test_calls_e2e.py`: the call page script and the suite's phone camera page in Chromium with a fake camera (no TV needed).

## v1.4 (versionCode 5): phone trackpad
- **Phone trackpad (TV Overlay Suite 1.6 phone app):** Saruki takes the Suite's Trackpad screen. Moves come through the Suite on the TV (Android `Messenger`, bound to `io.github.tvoverlaysuite/.PadService`, only messages from the Suite's app id are used) and are applied on the next frame (70% now, the rest the frame after) instead of easing in halfway each frame.
  - Tap = click (play/pause when a video fills the screen), two-finger scroll goes straight to the page, two-finger tap / hold = the context menu (quick menu on Home), tap-then-slide = drag, Back / Home / Menu / Play buttons.
  - On Home and in menus a swipe moves the highlight (one tile per 230 trackpad pixels).
  - Saruki tells the Suite when it comes to the front and when it leaves, so the phone shows the trackpad only while Saruki is on screen. A drag in progress is let go when Saruki leaves the front.
  - While in front and linked, Saruki holds a low-latency Wi-Fi lock (power saving off), so the phone's packets aren't held back by the TV's Wi-Fi.
- New permission `WAKE_LOCK` (for that Wi-Fi lock) and a `<queries>` entry for the Suite's package.
- The QR-code phone page keeps working as before.

## v1.3 (versionCode 4): YouTube sound fix
- **YouTube:** after an ad was skipped, the real video stayed muted. Saruki now turns the sound back on when the ad ends, and leaves alone any mute you set yourself.
- **Phone remote:** the phone page is prepared once instead of on every visit.

## v1.2 (versionCode 3): polish and smoothness
- **Pointer:** the pointer is now a small layer that moves without redrawing the screen, so it glides more smoothly on the TV. It grows a little over links and shrinks over text boxes, with a short animation.
- **Idle pointer:** after 5 s it fades to a faint dot (OK still clicks right away). After 30 s it hides. Before, it hid after 6 s and the first OK press only brought it back.
- **Phone touchpad:** the pointer glides between phone messages instead of jumping. Two-finger scroll is smooth. A tap gives a light vibration on the phone.
- **Scrolling:** tapping an arrow at the screen edge glides the page by about a quarter of the screen, instead of jumping. Boxes inside pages scroll smoothly, and sites that scroll the `<body>` instead of the page now scroll too. The page draws a little past the screen edges ahead of time (smoother scrolling), and there's no glow at page ends.
- **Pop-up & redirect shield (new, Strict by default):**
  - Only a click on a real, visible link may open a new tab, send the page to another site, or open another app such as the Play Store.
  - Pop-ups and pop-unders fired by a click on a video player, invisible "click anywhere" links, timed redirects and app-store jumps are stopped.
  - After a stopped pop-up, Saruki removes the invisible layer that caught the click, so the next click reaches the player.
  - MENU shows "Open blocked pop-up", "Go to blocked redirect" and "Allow pop-ups & redirects here".
  - Settings → Pop-up & redirect shield cycles Strict / Standard / Off. There's also a per-site switch.
  - Sign-in and payment windows (PayPal, Microsoft, Apple…) and link shorteners (t.co, bit.ly…) are let through.
- **Page dialogs:** alerts become a short note instead of a box that freezes the TV. "Leave this page?" always leaves. OK/Cancel questions open as a Saruki panel, and BACK answers Cancel.
- **Motion:** menus slide up, side lists slide in from the right, and dialogs grow in. They fade out instead of vanishing. Home fades in and out. Notes and the site chip glide in, and the loading bar fills smoothly. Focus lift uses one easing curve everywhere. The TV Player's bar fades in.
- **Fixes:**
  - Going Home pauses a video left playing on the page.
  - The Home clock keeps time.
  - Voice/web searches sent to Saruki open.
  - The phone remote keeps the same port, so an open phone page keeps working.
  - Each page now gets its own site settings at load, so it no longer briefly uses the previous site's.
  - The ▶ Play button is clickable with an edge margin set.

## v1.1 (versionCode 2)
- Phone touchpad moves the highlight on Home and in menus (one finger-length per tile).
- 32-bit ARM only (the TCL 55Q51K is armeabi-v7a): about 10 MB instead of 15 MB.
- A Bluetooth mouse hides Saruki's blue pointer while you use it.

## v1.0 (versionCode 1)
- First build.

## Signing key
`saruki.jks` (alias `saruki`) signs every official Saruki build. Android only installs an update signed with the same
key, so the maintainer keeps this file safe. **The key file and its password are never in this repository.** Official
builds pass them on the command line, `-PsarukiKeystore=/path/saruki.jks -PsarukiKeystorePassword=…`, or put the same
properties in `~/.gradle/gradle.properties` (outside the project).
Without the key file, `./gradlew assembleRelease` still works: it builds unsigned APKs (sign them with your own key to
install them). `./gradlew assembleDebug` builds an installable TV app signed with your local debug key.
