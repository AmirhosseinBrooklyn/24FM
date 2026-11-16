'use strict';

var record       = document.getElementById('record');
var cdWrap       = document.getElementById('cd-wrap');
var toneArm      = document.getElementById('tone-arm');
var btnPlay      = document.getElementById('btn-play');
var iconPlay     = btnPlay.querySelector('.icon-play');
var iconPause    = btnPlay.querySelector('.icon-pause');
var bufferRing   = document.getElementById('buffer-ring');
var streamError  = document.getElementById('stream-error');
var volumeSlider = document.getElementById('volume-slider');
var volBtn       = document.getElementById('vol-btn');
var volIconOn    = volBtn.querySelector('.vol-icon-on');
var volIconMuted = volBtn.querySelector('.vol-icon-muted');
var trackGenre   = document.getElementById('track-genre-label');

// Playback intent and confirmed connection state are deliberately separate.
var audio = new Audio();
audio.preload = 'none';
var hls = null, playing = false, armed = false, destroying = false;
var currentUrl = null, currentLabel = '', currentGenreKey = '', attemptId = '';
var connectionState = 'off', generation = 0;
var watchdogTimer = null, retryTimer = null, retryCount = 0;
var WATCHDOG_MS = 12000, RETRY_BASE_MS = 3000, RETRY_MAX_MS = 20000, MAX_RETRIES = 4;
var isMuted = false, prevVolume = 1;
var savedVol = 1;
try { var stored = Number(localStorage.getItem('24fm_volume')); if (localStorage.getItem('24fm_volume') !== null && isFinite(stored)) savedVol = Math.max(0, Math.min(1, stored)); } catch (_) {}
audio.volume = savedVol;
volumeSlider.value = savedVol;
isMuted = savedVol === 0;
prevVolume = savedVol || 1;
volIconOn.style.display = isMuted ? 'none' : '';
volIconMuted.style.display = isMuted ? '' : 'none';

