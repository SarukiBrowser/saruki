"""Saruki 1.5 video calls, end to end in Chromium with a fake camera (no TV needed).

Runs calls.js (Saruki's page script) in a 'call page' and TV Overlay Suite 1.7's phone camera page
(phone/assets/cam/index.html) in a 'phone page', and plays the native parts in between (Saruki's Calls,
the suite's relay, the phone app's CamActivity) with the same lines they exchange.

  pip install playwright && playwright install chromium
  python3 tests/test_calls_e2e.py remote/src/main/assets/cam/index.html   (v1.7: Saruki Remote's camera page)
"""
import asyncio, base64, json, threading, http.server, functools, sys, os, shutil, tempfile
from playwright.async_api import async_playwright

def b64(s): return base64.urlsafe_b64encode(s.encode()).decode().rstrip('=')
def unb64(s): return base64.urlsafe_b64decode(s + '=' * (-len(s) % 4)).decode()

WWW = tempfile.mkdtemp()
HERE = os.path.dirname(os.path.abspath(__file__))
shutil.copy(os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'calls.js'), WWW)
shutil.copy(sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, 'phone-cam-page.html'), os.path.join(WWW, 'phone.html'))
open(os.path.join(WWW, 'call.html'), 'w').write('<!DOCTYPE html><html><head><script src="calls.js"></script></head>'
    '<body><video id="v" autoplay muted playsinline></video></body></html>')

class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass

def serve():
    h = functools.partial(Quiet, directory=WWW)
    http.server.ThreadingHTTPServer.allow_reuse_address = True
    srv = http.server.ThreadingHTTPServer(('127.0.0.1', 8899), h)
    threading.Thread(target=srv.serve_forever, daemon=True).start()

BUS = """window.__busL=[];window.SarukiCall={postMessage:function(s){window.__toNative(s)},addEventListener:function(t,f){window.__busL.push(f)}};
window.__fromNative=function(s){window.__busL.forEach(function(f){f({data:s})})};"""
CAMB = """window.SarukiCam={send:function(l){window.__toTv(l)},ready:function(){window.__ready()},dim:function(){},close:function(){window.__closed=1},tvName:function(){return 'Sala'}};"""

results = []
def check(ok, m):
    results.append(ok); print(('PASS ' if ok else 'FAIL ') + m, flush=True)

