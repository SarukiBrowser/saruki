import json, sys
from playwright.sync_api import sync_playwright

SCRIPT = open(__import__('os').path.join(__import__('os').path.dirname(__file__), '..', 'app/src/main/assets/saruki.js')).read()
FAKE = """
window.__log = [];
window.SarukiNative = {
  flags: function(){ __log.push(['flags']); return 'acs'; },
  flagsFor: function(h){ __log.push(['flagsFor', h]); return 'acs'; },
  vw: function(){ return 0; },
  media: function(){},
  clicked: function(h){ __log.push(['clicked', h]); }
};
"""
PAGE = """<!doctype html><html><head><meta name=viewport content="width=device-width"></head><body style="margin:0;font:16px sans-serif">
<p style="padding:20px"><a id=real href="https://wiki.example.org/page">A real link</a> <span id=plain>plain text here for the test</span></p>
<div id=player style="position:relative;width:600px;height:300px;margin:20px;background:#222">
  <video id=vid style="width:100%;height:100%"></video>
  <div id=jack style="position:absolute;inset:0;background:transparent;z-index:5"></div>
</div>
<a id=bigtrap href="https://casino.example.io/" style="position:fixed;inset:0;z-index:9;opacity:0.01"></a>
<script>document.addEventListener('click', function(e){ e.preventDefault(); });</script>
</body></html>"""

ok = True
def check(name, cond, extra=''):
    global ok
    print(('PASS ' if cond else 'FAIL ') + name + (' ' + str(extra) if not cond else ''))
    ok = ok and cond

with sync_playwright() as p:
    b = p.chromium.launch(executable_path=sys.argv[1] if len(sys.argv) > 1 else '/usr/bin/google-chrome', args=['--no-sandbox'])
    ctx = b.new_context(viewport={'width': 960, 'height': 540}, device_scale_factor=2)
    PAGES = {}
    ctx.route('https://test.local/**', lambda route: route.fulfill(status=200, content_type='text/html', body=PAGES[route.request.url.split('/')[-1]]))
    ctx.add_init_script(FAKE)
    ctx.add_init_script(SCRIPT)
    def load(name, html):
        PAGES[name] = html
        pg_ = ctx.new_page()
        pg_.goto('https://test.local/' + name)
        return pg_
    pg = load('a', PAGE)
    log = pg.evaluate("__log")
    check('top page asks flags with its own host', ['flagsFor', 'test.local'] in log, log)

    S = "window.__saruki"
    # the big invisible trap link covers everything: a click on it reports ""
    pg.evaluate("__log=[]")
    pg.mouse.click(480, 500)
    log = pg.evaluate("__log")
    check('click on invisible full-screen link reports no link', ['clicked', ''] in log, log)

    # remove the trap, then a real link reports its href
    pg.evaluate("document.getElementById('bigtrap').remove(); __log=[]")
    box = pg.locator('#real').bounding_box()
    pg.mouse.click(box['x'] + 5, box['y'] + 5)
    log = pg.evaluate("__log")
    check('click on real link reports href', ['clicked', 'https://wiki.example.org/page'] in log, log)

    pg.evaluate("__log=[]")
    box = pg.locator('#plain').bounding_box()
    pg.mouse.click(box['x'] + 5, box['y'] + 5)
    log = pg.evaluate("__log")
    check('click on text reports no link', ['clicked', ''] in log, log)

    # unjack: native coords are css*2 with setVW(1920)
    pb = pg.locator('#player').bounding_box()
    nx, ny = int((pb['x'] + 300) * 2), int((pb['y'] + 150) * 2)
    r = pg.evaluate(f"{S}.setVW(1920), {S}.unjack({nx},{ny})")
    check('unjack removes transparent layer over player', r == 1 and pg.evaluate("!document.getElementById('jack')"), r)
    r = pg.evaluate(f"{S}.unjack({nx},{ny})")
    check('unjack leaves the video itself alone', r == 0 and pg.evaluate("!!document.getElementById('vid')"), r)
    bx = pg.locator('#plain').bounding_box()
    r = pg.evaluate(f"{S}.unjack({int((bx['x']+5)*2)},{int((bx['y']+5)*2)})")
    check('unjack on normal text does nothing', r == 0, r)

    # body as the scrolling box (html overflow hidden, body scrolls)
    pg2 = load('b', """<!doctype html><html style="height:100%;overflow:hidden"><body style="margin:0;height:100%;overflow-y:auto">
      <div style="height:5000px;background:linear-gradient(#fff,#888)">tall</div></body></html>""")
    t = pg2.evaluate(f"{S}.setVW(1920), {S}.target(960,540,0,1)")
    check('body scroller is found as the box to scroll', t == 1, t)
    pg2.evaluate(f"{S}.sbs(0,600); {S}.sbs(0,600)")   # two quick presses add up: 1200 px native = 600 css
    pg2.wait_for_timeout(1200)
    st = pg2.evaluate("document.body.scrollTop")
    check('two quick smooth scrolls add up (about 600 css px)', 560 <= st <= 640, st)

    # normal page: page itself is the target (0) and the old helpers still work
    pg3 = load('c', """<!doctype html><body style="margin:0"><div style="height:4000px">
      <div id=box style="height:200px;width:400px;overflow:auto;margin-top:100px"><div style="height:2000px">inner</div></div>
      <a id=l2 href="https://x.example.com" style="display:inline-block;margin:40px">link two</a></div></body>""")
    t = pg3.evaluate(f"{S}.setVW(1920), {S}.target(1800,1000,0,1)")
    check('plain page scrolls as the page (0)', t == 0, t)
    bb = pg3.locator('#box').bounding_box()
    t = pg3.evaluate(f"{S}.target({int((bb['x']+50)*2)},{int((bb['y']+50)*2)},0,1)")
    check('inner box under pointer is the target (1)', t == 1, t)
    lb = pg3.locator('#l2').bounding_box()
    h = pg3.evaluate(f"{S}.hit({int((lb['x']+5)*2)},{int((lb['y']+5)*2)})")
    check('hit() still detects links', h == 'c', h)
    b.close()

print('ALL PASS' if ok else 'SOME FAILED')
sys.exit(0 if ok else 1)