toneArm.style.transition = 'none';
toneArm.style.transform = 'rotate(22deg)';
requestAnimationFrame(function () { requestAnimationFrame(function () { toneArm.style.transition = ''; }); });
function armRest() { toneArm.style.transform = 'rotate(22deg)'; }
function armPlay() { toneArm.style.transform = 'rotate(55deg)'; }
function showBuffer(on) { bufferRing.classList.toggle('visible', on); }
function showError(on) { streamError.classList.toggle('visible', on); }
function setPlayIcon(on) { iconPlay.style.display = on ? 'none' : ''; iconPause.style.display = on ? '' : 'none'; }
function setBodyState(state) {
  document.body.classList.remove('state-off', 'state-on', 'state-paused');
  document.body.classList.add(state);
}
function stateSnapshot() { return { state: connectionState, attemptId: attemptId, genre: currentGenreKey, url: currentUrl, label: currentLabel }; }
function publishState(state) {
  connectionState = state;
  playing = state === 'live';
  document.body.dataset.connection = state;
  var label = document.getElementById('connection-status');
  if (label) { var text = { off: 'خاموش', connecting: 'در حال اتصال', live: 'زنده' }[state]; if (label.textContent !== text) label.textContent = text; }
  record.classList.toggle('spinning', playing);
  showBuffer(state === 'connecting');
  setPlayIcon(armed);
  if (state === 'off') armRest(); else armPlay();
  document.dispatchEvent(new CustomEvent('24fm:statechange', { detail: stateSnapshot() }));
}
function genreKey(url, label, key) {
  if (key) return key;
  var genres = window.Popup ? Popup.getGenres() : [];
  var match = genres.find(function (g) { return g.url === url && g.label === label; }) || genres.find(function (g) { return g.url === url; });
  return match ? match.key : '';
}
function clearWatchdog() { clearTimeout(watchdogTimer); watchdogTimer = null; }
function resetRetry() { retryCount = 0; clearWatchdog(); clearTimeout(retryTimer); retryTimer = null; }
function startWatchdog() {
  clearWatchdog();
  // The Android service owns its retry budget, including background playback.
  if (window.__fm24BridgeInstalled) return;
  watchdogTimer = setTimeout(function () { if (armed && !playing) retryStream(); }, WATCHDOG_MS);
}
function _destroyHls() {
  var stamp = ++generation;
  destroying = true;
  if (hls) { hls.destroy(); hls = null; }
  audio.pause(); audio.removeAttribute('src'); audio.load();
  setTimeout(function () { if (stamp === generation) destroying = false; }, 100);
}
function failPlayback() {
  armed = false;
  resetRetry(); _destroyHls(); showError(true); setBodyState('state-paused'); publishState('off');
}
function retryStream() {
  clearWatchdog(); clearTimeout(retryTimer);
  if (!armed || !currentUrl || playing) return;
  if (retryCount >= MAX_RETRIES) { failPlayback(); return; }
  retryCount++;
  publishState('connecting');
  showError(false);
  var stamp = generation;
  retryTimer = setTimeout(function () {
    if (armed && !playing && stamp === generation) _loadStream(currentUrl);
  }, Math.min(RETRY_BASE_MS * retryCount, RETRY_MAX_MS));
}
function isHLSUrl(url) { return /\.m3u8(\?|$)/i.test(url); }
function _loadStream(url) {
  _destroyHls();
  var stamp = generation;
  showError(false); publishState('connecting'); startWatchdog();
  function current() { return armed && stamp === generation; }
  function play() {
    if (!current()) return;
    var promise = audio.play();
    if (promise && promise.catch) promise.catch(function (err) {
      if (!current() || err.name === 'AbortError') return;
      if (err.name === 'NotAllowedError' || window.__fm24BridgeInstalled) failPlayback();
      else retryStream();
    });
  }
  if (isHLSUrl(url) && typeof Hls !== 'undefined' && Hls.isSupported()) {
    var instance = new Hls({ enableWorker: true, backBufferLength: 0, maxBufferLength: 20,
      maxMaxBufferLength: 60, liveSyncDurationCount: 3, liveMaxLatencyDurationCount: 10,
      fragLoadingTimeOut: 10000, manifestLoadingTimeOut: 10000, levelLoadingTimeOut: 10000,
      fragLoadingMaxRetry: 3, manifestLoadingMaxRetry: 3, levelLoadingMaxRetry: 3,
      xhrSetup: function (xhr) { xhr.withCredentials = false; } });
    hls = instance;
    instance.on(Hls.Events.MANIFEST_PARSED, play);
    instance.on(Hls.Events.ERROR, function (_, data) {
      if (!current() || !data.fatal) return;
      publishState('connecting');
      if (data.type === Hls.ErrorTypes.MEDIA_ERROR && retryCount < MAX_RETRIES) {
        retryCount++; instance.recoverMediaError(); startWatchdog();
      } else retryStream();
    });
    instance.loadSource(url); instance.attachMedia(audio);
  } else if (!isHLSUrl(url) || audio.canPlayType('application/vnd.apple.mpegurl')) {
    audio.src = url; play();
  } else failPlayback();
}
audio.addEventListener('playing', function () {
  if (!armed || (!window.__fm24BridgeInstalled && audio.readyState < 2)) return;
  resetRetry(); showError(false); publishState('live');
});
function buffering() { if (armed) { publishState('connecting'); startWatchdog(); } }
audio.addEventListener('waiting', buffering);
audio.addEventListener('stalled', function () { if (audio.readyState < 3 || window.__fm24BridgeInstalled) buffering(); });
audio.addEventListener('pause', function () {
  if (!destroying && armed && !window.__fm24BridgeInstalled) pausePlayback();
});
audio.addEventListener('error', function () {
  if (destroying || !armed) return;
  publishState('connecting');
  if (window.__fm24BridgeInstalled) failPlayback(); else retryStream();
});
audio.addEventListener('ended', function () { if (armed) { publishState('connecting'); retryStream(); } });
function startPlayback(url, label, key) {
  if (!url) return;
  var reuse = armed && playing && currentUrl === url;
  currentUrl = url; currentLabel = label || ''; currentGenreKey = genreKey(url, label, key);
  if (trackGenre) trackGenre.textContent = currentLabel;
  document.body.classList.add('has-genre');
  if (reuse) {
    if (window.__fm24BridgeInstalled) { audio.src = url; audio.play().catch(function () {}); }
    publishState('live'); swapCD(); return;
  }
  resetRetry(); armed = true;
  attemptId = crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + '-' + Math.random().toString(36).slice(2);
  setBodyState('state-on');
  _loadStream(url); swapCD();
}
function pausePlayback(external) {
  armed = false; resetRetry();
  if (external === true && window.__fm24BridgeInstalled) generation++; else _destroyHls();
  showError(false);
  setBodyState('state-paused'); publishState('off');
}
function togglePlayPause() {
  if (!currentUrl) { if (window.Popup) Popup.open(); return; }
  if (armed) pausePlayback(); else startPlayback(currentUrl, currentLabel, currentGenreKey);
}
btnPlay.addEventListener('click', togglePlayPause);
record.addEventListener('click', togglePlayPause);