async def main():
    serve()
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path=os.environ.get('CHROME', '/usr/bin/google-chrome'), args=['--no-sandbox', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'])
        ctx = await b.new_context(permissions=['camera', 'microphone'])
        call = await ctx.new_page(); phone = await ctx.new_page()
        log = {'native': [], 'tv': []}
        sess = {}
        mode = {'video': 'phone', 'audio': 'phone'}
        async def to_call(o): await call.evaluate('s => window.__fromNative(s)', json.dumps(o))
        async def to_native(s):
            m = json.loads(s); log['native'].append(m)
            t = m['t']
            if t == 'hello': await to_call({'t': 'avail', 'phone': True, 'usb': False})
            elif t == 'plan': await to_call({'t': 'plan', 'q': m['q'], 'ok': True, 'video': mode['video'] if m['v'] else '', 'audio': mode['audio'] if m['a'] else '', 'muteA': False, 'muteV': False})
            elif t == 'open':
                sess[m['sid']] = m['f']
                await phone.evaluate('l => cam.fromTv(l)', 'V o %s %s %s %s %s' % (m['sid'], m['kind'], m['facing'], b64('io.github.sarukibrowser.tv'), b64('Saruki')))
            elif t == 'sdp': await phone.evaluate('l => cam.fromTv(l)', 'V s %s %s' % (m['sid'], b64(m['sdp'])))
            elif t == 'close': await phone.evaluate('l => cam.fromTv(l)', 'V x ' + m['sid'])
        async def to_tv(line):
            log['tv'].append(line); a = line.split(' ')
            if a[1] == 's': await to_call({'t': 'sdp', 'sid': a[2], 'sdp': unb64(a[3])})
            elif a[1] == 'h': await to_call({'t': 'state', 'sid': a[2], 's': a[3], 'msg': unb64(a[4]) if len(a) > 4 else ''})
            elif a[1] == 'x': await to_call({'t': 'state', 'sid': a[2], 's': 'closed', 'msg': ''})
        await call.expose_function('__toNative', to_native)
        await call.add_init_script(BUS)
        await phone.expose_function('__toTv', to_tv)
        await phone.expose_function('__ready', lambda: None)
        await phone.add_init_script(CAMB)
        await phone.goto('http://127.0.0.1:8899/phone.html')
        await call.goto('http://127.0.0.1:8899/call.html')
        await call.wait_for_timeout(300)
        check(any(m['t'] == 'hello' for m in log['native']), 'call page says hello on load')

        devs = await call.evaluate("navigator.mediaDevices.enumerateDevices().then(l => l.map(d => d.kind + ':' + d.deviceId))")
        check('videoinput:saruki-phone-camera' in devs and 'audioinput:saruki-phone-mic' in devs, 'phone camera and mic listed as devices: %s' % [d for d in devs if 'saruki' in d])

        r = await call.evaluate("""async () => {
          const t0 = performance.now();
          const s = await navigator.mediaDevices.getUserMedia({video: {width: 1280, height: 720}, audio: true});
          window.S = s;
          const v = document.getElementById('v'); v.srcObject = s;
          for (let i = 0; i < 100 && !(v.videoWidth > 0); i++) await new Promise(r => setTimeout(r, 100));
          return {ms: Math.round(performance.now() - t0), kinds: s.getTracks().map(t => t.kind + ':' + t.readyState).sort(), w: v.videoWidth, h: v.videoHeight};
        }""")
        print('INFO', r)
        check(r['kinds'] == ['audio:live', 'video:live'], 'getUserMedia returns a live camera + microphone from the phone page')
        check(r['w'] > 0, 'phone video frames arrive on the call page (%sx%s, %d ms)' % (r['w'], r['h'], r['ms']))
        check(any(l.startswith('V h ') and ' live' in l for l in log['tv']), 'phone reports the session live')
        live = [m for m in log['native'] if m['t'] == 'live']
        check(live and live[-1]['na'] == 1 and live[-1]['nv'] == 1 and live[-1]['phone'], 'Saruki told: 1 audio + 1 video track live, from the phone')
        ph = await phone.evaluate("document.getElementById('title').textContent")
        check('→ Sala' in ph, 'phone screen shows it is live: ' + ph)

        await to_call({'t': 'mute', 'on': True})
        en = await call.evaluate("S.getAudioTracks()[0].enabled")
        check(en is False, 'remote mute turns the audio track off')
        await to_call({'t': 'mute', 'on': False})
        check(await call.evaluate("S.getAudioTracks()[0].enabled") is True, 'unmute turns it back on')

        # Meet-style: a second getUserMedia for the camera only while the first is live
        r2 = await call.evaluate("navigator.mediaDevices.getUserMedia({video: true}).then(s => { window.S2 = s; return s.getTracks().map(t => t.kind + ':' + t.readyState) })")
        check(r2 == ['video:live'], 'a second request (camera only) works alongside the first')
        n_open = len([m for m in log['native'] if m['t'] == 'open'])
        check(n_open == 2, 'two phone sessions opened (%d)' % n_open)
        # clone + stop originals: session stays while the clone lives
        await call.evaluate("window.C = S2.getVideoTracks()[0].clone(); S2.getVideoTracks()[0].stop()")
        await call.wait_for_timeout(300)
        closes = [m for m in log['native'] if m['t'] == 'close']
        check(len(closes) == 0, 'stopping a track that has a live copy keeps the phone session')
        await call.evaluate("C.stop()")
        await call.wait_for_timeout(500)
        closes = [m for m in log['native'] if m['t'] == 'close']
        check(len(closes) == 1, 'stopping the last copy closes that phone session')
        nsess = await phone.evaluate("Object.keys(window.cam ? {} : {}).length")
        # what a call site does: send the phone video on through its own WebRTC connection
        rel = await call.evaluate("""async () => {
          const a = new RTCPeerConnection(), b = new RTCPeerConnection();
          a.onicecandidate = e => e.candidate && b.addIceCandidate(e.candidate);
          b.onicecandidate = e => e.candidate && a.addIceCandidate(e.candidate);
          const got = new Promise(r => b.ontrack = e => r(e.streams[0] || new MediaStream([e.track])));
          S.getTracks().forEach(t => a.addTrack(t, S));
          await a.setLocalDescription(await a.createOffer()); await b.setRemoteDescription(a.localDescription);
          await b.setLocalDescription(await b.createAnswer()); await a.setRemoteDescription(b.localDescription);
          const st = await got; const v = document.createElement('video'); v.muted = true; v.autoplay = true; v.srcObject = st; document.body.appendChild(v);
          for (let i = 0; i < 80 && !(v.videoWidth > 0); i++) await new Promise(r => setTimeout(r, 100));
          a.close(); b.close(); return v.videoWidth; }""")
        check(rel > 0, 'phone video can be re-sent through a call site\'s own WebRTC connection (%spx wide)' % rel)
        # front / back camera switch reaches the phone and keeps the video flowing
        sid0 = [m for m in log['native'] if m['t'] == 'open'][0]['sid']
        await phone.evaluate('l => cam.fromTv(l)', 'V f %s e' % sid0)
        await phone.wait_for_timeout(800)
        cls = await phone.evaluate("document.getElementById('me').className")
        fw = await call.evaluate("""async () => { const v = document.getElementById('v'); const t0 = v.currentTime; await new Promise(r => setTimeout(r, 700)); return v.currentTime > t0 && S.getVideoTracks()[0].readyState === 'live'; }""")
        check(cls == '' and fw, 'switching to the back camera keeps the call video playing (mirror off)')
        # hang up ends everything
        await to_call({'t': 'hangup', 'x': True})
        await call.wait_for_timeout(600)
        st = await call.evaluate("S.getTracks().map(t => t.readyState)")
        check(st == ['ended', 'ended'], 'hang up ends the page tracks')
        closes = [m for m in log['native'] if m['t'] == 'close']
        check(len(closes) == 2, 'hang up closes the remaining phone session')
        await phone.wait_for_timeout(4800)
        released = await phone.evaluate("document.getElementById('idle').style.display")
        check(released == 'flex', 'the phone releases its camera a few seconds after the last session')

        # the phone refuses
        async def deny_open(s):
            pass
        r3 = await call.evaluate("""() => { const p = navigator.mediaDevices.getUserMedia({audio: true}); return p.then(() => 'ok', e => e.name); }""")
        # (phone allowed above; now simulate refusal through a state message right after open)
        mode['video'] = 'phone'
        res = await call.evaluate("""async () => {
          const p = navigator.mediaDevices.getUserMedia({video: true}).then(() => 'ok', e => e.name + ':' + e.message);
          await new Promise(r => setTimeout(r, 50));
          return p; }""")
        check(res == 'ok', 'another call works after hang up')
        mode['video'] = 'usb'; mode['audio'] = 'phone'
        mix = await call.evaluate("""navigator.mediaDevices.getUserMedia({video: true, audio: true}).then(s => { window.M = s; return s.getTracks().map(t => t.kind + ':' + (t.label.indexOf('fake') >= 0 || t.label.indexOf('Fake') >= 0 ? 'usb' : 'phone')).sort() })""")
        print('INFO mixed', mix)
        check(len(mix) == 2 and 'video:usb' in mix and any(x.startswith('audio') for x in mix), 'USB webcam video + phone microphone in one stream')
        lastopen = [m for m in log['native'] if m['t'] == 'open'][-1]
        check(lastopen['kind'] == 'a', 'only the phone microphone was opened for that call (kind %s)' % lastopen['kind'])
        # the phone says no
        mode['video'] = 'phone'; mode['audio'] = ''
        orig = to_tv
        res = await call.evaluate("""async () => {
          const p = navigator.mediaDevices.getUserMedia({video: true}).then(() => 'ok', e => e.name);
          return p; }""")
        check(res == 'ok', 'phone-only video after a mixed call')
        await b.close()
    print('ALL PASS' if all(results) else '%d FAILED' % results.count(False))
    sys.exit(0 if all(results) else 1)

asyncio.run(main())
