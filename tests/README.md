# Saruki checks that run on a computer
- `ShieldTest.java`: pop-up & redirect shield rules.
  `javac -d out app/src/main/java/io/github/sarukibrowser/tv/Shield.java tests/ShieldTest.java && java -cp out io.github.sarukibrowser.tv.ShieldTest`
- `test_page.py`: the page script (`assets/saruki.js`) in headless Chrome via Playwright (click reporting, invisible-layer removal, scrolling).
  Edit the paths at the top to point at this source folder, then run `python3 tests/test_page.py`.
- `test_calls_e2e.py` (v1.5): video calls end to end in Chromium with a fake camera. Runs `assets/calls.js` in a call page and
  TV Overlay Suite 1.7's phone camera page in a phone page, with the native relays played in between.
  `python3 tests/test_calls_e2e.py remote/src/main/assets/cam/index.html` (Saruki Remote's camera page; TV Overlay
  Suite's `phone/assets/cam/index.html` works too)
- `test_type.py` (v1.7): typing from Saruki Remote (`assets/type.js`) in headless Chrome: search, text, email,
  password, textarea, rich text, React-style boxes, iframes. `python3 tests/test_type.py`
- `link/` (v1.7): the Saruki Remote link on a computer. `bash tests/link/run.sh` runs the protocol tests (typing,
  camera) and LinkTest: Saruki's real server against the phone app's real client (pairing, lockout, trackpad,
  typing, camera relay, pairing page and app download).
- `MigrateTest.java` (v1.8): the format Saruki uses to bring over favorites, tabs and settings from the earlier
  Saruki (every value and type kept; per-install items left behind). Needs `org.json` (json-20240303.jar) before
  `android.jar` on the class path:
  `javac --release 17 -cp json.jar:$ANDROID_HOME/platforms/android-36/android.jar -d out app/src/main/java/io/github/sarukibrowser/tv/{Migrate,Prefs,Db}.java tests/MigrateTest.java && java -cp out:json.jar:$ANDROID_HOME/platforms/android-36/android.jar io.github.sarukibrowser.tv.MigrateTest`
