/*
 * 24FM Android bridge. Injected by the app at document start, only on https://24fm.ir.
 *
 * The website plays audio through a single `new Audio()` in js/player.js. Inside the app,
 * that element is replaced by NativeAudio, which forwards play/pause/volume to the native
 * Media3 player. So audio keeps playing in the background and on the lock screen, and the
 * page can never start a second, duplicate stream. The website itself is not modified.
 */
(function () {
  'use strict';

  var native = window.FM24Native;
  if (!native || window.__fm24BridgeInstalled) return;
  window.__fm24BridgeInstalled = true;

  function send(msg) {
    try { native.postMessage(JSON.stringify(msg)); } catch (e) { /* bridge unavailable */ }
  }

  // ── 1. Keep hls.js out of the way: player.js then hands every URL (HLS included)
  //       to audio.src, and the native player handles HLS itself.
  try {
    Object.defineProperty(window, 'Hls', {
      configurable: false,
      get: function () { return undefined; },
      set: function () { /* ignore hls.js UMD assignment */ }
    });
  } catch (e) { /* already defined: player.js still works with NativeAudio */ }

  // ── 2. Native-backed replacement for HTMLAudioElement ───────────────────────────
  var nativeState = 'idle';
  var nativeUrl = null;
  var nativeLabel = null;
  var element = null;          // the page's (single) audio element
  var pendingPause = null;     // debounced pause, cancelled by an immediate re-play

  function NativeAudio() {
    this._listeners = {};
    this._src = '';
    this._volume = 1;
    this.preload = 'none';
    this.crossOrigin = null;
    element = this;
  }

  NativeAudio.prototype.addEventListener = function (type, fn) {
    if (typeof fn !== 'function') return;
    (this._listeners[type] = this._listeners[type] || []).push(fn);
  };
  NativeAudio.prototype.removeEventListener = function (type, fn) {
    var list = this._listeners[type];
    if (!list) return;
    var i = list.indexOf(fn);
    if (i >= 0) list.splice(i, 1);
  };
  NativeAudio.prototype._emit = function (type) {
    var list = (this._listeners[type] || []).slice();
    var evt = { type: type, target: this, currentTarget: this };
    for (var i = 0; i < list.length; i++) {
      try { list[i].call(this, evt); } catch (e) { setTimeout(function () { throw e; }); }
    }
  };

  Object.defineProperty(NativeAudio.prototype, 'src', {
    get: function () { return this._src; },
    set: function (v) { this._src = v ? String(v) : ''; }
  });
  Object.defineProperty(NativeAudio.prototype, 'volume', {
    get: function () { return this._volume; },
    set: function (v) {
      var n = Number(v);
      if (!isFinite(n)) return;
      this._volume = Math.min(1, Math.max(0, n));
      send({ t: 'volume', v: this._volume });
    }
  });
  Object.defineProperty(NativeAudio.prototype, 'paused', {
    get: function () { return nativeState !== 'playing' && nativeState !== 'buffering'; }
  });
  NativeAudio.prototype.currentTime = 0;
  NativeAudio.prototype.readyState = 0;

  NativeAudio.prototype.canPlayType = function () { return 'maybe'; };
  NativeAudio.prototype.removeAttribute = function (name) {
    if (name === 'src') this._src = '';
  };
  NativeAudio.prototype.load = function () { /* nothing to preload: native player owns the stream */ };

  NativeAudio.prototype.pause = function () {
    // player.js always calls pause() before switching streams. Defer it briefly so that an
    // immediate play() of the same URL does not stop and restart the live stream.
    if (pendingPause) return;
    pendingPause = setTimeout(function () {
      pendingPause = null;
      send({ t: 'pause' });
    }, 150);
  };

  NativeAudio.prototype.play = function () {
    if (pendingPause) { clearTimeout(pendingPause); pendingPause = null; }
    if (!this._src) return Promise.resolve();
    send({ t: 'play', url: this._src, label: currentLabel() });
    return Promise.resolve();
  };

  function currentLabel() {
    var el = document.getElementById('track-genre-label');
    return el ? (el.textContent || '').trim() : '';
  }

  window.Audio = NativeAudio;

  // ── 3. Native → page state sync ───────────────────────────────────────────────
  function bodyHas(cls) {
    return !!(document.body && document.body.classList.contains(cls));
  }

  function clickPlayButton() {
    var btn = document.getElementById('btn-play');
    if (btn) btn.click();
  }

  function findGenreKey(url) {
    try {
      var genres = window.Popup && window.Popup.getGenres ? window.Popup.getGenres() : [];
      for (var i = 0; i < genres.length; i++) if (genres[i].url === url) return genres[i].key;
    } catch (e) { /* ignore */ }
    return null;
  }

  function onNativeState(msg) {
    var previous = nativeState;
    nativeState = msg.state;
    nativeUrl = msg.url;
    nativeLabel = msg.label;

    if (!element) return;
    var player = window.Player;

    switch (nativeState) {
      case 'playing':
        if (bodyHas('state-on')) {
          element._emit('playing');
        } else if (player && nativeUrl) {
          // Started from the notification, a headset or before a page reload: reflect it.
          var key = findGenreKey(nativeUrl);
          if (key && window.Popup && window.Popup.setActiveKey) window.Popup.setActiveKey(key);
          player.start(nativeUrl, nativeLabel || '');
        }
        break;
      case 'buffering':
        if (bodyHas('state-on')) element._emit('waiting');
        break;
      case 'paused':
      case 'idle':
        if (bodyHas('state-on') && player) {
          // Paused natively (notification, headset, audio focus): run the page's own pause.
          if (!player.isPlaying()) element._emit('playing');
          clickPlayButton();
        } else if (previous !== nativeState) {
          element._emit('pause');
        }
        break;
      case 'error':
        if (bodyHas('state-on')) element._emit('error');
        break;
    }
  }

  native.onmessage = function (event) {
    var msg;
    try { msg = JSON.parse(event.data); } catch (e) { return; }
    if (msg && msg.t === 'state') onNativeState(msg);
  };

  // ── 4. UI hints for native pull-to-refresh and back handling ─────────────────────
  var lastUi = '';
  var touchBlocksRefresh = false;

  function reportUi() {
    var popupOpen = bodyHas('popup-open');
    var ui = { t: 'ui', canRefresh: !popupOpen && !touchBlocksRefresh, popupOpen: popupOpen };
    var key = ui.canRefresh + '|' + ui.popupOpen;
    if (key === lastUi) return;
    lastUi = key;
    send(ui);
  }

  function scrolledAncestor(node) {
    for (var el = node; el && el !== document.body && el.nodeType === 1; el = el.parentElement) {
      if (el.scrollTop > 0 && el.scrollHeight > el.clientHeight) return true;
    }
    return false;
  }

  document.addEventListener('touchstart', function (e) {
    touchBlocksRefresh = scrolledAncestor(e.target);
    reportUi();
  }, { capture: true, passive: true });

  var lastBodyState = null;

  function bodyState() {
    if (bodyHas('state-on')) return 'on';
    if (bodyHas('state-paused')) return 'paused';
    return 'off';
  }

  function onBodyClassChange() {
    reportUi();
    var state = bodyState();
    var previous = lastBodyState;
    lastBodyState = state;
    // The site's power-off button moves on/paused -> off: also dismiss the native session.
    if (state === 'off' && (previous === 'on' || previous === 'paused') && nativeState !== 'idle') {
      send({ t: 'stop' });
    }
  }

  document.addEventListener('DOMContentLoaded', function () {
    lastBodyState = bodyState();
    if (window.MutationObserver && document.body) {
      new MutationObserver(onBodyClassChange).observe(document.body, { attributes: true, attributeFilter: ['class'] });
    }
    reportUi();
    // Ask for the current native state so a reload while playing shows the playing UI.
    send({ t: 'hello' });
  });

  // Expose a minimal hook for native back handling.
  window.__fm24ClosePopup = function () {
    if (bodyHas('popup-open') && window.Popup && window.Popup.close) { window.Popup.close(); return true; }
    return false;
  };
})();
