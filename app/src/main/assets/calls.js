// Saruki v1.5: video calls. Runs in every page and frame before the page's own scripts.
// It stands in for getUserMedia: Saruki decides which camera and microphone to use (USB webcam, Bluetooth earbuds,
// or the phone over Wi-Fi through TV Overlay Suite), and the page gets one ordinary MediaStream.
(function () {
  'use strict';
  if (window.__sarukiCall) return;
  window.__sarukiCall = true;
  var bus = window.SarukiCall;
  var md = navigator.mediaDevices;
  if (!bus || !md || !md.getUserMedia) return;   // no bridge, or not a secure page (no cameras there anyway)

  var PHONE_CAM = 'saruki-phone-camera', PHONE_MIC = 'saruki-phone-mic';
  var RPC = window.RTCPeerConnection, MS = window.MediaStream, DOMEx = window.DOMException;
  var gum0 = md.getUserMedia.bind(md);
  var enum0 = md.enumerateDevices ? md.enumerateDevices.bind(md) : null;
  var TP = MediaStreamTrack.prototype, stop0 = TP.stop, clone0 = TP.clone;
  var top = window === window.top;

  function rid(n) {
    var c = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789', r = new Uint32Array(n), s = '';
    crypto.getRandomValues(r);
    for (var i = 0; i < n; i++) s += c[r[i] % c.length];
    return s;
  }
  var fid = rid(10);
  var avail = { phone: false, usb: false, known: false }, availWait = [];
  var q = 0, asks = {}, sessions = {}, live = new Set(), mute = { a: false, v: false }, hello = false;

  function send(o) { o.f = fid; try { bus.postMessage(JSON.stringify(o)); } catch (e) {} }
  function sayHello() { if (!hello) { hello = true; send({ t: 'hello' }); } }
  bus.addEventListener('message', function (ev) {
    var m;
    try { m = JSON.parse(ev.data); } catch (e) { return; }
    switch (m.t) {
      case 'avail':
        avail.phone = !!m.phone; avail.usb = !!m.usb; avail.known = true;
        availWait.splice(0).forEach(function (f) { f(); });
        break;
      case 'plan': var a = asks[m.q]; if (a) { delete asks[m.q]; a(m); } break;
      case 'sdp': offer(m.sid, m.sdp); break;
      case 'state': state(m.sid, m.s, m.msg); break;
      case 'mute': mute.a = !!m.on; apply('audio', !mute.a); break;
      case 'vid': mute.v = !!m.off; apply('video', !mute.v); break;
      case 'hangup': hangup(); break;
    }
  });

  function err(name, msg) { try { return new DOMEx(msg || name, name); } catch (e) { var x = new Error(msg || name); x.name = name; return x; } }
  function apply(kind, on) { live.forEach(function (t) { if (t.kind === kind) t.enabled = on; }); }

  function devId(c) {
    if (!c || typeof c !== 'object' || !c.deviceId) return '';
    var d = c.deviceId;
    if (typeof d === 'string') return d;
    if (Array.isArray(d)) return d[0] || '';
    var v = d.exact || d.ideal || '';
    return Array.isArray(v) ? (v[0] || '') : String(v);
  }
  function withoutPhoneId(c) {
    var id = devId(c);
    if (id !== PHONE_CAM && id !== PHONE_MIC) return c;
    var o = Object.assign({}, c);
    delete o.deviceId;
    return Object.keys(o).length ? o : true;
  }
  function facing(c) {
    if (!c || typeof c !== 'object' || !c.facingMode) return 'u';
    var f = c.facingMode;
    if (typeof f === 'object' && !Array.isArray(f)) f = f.exact || f.ideal || '';
    if (Array.isArray(f)) f = f[0];
    return f === 'environment' ? 'e' : 'u';
  }

  function plan(v, a, vid, aid) {
    return new Promise(function (res) {
      var id = ++q;
      asks[id] = res;
      send({ t: 'plan', q: id, v: v, a: a, vid: vid, aid: aid });
      // the site question may wait for you to answer on the TV
      setTimeout(function () { if (asks[id]) { delete asks[id]; res({ ok: false, err: 'NotAllowedError', msg: 'No answer' }); } }, 180000);
    });
  }

  // ---------------------------------------------------------------- getUserMedia
  function gum(c) {
    c = c || {};
    var wv = !!c.video, wa = !!c.audio;
    if (!wv && !wa) return gum0(c);
    sayHello();
    return plan(wv, wa, devId(c.video), devId(c.audio)).then(function (p) {
      if (!p.ok) throw err(p.err || 'NotAllowedError', p.msg);
      mute.a = !!p.muteA; mute.v = !!p.muteV;
      var real = {}, kind = '';
      if (wv) { if (p.video === 'usb') real.video = withoutPhoneId(c.video); else if (p.video === 'phone') kind += 'v'; }
      if (wa) { if (p.audio === 'real') real.audio = withoutPhoneId(c.audio); else if (p.audio === 'phone') kind += 'a'; }
      var mine = (real.video || real.audio) ? gumReal(real) : Promise.resolve([]);
      var phone = kind ? phoneStream(kind, facing(c.video)) : Promise.resolve([]);
      return Promise.allSettled([mine, phone]).then(function (r) {
        var got = [], fail = null;
        r.forEach(function (x) { if (x.status === 'fulfilled') got = got.concat(x.value); else if (!fail) fail = x.reason; });
        if (fail) {
          got.forEach(function (t) { try { stop0.call(t); } catch (e) {} if (t.__sarukiSid) close(t.__sarukiSid); });
          throw fail;
        }
        got.forEach(function (t) { adopt(t); if (t.__sarukiSid && sessions[t.__sarukiSid]) sessions[t.__sarukiSid].adopted = true; });
        changed();
        return new MS(got);
      });
    });
  }

  function gumReal(real) {
    return gum0(real).then(function (s) { return s.getTracks(); }, function (e) {
      // no microphone on the TV: carry on with the camera and say so
      if (real.video && real.audio) return gum0({ video: real.video }).then(function (s) { send({ t: 'nomic' }); return s.getTracks(); });
      throw e;
    });
  }

  function adopt(t) {
    live.add(t);
    if (t.kind === 'audio' && mute.a) t.enabled = false;
    if (t.kind === 'video' && mute.v) t.enabled = false;
    t.addEventListener('ended', function () { drop(t); });
  }

  // tracks the page stops (or copies) are followed, so Saruki knows when the call's camera is off
  TP.stop = function () { var r = stop0.apply(this, arguments); if (live.has(this)) drop(this); return r; };
  TP.clone = function () {
    var t = clone0.apply(this, arguments);
    if (live.has(this)) { if (this.__sarukiSid) t.__sarukiSid = this.__sarukiSid; adopt(t); changed(); }
    return t;
  };

  function drop(t) { if (live.delete(t)) changed(); }

  function changed() {
    Object.keys(sessions).forEach(function (sid) {
      var s = sessions[sid];
      if (!s.adopted) return;
      var any = false;
      live.forEach(function (t) { if (t.__sarukiSid === sid && t.readyState !== 'ended') any = true; });
      if (!any) close(sid);
    });
    var na = 0, nv = 0, ph = false;
    live.forEach(function (t) {
      if (t.readyState === 'ended') { live.delete(t); return; }
      if (t.kind === 'audio') na++; else nv++;
      if (t.__sarukiSid) ph = true;
    });
    send({ t: 'live', na: na, nv: nv, phone: ph });
  }

  // ---------------------------------------------------------------- the phone over Wi-Fi
  function phoneStream(kind, face) {
    return new Promise(function (res, rej) {
      var sid = rid(8);
      var pc = new RPC({ iceServers: [] });
      var s = sessions[sid] = { pc: pc, kind: kind, tracks: [], done: false };
      s.fail = function (e) {
        if (s.done) return;
        s.done = true;
        clearTimeout(s.timer);
        close(sid);
        rej(e);
      };
      s.timer = setTimeout(function () { s.fail(err('NotReadableError', 'The phone camera did not start')); }, 30000);
      pc.ontrack = function (e) {
        if (s.tracks.indexOf(e.track) < 0) { e.track.__sarukiSid = sid; s.tracks.push(e.track); }
        ready();
      };
      pc.oniceconnectionstatechange = function () {
        var st = pc.iceConnectionState;
        if (st === 'connected' || st === 'completed') ready();
        else if (st === 'failed') { if (!s.done) s.fail(err('NotReadableError', 'Could not reach the phone over Wi-Fi')); else ended(sid); }
      };
      function ready() {
        if (s.done || s.tracks.length < kind.length) return;
        var st = pc.iceConnectionState;
        if (st !== 'connected' && st !== 'completed') return;
        s.done = true;
        clearTimeout(s.timer);
        res(s.tracks.slice());
      }
      send({ t: 'open', sid: sid, kind: kind, facing: face });
    });
  }

  function gathered(pc) {
    return new Promise(function (res) {
      if (pc.iceGatheringState === 'complete') return res();
      var t = setTimeout(res, 2000);
      pc.addEventListener('icegatheringstatechange', function () {
        if (pc.iceGatheringState === 'complete') { clearTimeout(t); res(); }
      });
    });
  }

  function offer(sid, sdp) {
    var s = sessions[sid];
    if (!s || s.answered) return;
    s.answered = true;
    var pc = s.pc;
    pc.setRemoteDescription({ type: 'offer', sdp: sdp })
      .then(function () { return pc.createAnswer(); })
      .then(function (a) { return pc.setLocalDescription(a); })
      .then(function () { return gathered(pc); })
      .then(function () { if (sessions[sid]) send({ t: 'sdp', sid: sid, sdp: pc.localDescription.sdp }); })
      .catch(function (e) { s.fail(err('NotReadableError', 'Phone camera: ' + e)); });
  }

  function state(sid, st, msg) {
    var s = sessions[sid];
    if (!s) return;
    if (st === 'wait') {
      // the phone is asking you: give it time
      clearTimeout(s.timer);
      s.timer = setTimeout(function () { s.fail(err('NotAllowedError', 'No answer on the phone')); }, 120000);
    } else if (st === 'deny') {
      s.fail(err('NotAllowedError', msg || 'Not allowed on the phone'));
    } else if (st === 'err' || st === 'closed') {
      if (!s.done) s.fail(err('NotReadableError', msg || 'The phone camera stopped'));
      else ended(sid);
    }
  }

  /** The phone side ended: its tracks end for the page too. */
  function ended(sid) {
    var s = sessions[sid];
    if (!s) return;
    var mine = [];
    live.forEach(function (t) { if (t.__sarukiSid === sid) mine.push(t); });
    mine.forEach(function (t) {
      live.delete(t);
      try { stop0.call(t); } catch (e) {}
      try { t.dispatchEvent(new Event('ended')); } catch (e) {}
    });
    close(sid);
    changed();
  }

  function close(sid) {
    var s = sessions[sid];
    if (!s) return;
    delete sessions[sid];
    clearTimeout(s.timer);
    try { s.pc.close(); } catch (e) {}
    send({ t: 'close', sid: sid });
  }

  function hangup() {
    live.forEach(function (t) {
      try { stop0.call(t); } catch (e) {}
      try { t.dispatchEvent(new Event('ended')); } catch (e) {}
    });
    live.clear();
    Object.keys(sessions).forEach(close);
    changed();
  }

  // ---------------------------------------------------------------- the page's view of devices
  function fake(kind, id, label) {
    var o = { deviceId: id, kind: kind, label: label, groupId: 'saruki-phone' };
    try { Object.setPrototypeOf(o, (window.InputDeviceInfo || window.MediaDeviceInfo).prototype); } catch (e) {}
    Object.defineProperty(o, 'deviceId', { value: id, enumerable: true });
    Object.defineProperty(o, 'kind', { value: kind, enumerable: true });
    Object.defineProperty(o, 'label', { value: label, enumerable: true });
    Object.defineProperty(o, 'groupId', { value: 'saruki-phone', enumerable: true });
    o.getCapabilities = function () { return {}; };
    o.toJSON = function () { return { deviceId: id, kind: kind, label: label, groupId: 'saruki-phone' }; };
    return o;
  }

  function knownAvail() {
    if (avail.known) return Promise.resolve();
    sayHello();
    return new Promise(function (res) { availWait.push(res); setTimeout(res, 400); });
  }

  if (enum0) {
    md.enumerateDevices = function () {
      return Promise.all([enum0(), knownAvail()]).then(function (r) {
        var out = Array.prototype.slice.call(r[0]);
        if (avail.phone) {
          out.push(fake('videoinput', PHONE_CAM, 'Phone camera (Wi-Fi)'));
          out.push(fake('audioinput', PHONE_MIC, 'Phone microphone (Wi-Fi)'));
        }
        return out;
      });
    };
  }
  md.getUserMedia = function (c) { return gum(c); };
  var legacy = function (c, ok, bad) { gum(c).then(ok, bad); };
  if (navigator.getUserMedia) navigator.getUserMedia = legacy;
  if (navigator.webkitGetUserMedia) navigator.webkitGetUserMedia = legacy;

  window.addEventListener('pagehide', function () { Object.keys(sessions).forEach(close); });
  if (top) sayHello();
})();
