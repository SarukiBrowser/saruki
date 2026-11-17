#!/usr/bin/env python3
"""v1.7: type.js (typing from Saruki Remote into page boxes) in headless Chrome. No TV needed.

A fake "SarukiType" bridge stands in for Saruki: it records what the page script reports and hands it edits the way
Saruki does. Run:  python3 tests/test_type.py   (needs `pip install playwright` and Chrome or Chromium)
"""
import json, os, sys
from playwright.sync_api import sync_playwright

HERE = os.path.dirname(os.path.abspath(__file__))
TYPE_JS = open(os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'type.js')).read()

BRIDGE = """
window.__out = []; window.__ls = [];
window.SarukiType = {
  postMessage: function (s) { window.__out.push(JSON.parse(s)); },
  addEventListener: function (t, f) { window.__ls.push(f); }
};
window.__to = function (m) { window.__ls.forEach(function (f) { f({ data: JSON.stringify(m) }); }); };
"""

PAGE = """<!doctype html><html><body>
<form id=f action="javascript:void(0)" onsubmit="window.__submitted=(window.__submitted||0)+1; return false">
  <input id=q type=search placeholder="Search videos" name=q>
</form>
<input id=t type=text placeholder="Your name" value="abc">
<input id=em type=email aria-label="Email">
<input id=pw type=password placeholder="Password">
<textarea id=ta placeholder="Message"></textarea>
<div id=ce contenteditable=true>hello <b>world</b></div>
<input id=ctl type=text placeholder="controlled">
<script>
  // like React: keeps its own copy from input events, and re-renders the value from it
  window.__ctl = '';
  document.getElementById('ctl').addEventListener('input', function (e) { window.__ctl = e.target.value; });
  window.__inputs = 0;
  document.addEventListener('input', function () { window.__inputs++; }, true);
</script>
</body></html>"""

fails = 0
def check(ok, what):
    global fails
    print(('PASS ' if ok else 'FAIL ') + what)
    if not ok: fails += 1

def chrome():
    for p in ('/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser'):
        if os.path.exists(p): return p
    return None

