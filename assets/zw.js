/*
 * Injected at document-start into https://zwlib.ruc.edu.cn/jsq-v/ and
 * https://cas.ruc.edu.cn/cas/login by MainActivity.
 *
 * Holds no secrets: credentials are pushed in by native through
 * evaluateJavascript only when the page actually needs them. The <script> node
 * removes itself once executed so page code cannot read this source back.
 */
(function () {
  'use strict';
  var ME = document.currentScript;
  var W = window;
  if (W.__ZW_READY) { if (ME && ME.parentNode) { ME.parentNode.removeChild(ME); } return; }
  W.__ZW_READY = true;

  var HOST = location.hostname || '';
  var IS_CAS = /(^|\.)cas\.ruc\.edu\.cn$/.test(HOST);
  // The site serves two different builds: the PC one (jsq-pc) prefixes sessionStorage
  // with "jsq_p", the mobile one (leo-jsq-move) with "jsq_m". Detect, never hardcode.
  var CANDIDATES = ['jsq_p-', 'jsq_m-'];

  function detectPrefix() {
    try {
      var i, k;
      for (i = 0; i < CANDIDATES.length; i++) {
        if (sessionStorage.getItem(CANDIDATES[i] + 'token')) { return CANDIDATES[i]; }
      }
      for (i = 0; i < sessionStorage.length; i++) {
        k = sessionStorage.key(i);
        if (k) {
          var m = k.match(/^(jsq_[a-zA-Z0-9]+-)/);
          if (m) { return m[1]; }
        }
      }
    } catch (e) {}
    return 'jsq_p-';
  }

  var P = detectPrefix();
  var NATIVE = W.ZWNative || null;

  function call(name, arg) {
    try { if (NATIVE && typeof NATIVE[name] === 'function') { return NATIVE[name](arg); } } catch (e) {}
    return null;
  }
  function send(o) { call('onEvent', JSON.stringify(o)); }

  /* =================================================================== */
  /* shared DOM helpers                                                   */
  /* =================================================================== */

  function live(el) { return !!(el && el.getClientRects && el.getClientRects().length > 0); }

  function setVal(el, v) {
    if (!el) { return; }
    try {
      var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      var d = Object.getOwnPropertyDescriptor(proto, 'value');
      if (d && d.set) { d.set.call(el, v); } else { el.value = v; }
    } catch (e) { el.value = v; }
    try {
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    } catch (e) {}
  }

  function allLive(sel) {
    var out = [], list = document.querySelectorAll(sel), i;
    for (i = 0; i < list.length; i++) { if (live(list[i])) { out.push(list[i]); } }
    return out;
  }

  try { if (ME && ME.parentNode) { ME.parentNode.removeChild(ME); } } catch (e) {}

  /* =================================================================== */
  /* CAS — cas.ruc.edu.cn/cas/login                                       */
  /* =================================================================== */
  if (IS_CAS) {
    var AUTO = false;
    try { AUTO = !!JSON.parse(call('getState') || '{}').auto; } catch (e) {}
    var casCreds = null;

    // 「7天内自动登录」 is the single highest-value toggle on this page: with the
    // CASTGC cookie alive every later cold start re-authenticates silently.
    function ensureRememberMe() {
      if (!AUTO) { return; }
      var boxes = document.querySelectorAll('input[name=rememberMe]'), i;
      for (i = 0; i < boxes.length; i++) {
        if (!boxes[i].checked) {
          boxes[i].checked = true;
          try { boxes[i].dispatchEvent(new Event('change', { bubbles: true })); } catch (e) {}
          boxes[i].checked = true;
        }
      }
    }

    function paint() {
      if (!casCreds) { return false; }
      var i, hit = false;
      var uns = allLive('#username, input[name=username]');
      for (i = 0; i < uns.length; i++) {
        if (!uns[i].value) { setVal(uns[i], casCreds.u); hit = true; }
      }
      var pws = allLive('#passwordShow');
      for (i = 0; i < pws.length; i++) {
        if (!pws[i].value) { setVal(pws[i], casCreds.p); hit = true; }
      }
      return hit;
    }

    W.__ZW = {
      fillCas: function (u, p) {
        try {
          casCreds = { u: u || '', p: p || '' };
          paint();
          ensureRememberMe();
          var cap = allLive('#authcode');
          if (cap.length) { try { cap[0].focus(); } catch (e) {} }
          var ok = allLive('#passwordShow').length > 0;
          send({ t: ok ? 'casFilled' : 'casNoForm' });
          return ok;
        } catch (e) { send({ t: 'error', m: 'cas:' + e }); return false; }
      },
      state: function () { return 'cas'; },
      route: function () { return 'cas'; }
    };

    var routeSent = false;
    function casTick() {
      if (!routeSent) { routeSent = true; send({ t: 'route', v: 'cas' }); }
      ensureRememberMe();
      paint();
      if (AUTO && !casCreds) { send({ t: 'casNeedCreds' }); }
    }

    // force the checkbox again at the last moment: the POST only carries it when
    // it is checked at the time the form is serialised
    function beforeSubmit() {
      ensureRememberMe();
      try {
        var u = (allLive('#username, input[name=username]')[0] || {}).value;
        var pw = (allLive('#passwordShow')[0] || {}).value;
        if (u && pw) { send({ t: 'creds', u: u, p: pw }); }
      } catch (e) {}
    }

    document.addEventListener('click', function (ev) {
      try {
        var n = ev.target, b = null;
        while (n && n !== document.body) {
          if (n.tagName === 'BUTTON' || (n.tagName === 'INPUT' && /submit/i.test(n.type || ''))) { b = n; break; }
          n = n.parentNode;
        }
        if (b) { beforeSubmit(); }
      } catch (e) {}
    }, true);

    document.addEventListener('submit', beforeSubmit, true);
    document.addEventListener('keydown', function (ev) {
      if (ev.key === 'Enter' || ev.keyCode === 13) { beforeSubmit(); }
    }, true);

    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', function () { setTimeout(casTick, 50); });
    } else {
      setTimeout(casTick, 50);
    }
    var n2 = 0;
    var timer = setInterval(function () {
      casTick();
      if (++n2 > 40) { clearInterval(timer); }
    }, 500);

    return;
  }

  /* =================================================================== */
  /* Seat booking SPA — zwlib.ruc.edu.cn/jsq-v                            */
  /* =================================================================== */

  var tried = false;            // one auto-submit per document
  var lastTok = null;
  var lastRoute = null;
  var ready = false;
  var attempts = 0;
  var submittedAt = 0;

  function ssGet(k) { try { return sessionStorage.getItem(P + k); } catch (e) { return null; } }
  function ssSet(k, v) { try { sessionStorage.setItem(P + k, v); } catch (e) {} }

  function state() {
    try { return JSON.parse(call('getState') || '{}'); } catch (e) { return {}; }
  }
  var ST = state();
  var AUTO = !!ST.auto;

  /* ---- 1. restore the session so the router guard never hits the CAS redirect ---- */
  try {
    if (!ssGet('token')) {
      var raw = call('getSeed');
      if (raw) {
        var seed = JSON.parse(raw), k, ci;
        // write under every candidate prefix: at document-start we cannot know
        // which build the server decided to give this WebView
        var prefixes = CANDIDATES.concat([P]);
        for (ci = 0; ci < prefixes.length; ci++) {
          var pref = prefixes[ci];
          for (k in seed) {
            if (Object.prototype.hasOwnProperty.call(seed, k) && seed[k] != null) {
              try { sessionStorage.setItem(pref + k, seed[k]); } catch (e2) {}
            }
          }
        }
        P = detectPrefix();
        if (ssGet('token')) { send({ t: 'seeded', p: P }); }
      }
    }
  } catch (e) { send({ t: 'error', m: 'seed:' + e }); }

  /* ---- 2. login form helpers ---- */
  function fields() {
    var all = document.querySelectorAll('input'), pw = null, un = null, i;
    for (i = 0; i < all.length; i++) {
      if (all[i].type === 'password' && live(all[i])) { pw = all[i]; break; }
    }
    if (!pw) { return null; }
    for (i = 0; i < all.length; i++) {
      var e = all[i];
      if (e === pw) { break; }
      var ty = (e.type || 'text').toLowerCase();
      if (live(e) && (ty === 'text' || ty === 'tel' || ty === 'number') && !e.readOnly) { un = e; }
    }
    return { u: un, p: pw };
  }

  function captchaShown() {
    var i, imgs = document.querySelectorAll('img');
    for (i = 0; i < imgs.length; i++) {
      if (/aptcha/i.test(imgs[i].src || '') && live(imgs[i])) { return true; }
    }
    var ins = document.querySelectorAll('input');
    for (i = 0; i < ins.length; i++) {
      if (/验证码|captcha/i.test(ins[i].placeholder || '') && live(ins[i])) { return true; }
    }
    return false;
  }

  function submitButton() {
    var bs = document.querySelectorAll('button'), i;
    for (i = 0; i < bs.length; i++) {
      if (/^(立即登录|登录|Login)$/i.test((bs[i].innerText || '').replace(/\s+/g, '')) && live(bs[i])) { return bs[i]; }
    }
    return null;
  }

  function route() {
    var h = location.hash || '';
    if (h.charAt(0) === '#') { h = h.slice(1); }
    return h;
  }

  function snapshot() {
    var o = {}, i;
    try {
      for (i = 0; i < sessionStorage.length; i++) {
        var key = sessionStorage.key(i);
        if (!key || key.indexOf(P) !== 0) { continue; }
        var short = key.slice(P.length);
        if (short === 'queryParam' || short === 'showNotice') { continue; }
        var v = sessionStorage.getItem(key);
        if (v && v.length < 200000) { o[short] = v; }
      }
    } catch (e) {}
    return o;
  }

  /* ---- 3. dialogs the site throws on a stale token ---- */
  function dialogs() {
    if (!AUTO) { return; }
    var boxes = document.querySelectorAll('.el-message-box, .el-dialog'), i, j;
    for (i = 0; i < boxes.length; i++) {
      var box = boxes[i];
      if (!live(box)) { continue; }
      var txt = box.innerText || '';
      if (!/登录失败|重新触发统一认证|登录认证已过期|关闭浏览器之后重新登录/.test(txt)) { continue; }
      var btns = box.querySelectorAll('button');
      var wantOk = !(ST.hasCreds && route() === '/login');
      for (j = 0; j < btns.length; j++) {
        var t = (btns[j].innerText || '').replace(/\s+/g, '');
        if (wantOk && /^(确定|OK|确认)$/.test(t)) { btns[j].click(); send({ t: 'dialog', v: 'ok' }); break; }
        if (!wantOk && /^(取消|Cancel)$/.test(t)) { btns[j].click(); send({ t: 'dialog', v: 'cancel' }); break; }
      }
    }
  }

  /* ---- 4. api used by native ---- */
  W.__ZW = {
    autofill: function (u, p) {
      try {
        if (tried) { return false; }
        var f = fields();
        if (!f || !f.u || !f.p) { send({ t: 'noForm' }); return false; }
        setVal(f.u, u);
        setVal(f.p, p);
        if (captchaShown()) { tried = true; send({ t: 'needCaptcha' }); return false; }
        var b = submitButton();
        if (!b) { send({ t: 'noButton' }); return false; }
        tried = true;
        attempts++;
        submittedAt = Date.now();
        send({ t: 'submitting' });
        setTimeout(function () { try { b.click(); } catch (e) { send({ t: 'error', m: 'click:' + e }); } }, 150);
        return true;
      } catch (e) { send({ t: 'error', m: 'fill:' + e }); return false; }
    },
    nav: function (url) { try { location.href = url; } catch (e) {} },
    route: function () { return route(); },
    state: function () { return ssGet('token') ? 'in' : 'out'; }
  };

  /* ---- 5. learn credentials typed by hand ---- */
  document.addEventListener('click', function (ev) {
    try {
      var n = ev.target, b = null;
      while (n && n !== document.body) {
        if (n.tagName === 'BUTTON') { b = n; break; }
        n = n.parentNode;
      }
      if (!b || !/^(立即登录|登录|Login)$/i.test((b.innerText || '').replace(/\s+/g, ''))) { return; }
      var f = fields();
      if (!f || !f.u || !f.u.value || !f.p.value) { return; }
      send({ t: 'creds', u: f.u.value, p: f.p.value });
    } catch (e) {}
  }, true);

  /* ---- 6. poll ---- */
  function tick() {
    try {
      var tok = ssGet('token');
      if (tok !== lastTok) {
        if (tok) { send({ t: 'login', session: snapshot() }); }
        else if (lastTok) { send({ t: 'tokenGone' }); }
        lastTok = tok;
      }
      var r = route();
      if (r !== lastRoute) {
        lastRoute = r;
        send({ t: 'route', v: r });
      }
      dialogs();
      if (submittedAt && !tok && Date.now() - submittedAt > 5000 && attempts >= 1) {
        submittedAt = 0;
        send({ t: 'loginFail' });
      }
      if (!ready) { ready = true; send({ t: 'ready' }); }
    } catch (e) {}
  }

  // report synchronously: the site may redirect to CAS before any timer fires
  tick();
  setInterval(tick, 700);
})();
