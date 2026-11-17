"""v1.3: the YouTube ad skipper mutes during an ad and restores sound after it, but never unmutes what the viewer muted.
Usage: python3 tests/test_youtube.py <path-to-saruki.js> <chrome-path>"""
import sys
from playwright.sync_api import sync_playwright

SCRIPT = open(sys.argv[1]).read()
FAKE = "window.SarukiNative={flags:function(){return 'y';},flagsFor:function(){return 'y';},vw:function(){return 0;},media:function(){},clicked:function(){}};"
PAGE = """<!doctype html><html><body><div id=movie_player class="html5-video-player">
<video id=v></video></div></body></html>"""
ok = True
def check(name, cond):
    global ok
    print(('PASS ' if cond else 'FAIL ') + name); ok = ok and cond

with sync_playwright() as p:
    b = p.chromium.launch(executable_path=sys.argv[2], args=['--no-sandbox'])
    ctx = b.new_context()
    ctx.route('https://www.youtube.com/**', lambda r: r.fulfill(status=200, content_type='text/html', body=PAGE))
    ctx.add_init_script(FAKE); ctx.add_init_script(SCRIPT)
    pg = ctx.new_page(); pg.goto('https://www.youtube.com/watch?v=x')
    pg.wait_for_timeout(300)
    check('no ad: video not muted', pg.evaluate("document.getElementById('v').muted") is False)
    pg.evaluate("document.getElementById('movie_player').classList.add('ad-showing')")
    pg.wait_for_timeout(600)
    check('ad showing: muted', pg.evaluate("document.getElementById('v').muted") is True)
    pg.evaluate("document.getElementById('movie_player').classList.remove('ad-showing')")
    pg.wait_for_timeout(600)
    check('ad over: sound back on (the v1.2 bug)', pg.evaluate("document.getElementById('v').muted") is False)
    pg.evaluate("document.getElementById('v').muted = true")   # viewer mutes
    pg.evaluate("document.getElementById('movie_player').classList.add('ad-showing')")
    pg.wait_for_timeout(600)
    pg.evaluate("document.getElementById('movie_player').classList.remove('ad-showing')")
    pg.wait_for_timeout(600)
    check('viewer muted before the ad: stays muted after it', pg.evaluate("document.getElementById('v').muted") is True)
    btn = "var b=document.createElement('button');b.className='ytp-skip-ad-button';b.onclick=function(){window.__skipped=1};document.body.appendChild(b)"
    pg.evaluate(btn); pg.wait_for_timeout(600)
    check('Skip button pressed', pg.evaluate("window.__skipped") == 1)
    b.close()
print('ALL PASS' if ok else 'SOME FAILED'); sys.exit(0 if ok else 1)