with sync_playwright() as pw:
    b = pw.chromium.launch(executable_path=chrome(), headless=True)
    pg = b.new_page()
    pg.add_init_script(BRIDGE + TYPE_JS)
    pages = {'/': PAGE}
    pg.route('http://saruki.test/**', lambda r: r.fulfill(status=200, content_type='text/html', body=pages.get(r.request.url.replace('http://saruki.test', ''), PAGE)))
    pg.goto('http://saruki.test/')
    out = lambda: pg.evaluate("window.__out")
    clear = lambda: pg.evaluate("window.__out = []")
    to = lambda m: pg.evaluate("m => window.__to(m)", m)

    check(any(o['t'] == 'hi' for o in out()), 'script announces its frame')
    fid = out()[0]['f']

    # ---- focus a search box ----
    clear(); pg.click('#q'); pg.wait_for_timeout(50)
    o = [x for x in out() if x['t'] == 'open']
    check(len(o) == 1 and o[0]['a'] == 'Search' and o[0]['h'] == 'Search videos' and o[0]['text'] == '', 'search box opens with Search action and hint')
    n = o[0]['n']
    # type "hel", then "lo" (two edits), then fix a letter in the middle
    to({'t': 'edit', 'f': fid, 'n': n, 's': 0, 'd': 0, 'c': 3, 'x': 'hel'})
    to({'t': 'edit', 'f': fid, 'n': n, 's': 3, 'd': 0, 'c': 5, 'x': 'lo'})
    to({'t': 'edit', 'f': fid, 'n': n, 's': 1, 'd': 1, 'c': 2, 'x': 'a'})
    v = pg.evaluate("[document.getElementById('q').value, document.getElementById('q').selectionStart]")
    check(v == ['hallo', 2], 'edits play into the box with the cursor where the phone put it: %s' % v)
    check(not any(x['t'] == 'state' for x in out()), 'its own edits are not echoed back')
    to({'t': 'edit', 'f': fid, 'n': n, 's': 4, 'd': 1, 'c': 4, 'x': ''})
    check(pg.evaluate("document.getElementById('q').value") == 'hall', 'backspace from the phone')
    to({'t': 'enter', 'f': fid, 'n': n})
    check(pg.evaluate("window.__submitted") == 1, 'Search on the phone submits the form')

    # ---- page changes the box by itself -> state ----
    clear()
    pg.evaluate("var q=document.getElementById('q'); q.value='hall of fame'; q.dispatchEvent(new Event('input',{bubbles:true}))")
    pg.wait_for_timeout(200)
    st = [x for x in out() if x['t'] == 'state']
    check(len(st) == 1 and st[0]['text'] == 'hall of fame', 'page-made change goes to the phone')

    # ---- moving focus closes the old box and opens the new one ----
    clear(); pg.click('#t'); pg.wait_for_timeout(400)
    kinds = [x['t'] for x in out()]
    check('close' in kinds and 'open' in kinds, 'switching boxes: close + open (%s)' % kinds)
    o = [x for x in out() if x['t'] == 'open'][-1]
    check(o['text'] == 'abc' and o['h'] == 'Your name' and o['a'] == 'Enter', 'plain text box with its current text')

    # ---- email box (no selection API): falls back to setting the value ----
    clear(); pg.click('#em'); pg.wait_for_timeout(50)
    o = [x for x in out() if x['t'] == 'open'][-1]
    check(o['fl'] & 8 == 8, 'email box is flagged as email')
    to({'t': 'edit', 'f': fid, 'n': o['n'], 's': 0, 'd': 0, 'c': 9, 'x': 'me@x.com'})
    check(pg.evaluate("document.getElementById('em').value") == 'me@x.com', 'typing into an email box')

    # ---- password flag ----
    clear(); pg.click('#pw'); pg.wait_for_timeout(50)
    o = [x for x in out() if x['t'] == 'open'][-1]
    check(o['fl'] & 1 == 1, 'password box is flagged (the phone hides it)')

    # ---- textarea with a line break ----
    clear(); pg.click('#ta'); pg.wait_for_timeout(50)
    o = [x for x in out() if x['t'] == 'open'][-1]
    check(o['fl'] & 2 == 2 and o['a'] == '', 'textarea is multi-line, Enter makes a new line')
    to({'t': 'edit', 'f': fid, 'n': o['n'], 's': 0, 'd': 0, 'c': 9, 'x': 'hi\nthere'})
    check(pg.evaluate("document.getElementById('ta').value") == 'hi\nthere', 'line break in a textarea')

    # ---- contenteditable ----
    clear(); pg.click('#ce'); pg.wait_for_timeout(50)
    o = [x for x in out() if x['t'] == 'open'][-1]
    check(o['text'] == 'hello world', 'rich text box text: %r' % o['text'])
    to({'t': 'edit', 'f': fid, 'n': o['n'], 's': 11, 'd': 0, 'c': 12, 'x': '!'})
    to({'t': 'edit', 'f': fid, 'n': o['n'], 's': 0, 'd': 5, 'c': 3, 'x': 'Hey'})
    check(pg.evaluate("document.getElementById('ce').textContent") == 'Hey world!', 'edits in a rich text box: %r' % pg.evaluate("document.getElementById('ce').textContent"))

    # ---- controlled input (React style) sees real input events ----
    clear(); pg.click('#ctl'); pg.wait_for_timeout(50)
    o = [x for x in out() if x['t'] == 'open'][-1]
    to({'t': 'edit', 'f': fid, 'n': o['n'], 's': 0, 'd': 0, 'c': 4, 'x': 'test'})
    check(pg.evaluate("window.__ctl") == 'test', 'controlled inputs get input events (React / Vue forms)')

    # ---- edits for an old box are ignored ----
    to({'t': 'edit', 'f': fid, 'n': n, 's': 0, 'd': 0, 'c': 1, 'x': 'Z'})
    check(pg.evaluate("document.getElementById('q').value") == 'hall of fame', 'edit for a box that closed is ignored')
    to({'t': 'edit', 'f': 'OTHERFRAME', 'n': o['n'], 's': 0, 'd': 0, 'c': 1, 'x': 'Z'})
    check(pg.evaluate("window.__ctl") == 'test', 'edit for another frame is ignored')

    # ---- "Type" on the phone with the box already focused ----
    clear(); r = pg.evaluate("window.__sarukiTypeNow()")
    o = [x for x in out() if x['t'] == 'open']
    check(r == 1 and len(o) == 1 and o[0]['why'] == 'ask', 'phone asks: focused box announces itself')
    pg.evaluate("document.activeElement.blur()"); pg.wait_for_timeout(350)
    check(pg.evaluate("window.__sarukiTypeNow()") == 0, 'phone asks with nothing focused: none')

    # ---- a box inside an iframe reports from its own frame ----
    pages['/f'] = PAGE + "<iframe id=fr src='/inner'></iframe>"
    pages['/inner'] = "<input id=in placeholder='Inside'>"
    pg.goto('http://saruki.test/f')
    pg.wait_for_timeout(300)
    fr = pg.frame_locator('#fr')
    clear(); fr.locator('#in').click(); pg.wait_for_timeout(100)
    frame = [f for f in pg.frames if f != pg.main_frame][0]
    fo = frame.evaluate("window.__out || []")
    check(any(x['t'] == 'open' and x['h'] == 'Inside' for x in fo), 'box in an iframe announces itself from the iframe')
    b.close()

print('ALL PASS' if fails == 0 else 'FAILURES: %d' % fails)
sys.exit(1 if fails else 0)
