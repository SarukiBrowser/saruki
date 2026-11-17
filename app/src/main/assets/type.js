// Saruki v1.7: typing from Saruki Remote (the phone app). Runs in every page and frame before the page's own scripts.
// When a text box on the page gets focus, Saruki is told (which box, its text, cursor, kind); the phone mirrors it, and
// every change on the phone comes back here as one small edit that is played into the box like real typing, so
// sites see ordinary input events (search suggestions, React and Vue forms, sign-in boxes).
(function () {
  'use strict';
  if (window.__sarukiType) return;
  window.__sarukiType = true;
  var bus = window.SarukiType;
  if (!bus) return;

  function rid(n) {
    var c = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789', r = new Uint32Array(n), s = '';
    crypto.getRandomValues(r);
    for (var i = 0; i < n; i++) s += c[r[i] % c.length];
    return s;
  }
  var fid = rid(10);          // this frame
  var n = 0;                  // field counter in this frame
  var cur = null, curN = 0;   // the box with focus, and its number
  var applying = false, stateTimer = 0, closeTimer = 0;
  var MAX = 4000;

  function send(o) { o.f = fid; try { bus.postMessage(JSON.stringify(o)); } catch (e) {} }

  var NOSEL = /^(email|number|date|datetime-local|month|time|week|color|range)$/i;
  function editable(el) {
    if (!el || el.nodeType !== 1) return false;
    if (el.isContentEditable) return true;
    if (el.tagName === 'TEXTAREA') return !el.disabled && !el.readOnly;
    if (el.tagName === 'INPUT') {
      if (el.disabled || el.readOnly) return false;
      return !/^(button|submit|reset|checkbox|radio|range|color|file|image|hidden|date|datetime-local|month|time|week)$/i.test(el.type);
    }
    return false;
  }
  function isField(el) { return el.tagName === 'INPUT' || el.tagName === 'TEXTAREA'; }
  // the editing host of a contenteditable element (the outermost editable ancestor)
  function host(el) {
    if (isField(el)) return el;
    var h = el;
    while (h.parentElement && h.parentElement.isContentEditable) h = h.parentElement;
    return h;
  }

  // ---------- text and cursor of a box ----------
  function textOf(el) { return isField(el) ? String(el.value || '') : String(el.textContent || ''); }
  function selOf(el) {
    if (isField(el)) {
      try {
        if (el.selectionStart != null) return [el.selectionStart, el.selectionEnd];
      } catch (e) {}
      var l = textOf(el).length;
      return [l, l];
    }
    var s = getSelection();
    if (!s || !s.rangeCount || !el.contains(s.anchorNode)) { var l2 = textOf(el).length; return [l2, l2]; }
    var r = s.getRangeAt(0);
    return [offsetIn(el, r.startContainer, r.startOffset), offsetIn(el, r.endContainer, r.endOffset)];
  }
  function offsetIn(root, node, off) {
    var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT), t, pos = 0;
    if (node.nodeType !== 3) {
      // a position between children: count the text before that child
      var before = node.childNodes[off] || null;
      while ((t = w.nextNode())) {
        if (before && (before === t || (before.compareDocumentPosition(t) & Node.DOCUMENT_POSITION_FOLLOWING))) break;
        if (!before && !node.contains(t) && (node.compareDocumentPosition(t) & Node.DOCUMENT_POSITION_FOLLOWING)) break;
        pos += t.data.length;
      }
      return pos;
    }
    while ((t = w.nextNode())) {
      if (t === node) return pos + off;
      pos += t.data.length;
    }
    return pos;
  }
  function pointAt(root, pos) {
    var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT), t, last = null;
    while ((t = w.nextNode())) {
      if (pos <= t.data.length) return [t, pos];
      pos -= t.data.length;
      last = t;
    }
    if (last) return [last, last.data.length];
    return [root, root.childNodes.length];
  }
  function selectIn(el, s, e) {
    if (isField(el)) {
      try { el.setSelectionRange(s, e); return true; } catch (x) { return false; }
    }
    var a = pointAt(el, s), b = pointAt(el, e), r = document.createRange();
    try { r.setStart(a[0], a[1]); r.setEnd(b[0], b[1]); } catch (x) { return false; }
    var sel = getSelection();
    sel.removeAllRanges();
    sel.addRange(r);
    return true;
  }

  // ---------- what kind of box ----------
  function flags(el) {
    var f = 0, t = (el.type || '').toLowerCase(), im = (el.getAttribute('inputmode') || '').toLowerCase();
    if (t === 'password') f |= 1;
    if (el.tagName === 'TEXTAREA' || (!isField(el))) f |= 2;
    if (t === 'number' || t === 'tel' || im === 'numeric' || im === 'decimal' || im === 'tel') f |= 4;
    if (t === 'email' || im === 'email') f |= 8;
    if (t === 'url' || im === 'url') f |= 16;
    return f;
  }
  function action(el) {
    var k = (el.getAttribute('enterkeyhint') || '').toLowerCase();
    var map = { search: 'Search', go: 'Go', send: 'Send', next: 'Next', done: 'Done', enter: 'Enter', previous: 'Enter' };
    if (map[k]) return map[k];
    if (!isField(el) || el.tagName === 'TEXTAREA') return '';
    if ((el.type || '').toLowerCase() === 'search' || el.closest('form[role=search],[role=search]') ||
        /search|query|^q$/i.test((el.name || '') + ' ' + (el.id || '') + ' ' + (el.getAttribute('aria-label') || ''))) return 'Search';
    return el.form ? 'Go' : 'Enter';
  }
  function hint(el) {
    var h = el.getAttribute('placeholder') || el.getAttribute('aria-label') || el.getAttribute('title') || '';
    if (!h && el.id) { try { var l = document.querySelector('label[for="' + CSS.escape(el.id) + '"]'); if (l) h = l.textContent; } catch (e) {} }
    if (!h && el.labels && el.labels[0]) h = el.labels[0].textContent;
    h = String(h || '').replace(/\s+/g, ' ').trim();
    return h.slice(0, 60);
  }

  // long boxes: the phone mirrors the text around the cursor
  function windowOf(el) {
    var t = textOf(el), s = selOf(el), off = 0;
    if (t.length > MAX) {
      off = Math.max(0, Math.min(s[0] - MAX / 2, t.length - MAX));
      t = t.slice(off, off + MAX);
    }
    return { text: t, s: Math.max(0, s[0] - off), e: Math.max(0, s[1] - off), off: off };
  }

  function announce(why) {
    var el = document.activeElement;
    if (!editable(el)) return false;
    el = host(el);
    clearTimeout(closeTimer);
    if (el !== cur) {
      if (cur) send({ t: 'close', n: curN });   // moved straight from one box to another
      cur = el;
      curN = ++n;
    }
    var w = windowOf(el);
    cur.__sarukiOff = w.off;
    send({ t: 'open', n: curN, why: why || '', text: w.text, s: w.s, e: w.e, fl: flags(el), a: action(el), h: hint(el) });
    return true;
  }

  document.addEventListener('focusin', function (ev) {
    var el = ev.target;
    if (!editable(el)) return;
    // a box that only got focus because the page loaded can wait: Saruki pops the phone only after a click
    announce('focus');
  }, true);

  document.addEventListener('focusout', function (ev) {
    if (!cur) return;
    var was = cur, wasN = curN;
    clearTimeout(closeTimer);
    closeTimer = setTimeout(function () {
      var a = document.activeElement;
      if (a && editable(a) && host(a) === was) return;    // focus came back (the site re-focused it)
      if (cur === was) cur = null;
      send({ t: 'close', n: wasN });
    }, 250);
  }, true);

  // changes made by the page or the TV keyboard (not by the phone): tell the phone, a little later
  function changed() {
    if (applying || !cur) return;
    clearTimeout(stateTimer);
    stateTimer = setTimeout(function () {
      if (!cur || !cur.isConnected) return;
      var w = windowOf(cur);
      cur.__sarukiOff = w.off;
      send({ t: 'state', n: curN, text: w.text, s: w.s, e: w.e });
    }, 80);
  }
  document.addEventListener('input', function (ev) { if (cur && (ev.target === cur || cur.contains(ev.target))) changed(); }, true);
  document.addEventListener('change', function (ev) { if (cur && ev.target === cur) changed(); }, true);

  // ---------- an edit from the phone ----------
  var nativeValue = {};
  function setValue(el, v) {
    var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var d = Object.getOwnPropertyDescriptor(proto, 'value');
    if (d && d.set) d.set.call(el, v); else el.value = v;
  }
  function insert(el, text) {
    if (!text) return document.execCommand('delete');
    if (isField(el)) return document.execCommand('insertText', false, text);
    // rich editors: line breaks become real breaks
    var parts = text.split('\n'), ok = true;
    for (var i = 0; i < parts.length; i++) {
      if (i > 0) ok = document.execCommand('insertLineBreak') && ok;
      if (parts[i]) ok = document.execCommand('insertText', false, parts[i]) && ok;
    }
    return ok;
  }
  function edit(m) {
    var el = cur;
    if (!el || m.n !== curN || !el.isConnected) return;
    applying = true;
    try {
      if (document.activeElement !== el && !el.contains(document.activeElement)) el.focus({ preventScroll: true });
      var off = el.__sarukiOff || 0;
      var full = textOf(el);
      var start = Math.max(0, Math.min(full.length, off + m.s));
      var del = Math.max(0, Math.min(full.length - start, m.d));
      var cursor = off + m.c;
      var x = String(m.x || '');
      if (del > 0 || x) {
        var done = false;
        if (selectIn(el, start, start + del)) {
          var before = textOf(el);
          if (del > 0 && !x) done = document.execCommand('delete');
          else done = insert(el, x);
          // some boxes ignore execCommand: check it did the job
          var want = before.slice(0, start) + x + before.slice(start + del);
          if (done && textOf(el) !== want && isField(el)) done = false;
        }
        if (!done && isField(el)) {
          setValue(el, full.slice(0, start) + x + full.slice(start + del));
          try { el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: x ? 'insertText' : 'deleteContentBackward', data: x || null })); }
          catch (e) { el.dispatchEvent(new Event('input', { bubbles: true })); }
        }
      }
      var len = textOf(el).length;
      cursor = Math.max(0, Math.min(len, cursor));
      selectIn(el, cursor, cursor);
    } catch (e) {
    } finally {
      applying = false;
    }
  }

  // Enter / Search / Go
  function enter(m) {
    var el = cur;
    if (!el || m.n !== curN) return;
    var opts = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
    var go = el.dispatchEvent(new KeyboardEvent('keydown', opts));
    el.dispatchEvent(new KeyboardEvent('keypress', opts));
    el.dispatchEvent(new KeyboardEvent('keyup', opts));
    if (!go) return;
    if (el.tagName === 'INPUT' && el.form) {
      try { if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit(); } catch (e) {}
    } else if (!isField(el)) {
      document.execCommand('insertParagraph');
    } else if (el.tagName === 'TEXTAREA') {
      applying = true;
      try { document.execCommand('insertText', false, '\n'); } finally { applying = false; }
      changed();
    }
  }

  bus.addEventListener('message', function (ev) {
    var m;
    try { m = JSON.parse(ev.data); } catch (e) { return; }
    if (m.f !== fid) return;
    switch (m.t) {
      case 'edit': edit(m); break;
      case 'enter': enter(m); break;
      case 'ask': announce('ask'); break;
    }
  });

  // Saruki asks the frame that has focus to announce its box ("Type" on the phone)
  window.__sarukiTypeNow = function () { return announce('ask') ? 1 : 0; };
  send({ t: 'hi' });
})();
