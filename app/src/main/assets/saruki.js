(function () {
  if (window.__saruki) return;
  var N = window.SarukiNative;
  var top = (window === window.top);
  var flags = '';
  // The top page asks with its own address; frames inside it follow the page they sit in.
  try { flags = N ? (top && N.flagsFor ? N.flagsFor(location.hostname) : N.flags()) : ''; } catch (e) {}
  function has(f) { return flags.indexOf(f) >= 0; }

  // ---------- page width (desktop / mobile / page size) ----------
  var vpWidth = 0;
  try { vpWidth = top && N ? N.vw(location.host) : 0; } catch (e) {}
  function fixViewport() {
    if (!vpWidth) return;
    var c = 'width=' + vpWidth;
    var metas = document.querySelectorAll('meta[name="viewport" i]');
    var mine = null;
    for (var i = 0; i < metas.length; i++) {
      if (metas[i].hasAttribute('data-saruki')) mine = metas[i];
      else if (metas[i].getAttribute('content') !== c) metas[i].setAttribute('content', c);
    }
    if (!mine) {
      mine = document.createElement('meta');
      mine.setAttribute('name', 'viewport');
      mine.setAttribute('data-saruki', '1');
      (document.head || document.documentElement).appendChild(mine);
    }
    if (mine.getAttribute('content') !== c) mine.setAttribute('content', c);
  }
  // At document start the page has no <html> element yet: wait for it before touching the DOM.
  function whenRoot(fn) {
    if (document.documentElement) { fn(); return; }
    try {
      new MutationObserver(function (ms, o) { if (document.documentElement) { o.disconnect(); fn(); } })
        .observe(document, {childList: true});
    } catch (e) { document.addEventListener('DOMContentLoaded', fn); }
  }
  if (top && vpWidth) whenRoot(function () {
    fixViewport();
    try {
      new MutationObserver(function (ms) {
        for (var i = 0; i < ms.length; i++) for (var j = 0; j < ms[i].addedNodes.length; j++) {
          var n = ms[i].addedNodes[j];
          if (n.nodeName === 'META' || n.nodeName === 'HEAD') { fixViewport(); return; }
        }
      }).observe(document.documentElement, {childList: true, subtree: true});
    } catch (e) {}
  });

  // ---------- cosmetic clean-up: cookie banners and leftover ad boxes ----------
  var css = '';
  if (has('c')) css += '#onetrust-consent-sdk,#onetrust-banner-sdk,.onetrust-pc-dark-filter,#didomi-host,#qc-cmp2-container,' +
    '#CybotCookiebotDialog,#CybotCookiebotDialogBodyUnderlay,#usercentrics-root,#usercentrics-cmp-ui,.fc-consent-root,#sp_message_container,' +
    '[id^="sp_message_container"],.truste_box_overlay,#truste-consent-track,#cmpbox,#cmpbox2,.cc-window,.cookie-consent-banner,' +
    '#gdpr-consent-tool-wrapper,#cookie-law-info-bar,.cky-consent-container,.cky-overlay{display:none!important}' +
    'html.sp-message-open,body.didomi-popup-open,body.modal-open-cmp,html.didomi-popup-open{overflow:auto!important;position:static!important}';
  if (has('a')) css += 'ins.adsbygoogle,.adsbygoogle,[id^="div-gpt-ad"],[id^="google_ads_iframe"],iframe[id^="google_ads"],' +
    '.trc_related_container,.OUTBRAIN,[id^="taboola-"],.taboola,.ad-slot,.ad-container,#player-ads,ytd-ad-slot-renderer,' +
    'ytd-promoted-sparkles-web-renderer,ytd-in-feed-ad-layout-renderer,.ytp-ad-overlay-container,ytd-banner-promo-renderer{display:none!important}';
  css += '.__saruki_hl{position:fixed;pointer-events:none;z-index:2147483647;border:3px solid #FF5CA8;border-radius:8px;' +
    'box-shadow:0 0 0 3px rgba(0,0,0,.45),0 0 18px rgba(255,92,168,.6);transition:all .12s ease-out}';
  function addCss() {
    if (!document.documentElement || document.getElementById('__saruki_css')) return;
    var s = document.createElement('style'); s.id = '__saruki_css'; s.textContent = css;
    (document.head || document.documentElement).appendChild(s);
  }
  whenRoot(addCss);
  document.addEventListener('DOMContentLoaded', addCss);

  // YouTube: jump over video ads and press "Skip" as soon as it appears. The ad is muted while it plays, and the
  // sound comes back when the real video starts (YouTube reuses the same <video> for the ad and the video). Sound the
  // viewer muted themselves is left alone. A 400 ms check is cheap; watching every YouTube page change costs more.
  if (has('y') && /(^|\.)youtube\.com$/.test(location.hostname)) {
    var adMuted = null;   // the <video> Saruki muted for an ad, if any
    setInterval(function () {
      var ad = document.querySelector('.ad-showing, .ad-interrupting');
      var v = document.querySelector('video');
      if (ad && v) {
        if (!v.muted) { v.muted = true; adMuted = v; }
        if (isFinite(v.duration) && v.duration > 0) v.currentTime = v.duration;
      } else if (adMuted) {
        try { adMuted.muted = false; } catch (e) {}
        adMuted = null;
      }
      var b = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, button[id^="skip-button"]');
      if (b) b.click();
    }, 400);
  }

  // ---------- video events ----------
  function report(v, playing) {
    try {
      var src = v.currentSrc || v.src || '';
      if (src.indexOf('blob:') === 0) src = '';
      N.media(playing ? 1 : 0, src, top ? 1 : 0);
    } catch (e) {}
  }
  ['play', 'playing'].forEach(function (ev) {
    document.addEventListener(ev, function (e) { if (e.target && e.target.tagName === 'VIDEO') report(e.target, true); }, true);
  });
  ['pause', 'ended', 'emptied'].forEach(function (ev) {
    document.addEventListener(ev, function (e) { if (e.target && e.target.tagName === 'VIDEO') report(e.target, false); }, true);
  });

  // ---------- pop-up & redirect shield: tell Saruki which real link each click landed on ----------
  // A link only counts when you can see it: a huge invisible link laid over a video player ("click anywhere")
  // is an ad trap, so it reports "" and the pop-up or redirect it fires gets stopped.
  function trap(a) {
    try {
      var r = a.getBoundingClientRect(), vw = innerWidth || 1, vh = innerHeight || 1;
      var s = getComputedStyle(a);
      if (parseFloat(s.opacity) < 0.05 || s.visibility === 'hidden') return true;
      var big = r.width * r.height > vw * vh * 0.45;
      var empty = (a.innerText || '').trim().length < 3 && !a.querySelector('img,picture,svg,video,canvas');
      return big && empty;
    } catch (e) { return false; }
  }
  function onClick(e) {
    try {
      var t = e.target, a = null;
      for (var n = t; n && n.nodeType === 1; n = n.parentElement || (n.getRootNode && n.getRootNode().host)) {
        if (n.tagName === 'A' && n.href) { a = n; break; }
      }
      var href = a && !/^javascript:/i.test(a.href) && !trap(a) ? a.href : '';
      if (N && N.clicked) N.clicked(href);
    } catch (x) {}
  }
  window.addEventListener('click', onClick, true);
  window.addEventListener('auxclick', onClick, true);

  if (!top) { window.__saruki = {}; return; }

  // ---------- geometry: native view pixels <-> CSS pixels ----------
  var VW = 1920;
  function scale() { var v = window.visualViewport; return (v ? v.width : innerWidth) / VW; }
  function toCss(x, y) {
    var v = window.visualViewport, s = scale();
    return [x * s + (v ? v.offsetLeft : 0), y * s + (v ? v.offsetTop : 0)];
  }
  function toPx(r) {
    var v = window.visualViewport, s = scale(), ox = v ? v.offsetLeft : 0, oy = v ? v.offsetTop : 0;
    return [(r.left - ox) / s, (r.top - oy) / s, r.width / s, r.height / s];
  }
  function at(x, y) {
    var p = toCss(x, y);
    var el = document.elementFromPoint(p[0], p[1]);
    // look inside open shadow roots
    while (el && el.shadowRoot) {
      var inner = el.shadowRoot.elementFromPoint(p[0], p[1]);
      if (!inner || inner === el) break;
      el = inner;
    }
    return el;
  }

  var CLICK = 'a[href],button,input:not([type=hidden]),select,textarea,label,summary,video,iframe,[role=button],[role=link],[role=tab],' +
    '[role=menuitem],[role=option],[role=checkbox],[role=radio],[role=switch],[onclick],[tabindex]:not([tabindex="-1"]),[contenteditable=""],[contenteditable=true]';
  function clickableOf(el) {
    for (var n = el, d = 0; n && n.nodeType === 1 && d < 8; n = n.parentElement || (n.getRootNode && n.getRootNode().host), d++) {
      try { if (n.matches(CLICK)) return n; } catch (e) {}
      try { if (getComputedStyle(n).cursor === 'pointer') return n; } catch (e) {}
    }
    return null;
  }
  function editable(el) {
    if (!el) return false;
    if (el.isContentEditable) return true;
    if (el.tagName === 'TEXTAREA') return true;
    if (el.tagName === 'INPUT') return !/^(button|submit|reset|checkbox|radio|range|color|file|image|hidden)$/i.test(el.type);
    return false;
  }
  function visible(r) { return r.width > 2 && r.height > 2 && r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth; }

  // ---------- highlight ring (used by jump mode) ----------
  var hl = null, hlTimer = 0;
  function highlight(el, keep) {
    if (!hl) { hl = document.createElement('div'); hl.className = '__saruki_hl'; }
    if (!hl.isConnected) document.documentElement.appendChild(hl);
    var r = el.getBoundingClientRect(), pad = 3;
    hl.style.left = (r.left - pad) + 'px'; hl.style.top = (r.top - pad) + 'px';
    hl.style.width = (r.width + pad * 2) + 'px'; hl.style.height = (r.height + pad * 2) + 'px';
    hl.style.display = 'block';
    clearTimeout(hlTimer);
    hlTimer = setTimeout(function () { if (hl) hl.style.display = 'none'; }, keep ? 4000 : 900);
  }

  // ---------- scrolling the box under the cursor ----------
  var target = null;
  function canScroll(el, dx, dy) {
    var s = getComputedStyle(el);
    if (dy) {
      if (!/(auto|scroll|overlay)/.test(s.overflowY) || el.scrollHeight <= el.clientHeight + 1) return false;
      return dy > 0 ? el.scrollTop + el.clientHeight < el.scrollHeight - 1 : el.scrollTop > 0;
    }
    if (!/(auto|scroll|overlay)/.test(s.overflowX) || el.scrollWidth <= el.clientWidth + 1) return false;
    return dx > 0 ? el.scrollLeft + el.clientWidth < el.scrollWidth - 1 : el.scrollLeft > 0;
  }
  function docCan(dx, dy) {
    var se = document.scrollingElement || document.documentElement;
    if (dy) return dy > 0 ? se.scrollTop + innerHeight < se.scrollHeight - 1 : se.scrollTop > 0;
    return dx > 0 ? se.scrollLeft + innerWidth < se.scrollWidth - 1 : se.scrollLeft > 0;
  }
  function findTarget(x, y, dx, dy) {
    var el = at(x, y), root = document.scrollingElement || document.documentElement;
    for (var n = el; n && n.nodeType === 1 && n !== root && n !== document.documentElement;
         n = n.parentElement || (n.getRootNode && n.getRootNode().host)) {
      // <body> counts too when the site made it the scrolling box instead of the page
      try { if (canScroll(n, dx, dy)) return n; } catch (e) {}
    }
    return null;
  }
  // Smooth scroll of a box inside the page; quick repeats add up instead of cutting each other short.
  function smoothBy(el, dx, dy) {
    var now = Date.now(), win = !el;
    var cx = win ? scrollX : el.scrollLeft, cy = win ? scrollY : el.scrollTop;
    var g = el || window;
    if (!g.__sgT || now - g.__sgT > 450) { g.__sgX = cx; g.__sgY = cy; }
    g.__sgT = now;
    g.__sgX += dx; g.__sgY += dy;
    (el || window).scrollTo({left: g.__sgX, top: g.__sgY, behavior: 'smooth'});
  }

  // ---------- spatial "jump" navigation ----------
  function candidates() {
    var all = document.querySelectorAll(CLICK), out = [];
    for (var i = 0; i < all.length && out.length < 1500; i++) {
      var el = all[i], r = el.getBoundingClientRect();
      if (!visible(r)) continue;
      if (r.width > innerWidth * 0.95 && r.height > innerHeight * 0.6) continue;   // page-sized wrappers
      var s = getComputedStyle(el);
      if (s.visibility === 'hidden' || s.opacity === '0') continue;
      out.push([el, r]);
    }
    return out;
  }
  function jump(dir, x, y) {
    var p = toCss(x, y), cx = p[0], cy = p[1], best = null, bestCost = 1e9, list = candidates();
    for (var i = 0; i < list.length; i++) {
      var r = list[i][1], ex = r.left + r.width / 2, ey = r.top + r.height / 2;
      if (r.left <= cx && cx <= r.right && r.top <= cy && cy <= r.bottom) continue;   // the one under the cursor
      var along, across;
      if (dir === 'r') { along = r.left - cx; across = Math.max(0, Math.max(r.top - cy, cy - r.bottom)); }
      else if (dir === 'l') { along = cx - r.right; across = Math.max(0, Math.max(r.top - cy, cy - r.bottom)); }
      else if (dir === 'd') { along = r.top - cy; across = Math.max(0, Math.max(r.left - cx, cx - r.right)); }
      else { along = cy - r.bottom; across = Math.max(0, Math.max(r.left - cx, cx - r.right)); }
      if (along < -2) continue;
      var cost = Math.max(along, 0) + across * 2.2 + (across > 0 ? 40 : 0);
      if (cost < bestCost) { bestCost = cost; best = list[i]; }
    }
    // v1.8.2: pressing UP with no link above, at the very top of the page, means "go to the top bar" (the app reads this)
    if (!best) return dir === 'u' && pageAtTop() ? 'bar' : null;
    return linkPoint(best, cx);
  }
  function pageAtTop() {
    var se = document.scrollingElement || document.documentElement;
    return (window.scrollY || (se ? se.scrollTop : 0) || 0) <= 0;
  }
  function linkPoint(best, cx) {
    highlight(best[0], true);
    var r2 = best[1];
    var tx = r2.width > 160 ? Math.min(Math.max(cx, r2.left + 12), r2.right - 12) : r2.left + r2.width / 2;
    var ty = r2.top + Math.min(r2.height / 2, 24);
    var v = window.visualViewport, s = scale();
    return [(tx - (v ? v.offsetLeft : 0)) / s, (ty - (v ? v.offsetTop : 0)) / s];
  }
  // v1.8.2: the first link on the page (topmost, then leftmost): where DOWN from the top bar lands
  function firstLink(x, y) {
    var p = toCss(x, y), list = candidates(), best = null;
    for (var i = 0; i < list.length; i++) {
      var r = list[i][1];
      if (r.bottom <= 0) continue;
      if (!best || r.top < best[1].top - 2 || (Math.abs(r.top - best[1].top) <= 2 && r.left < best[1].left)) best = list[i];
    }
    return best ? linkPoint(best, p[0]) : null;
  }

  function mainVideo() {
    var vs = document.querySelectorAll('video'), best = null, area = 0;
    for (var i = 0; i < vs.length; i++) {
      if (!vs[i].paused) return vs[i];
      var r = vs[i].getBoundingClientRect(), a = r.width * r.height;
      if (a > area) { area = a; best = vs[i]; }
    }
    return best;
  }

  var fitted = null;
  window.__saruki = {
    setVW: function (w) { VW = w || 1920; },
    hit: function (x, y) {
      var el = at(x, y);
      if (!el) return 'n';
      if (editable(el)) return 't';
      return clickableOf(el) ? 'c' : 'n';
    },
    near: function (x, y, rad) {
      var el = at(x, y);
      if (el && clickableOf(el)) return null;
      var p = toCss(x, y), s = scale(), r = rad * s, best = null, bd = 1e9, list = candidates();
      for (var i = 0; i < list.length; i++) {
        var b = list[i][1];
        var qx = Math.min(Math.max(p[0], b.left + 3), b.right - 3), qy = Math.min(Math.max(p[1], b.top + 3), b.bottom - 3);
        var d = Math.hypot(qx - p[0], qy - p[1]);
        if (d < bd && d <= r) { bd = d; best = [qx, qy]; }
      }
      if (!best) return null;
      var v = window.visualViewport;
      return [(best[0] - (v ? v.offsetLeft : 0)) / s, (best[1] - (v ? v.offsetTop : 0)) / s];
    },
    // 1 = an inner box will scroll, 0 = the page will scroll, -1 = nothing can scroll that way
    target: function (x, y, dx, dy) {
      target = findTarget(x, y, dx, dy);
      if (target) return 1;
      return docCan(dx, dy) ? 0 : -1;
    },
    sbs: function (dx, dy) {
      var s = scale();
      smoothBy(target && target.isConnected ? target : null, dx * s, dy * s);
    },
    // Removes the invisible layer under the pointer that caught a click and fired an ad (never the video itself).
    unjack: function (x, y) {
      var el = at(x, y), area = innerWidth * innerHeight;
      for (var n = el, d = 0; n && n.nodeType === 1 && n !== document.body && n !== document.documentElement && d < 6; n = n.parentElement, d++) {
        if (/^(VIDEO|IFRAME|CANVAS|EMBED|OBJECT|IMG|INPUT|BUTTON|TEXTAREA|SELECT)$/.test(n.tagName)) return 0;
        var r = n.getBoundingClientRect();
        if (r.width * r.height < area * 0.12) continue;
        var s = getComputedStyle(n);
        if (s.position !== 'absolute' && s.position !== 'fixed') continue;
        if ((n.innerText || '').trim().length > 2) return 0;
        if (n.querySelector('video,iframe,canvas,embed,object,img,picture,svg,input,button')) return 0;
        var bg = s.backgroundColor, clear = bg === 'transparent' || /rgba\([^)]*,\s*0(\.0+)?\)$/.test(bg);
        if (parseFloat(s.opacity) < 0.15 || (clear && s.backgroundImage === 'none')) { n.remove(); return 1; }
      }
      return 0;
    },
    sb: function (dx, dy) {
      var s = scale();
      if (target && target.isConnected) target.scrollBy(dx * s, dy * s); else window.scrollBy(dx * s, dy * s);
    },
    page: function (x, y, dir) {
      var t = findTarget(x, y, 0, dir) , h = t ? t.clientHeight : innerHeight;
      (t || window).scrollBy({top: dir * h * 0.8, behavior: 'smooth'});
    },
    jump: jump,
    firstLink: firstLink,
    clearHl: function () { if (hl) hl.style.display = 'none'; return 1; },
    info: function (x, y) {
      var el = at(x, y), o = {};
      for (var n = el; n && n.nodeType === 1; n = n.parentElement) {
        if (!o.link && n.tagName === 'A' && n.href) { o.link = n.href; o.text = (n.innerText || '').trim().slice(0, 80); }
        if (!o.img && n.tagName === 'IMG' && n.currentSrc) o.img = n.currentSrc;
        if (!o.video && n.tagName === 'VIDEO') { var s = n.currentSrc || n.src || ''; o.video = s.indexOf('blob:') === 0 ? 'blob' : s; }
      }
      if (editable(el)) o.edit = 1;
      return JSON.stringify(o);
    },
    focusAt: function (x, y) {
      var el = at(x, y);
      if (editable(el)) { el.focus(); return 1; }
      return editable(document.activeElement) ? 1 : 0;
    },
    type: function (t) {
      var el = document.activeElement;
      if (!editable(el)) return 0;
      if (!document.execCommand('insertText', false, t)) {
        el.value = (el.value || '') + t;
        el.dispatchEvent(new Event('input', {bubbles: true}));
      }
      return 1;
    },
    backspace: function () { document.execCommand('delete'); },
    zap: function (x, y) {
      var el = at(x, y), pick = null;
      for (var n = el; n && n !== document.body && n !== document.documentElement; n = n.parentElement) {
        var p = getComputedStyle(n).position;
        if (p === 'fixed' || p === 'sticky') pick = n;
      }
      if (!pick) pick = el && el !== document.body && el !== document.documentElement ? el : null;
      if (!pick) return 0;
      pick.remove();
      [document.documentElement, document.body].forEach(function (e) {
        if (e && getComputedStyle(e).overflow === 'hidden') e.style.setProperty('overflow', 'auto', 'important');
      });
      return 1;
    },
    toggle: function () {
      var v = mainVideo();
      if (!v) return 0;
      if (v.paused) v.play(); else v.pause();
      return 1;
    },
    seek: function (s) {
      var v = mainVideo();
      if (!v || !isFinite(v.duration)) return 0;
      v.currentTime = Math.max(0, Math.min(v.duration - 1, v.currentTime + s));
      return Math.round(v.currentTime) + '/' + Math.round(v.duration);
    },
    hasVideo: function () { return mainVideo() ? 1 : 0; },
    fit: function () {
      if (fitted) {
        fitted.setAttribute('style', fitted.getAttribute('data-saruki-style') || '');
        fitted = null;
        return 0;
      }
      var v = mainVideo();
      if (!v) return -1;
      v.setAttribute('data-saruki-style', v.getAttribute('style') || '');
      v.setAttribute('style', 'position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;' +
        'max-width:none!important;max-height:none!important;z-index:2147483646!important;background:#000!important;object-fit:contain!important;transform:none!important');
      fitted = v;
      if (v.paused) v.play();
      return 1;
    },
    icon: function () {
      var best = null, size = 0, ls = document.querySelectorAll('link[rel~="icon" i],link[rel="apple-touch-icon" i],link[rel="apple-touch-icon-precomposed" i]');
      for (var i = 0; i < ls.length; i++) {
        var sz = parseInt((ls[i].getAttribute('sizes') || '0').split('x')[0], 10) || (/apple/i.test(ls[i].rel) ? 180 : 16);
        if (sz > size && ls[i].href && !/\.svg/i.test(ls[i].href)) { size = sz; best = ls[i].href; }
      }
      return best || '';
    }
  };
})();