// ── CD swap animation ─────────────────────────────────────────────────────
var cdSwapLocked = false;
var cdSwapGeneration = 0;

function swapCD() {
  if (cdSwapLocked) return;
  cdSwapLocked = true;
  var stamp = ++cdSwapGeneration;
  var fallback;
  // Keep the page-entry animation from restarting when the swap classes are removed.
  cdWrap.style.animation = 'none';
  cdWrap.classList.remove('sliding-in', 'sliding-out');
  void cdWrap.offsetWidth;
  cdWrap.classList.add('sliding-out');

  function finish() {
    if (stamp !== cdSwapGeneration) return;
    clearTimeout(fallback);
    cdWrap.removeEventListener('animationend', onOut);
    cdWrap.removeEventListener('animationend', onIn);
    cdWrap.classList.remove('sliding-out', 'sliding-in');
    cdSwapLocked = false;
  }
  function onIn(event) {
    if (event.target === cdWrap && event.animationName === 'cd-in') finish();
  }
  function onOut(event) {
    if (stamp !== cdSwapGeneration || event.target !== cdWrap || event.animationName !== 'cd-out') return;
    cdWrap.removeEventListener('animationend', onOut);
    cdWrap.classList.remove('sliding-out');
    void cdWrap.offsetWidth;
    cdWrap.classList.add('sliding-in');
    cdWrap.addEventListener('animationend', onIn);
  }

  cdWrap.addEventListener('animationend', onOut);
  fallback = setTimeout(finish, 2000);
}


// ── Volume & mute ─────────────────────────────────────────────────────────
volumeSlider.addEventListener('input', function (e) {
  var vol = parseFloat(e.target.value);
  audio.volume = vol;
  if (vol > 0) {
    isMuted    = false;
    prevVolume = vol;
    volIconOn.style.display    = '';
    volIconMuted.style.display = 'none';
  } else {
    isMuted = true;
    volIconOn.style.display    = 'none';
    volIconMuted.style.display = '';
  }
  try { localStorage.setItem('24fm_volume', String(vol)); } catch (_) {}
});

volBtn.addEventListener('click', function () {
  if (!isMuted) {
    prevVolume = audio.volume || 1.0;
    audio.volume       = 0;
    volumeSlider.value = 0;
    isMuted = true;
    volIconOn.style.display    = 'none';
    volIconMuted.style.display = '';
  } else {
    var vol = prevVolume > 0 ? prevVolume : 1.0;
    audio.volume       = vol;
    volumeSlider.value = vol;
    isMuted = false;
    volIconOn.style.display    = '';
    volIconMuted.style.display = 'none';
  }
  try { localStorage.setItem('24fm_volume', String(audio.volume)); } catch (_) {}
});


window.Player = {
  start: startPlayback, pause: pausePlayback,
  setCurrentUrl: function (url, label, key) {
    currentUrl = url; currentLabel = label || ''; currentGenreKey = genreKey(url, label, key);
    if (url) document.body.classList.add('has-genre');
    if (trackGenre && label) trackGenre.textContent = label;
  },
  isPlaying: function () { return playing; }, getState: stateSnapshot,
};
publishState('off');
