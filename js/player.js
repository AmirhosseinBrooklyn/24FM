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
var trackGenre   = document.getElementById('track-genre-label');

var audio = new Audio();
audio.preload = 'none';
audio.crossOrigin = 'anonymous';

var hls          = null;
var playing      = false;
var armed        = false;
var currentUrl   = null;
var currentLabel = '';

var watchdogTimer = null;
var retryTimer    = null;
var retryCount    = 0;
var WATCHDOG_MS   = 12000;
var RETRY_BASE_MS = 3000;
var RETRY_MAX_MS  = 20000;

var savedVol = parseFloat(localStorage.getItem('24fm_volume'));
audio.volume = isNaN(savedVol) ? 1.0 : savedVol;
volumeSlider.value = audio.volume;

var ARM_RESTING = 'rotate(22deg)';
var ARM_PLAYING = 'rotate(55deg)';

function armRest() { toneArm.style.transform = ARM_RESTING; }
function armPlay() { toneArm.style.transform = ARM_PLAYING; }

toneArm.style.transition = 'none';
toneArm.style.transform  = ARM_RESTING;
requestAnimationFrame(function () {
  requestAnimationFrame(function () { toneArm.style.transition = ''; });
});

function showBuffer(on) { bufferRing.classList.toggle('visible', on); }
function showError(on)  { streamError.classList.toggle('visible', on); }

function setPlayIcon(isPlaying) {
  iconPlay.style.display  = isPlaying ? 'none' : '';
  iconPause.style.display = isPlaying ? ''     : 'none';
}

function setBodyState(state) {
  document.body.classList.remove('state-off', 'state-on', 'state-paused');
  document.body.classList.add(state);
}

function startWatchdog() {
  clearTimeout(watchdogTimer);
  watchdogTimer = setTimeout(function () {
    if (armed && currentUrl) retryStream();
  }, WATCHDOG_MS);
}

function clearWatchdog() {
  clearTimeout(watchdogTimer);
  watchdogTimer = null;
}

function retryStream() {
  clearWatchdog();
  clearTimeout(retryTimer);
  if (!armed || !currentUrl) return;
  retryCount++;
  showBuffer(true);
  showError(false);
  var delay = Math.min(RETRY_BASE_MS * retryCount, RETRY_MAX_MS);
  retryTimer = setTimeout(function () {
    if (armed && currentUrl) _loadStream(currentUrl);
  }, delay);
}

function resetRetry() {
  retryCount = 0;
  clearTimeout(retryTimer);
  retryTimer = null;
  clearWatchdog();
}

/* CD swap — runs in parallel with stream loading, never blocks it */
var cdSwapLocked = false;

function swapCD() {
  if (cdSwapLocked) return;
  cdSwapLocked = true;

  cdWrap.style.animation = 'none';
  cdWrap.classList.remove('sliding-in', 'sliding-out');
  void cdWrap.offsetWidth;

  cdWrap.classList.add('sliding-out');

  function onOut() {
    cdWrap.removeEventListener('animationend', onOut);
    cdWrap.classList.remove('sliding-out');
    void cdWrap.offsetWidth;
    cdWrap.classList.add('sliding-in');
    cdWrap.addEventListener('animationend', function onIn() {
      cdWrap.removeEventListener('animationend', onIn);
      cdWrap.classList.remove('sliding-in');
      cdSwapLocked = false;
    });
  }

  cdWrap.addEventListener('animationend', onOut);

  /* Safety: if animation never fires (e.g. prefers-reduced-motion), unlock after timeout */
  setTimeout(function () {
    if (cdSwapLocked) {
      cdWrap.classList.remove('sliding-out', 'sliding-in');
      cdSwapLocked = false;
    }
  }, 2000);
}

function _destroyHls() {
  if (hls) { hls.destroy(); hls = null; }
  audio.pause();
  audio.removeAttribute('src');
  audio.load();
}

/* ── Core fix: stream loads IMMEDIATELY on user gesture ─────────────────── */
function _loadStream(url) {
  _destroyHls();
  showError(false);
  showBuffer(true);
  startWatchdog();

  if (typeof Hls !== 'undefined' && Hls.isSupported()) {
    hls = new Hls({
      enableWorker:               true,
      lowLatencyMode:             false,
      backBufferLength:           0,
      maxBufferLength:            20,
      maxMaxBufferLength:         60,
      liveSyncDurationCount:      3,
      liveMaxLatencyDurationCount: 10,
      fragLoadingTimeOut:         10000,
      manifestLoadingTimeOut:     10000,
      levelLoadingTimeOut:        10000,
      fragLoadingMaxRetry:        4,
      manifestLoadingMaxRetry:    4,
      levelLoadingMaxRetry:       4,
      startFragPrefetch:          true,
      testBandwidth:              false,
      xhrSetup: function (xhr) { xhr.withCredentials = false; },
    });

    hls.loadSource(url);
    hls.attachMedia(audio);

    hls.on(Hls.Events.MANIFEST_PARSED, function () {
      var promise = audio.play();
      if (promise !== undefined) {
        promise.catch(function (err) {
          /* Autoplay blocked — wait for next user interaction */
          showBuffer(false);
          showError(false);
          /* Show play icon so user knows to tap again */
          setPlayIcon(false);
          playing = false;
          document.addEventListener('click', function resume() {
            document.removeEventListener('click', resume);
            if (armed && hls) audio.play().catch(function () {});
          }, { once: true });
        });
      }
    });

    hls.on(Hls.Events.ERROR, function (evt, data) {
      if (!data.fatal) return;
      if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
        hls.recoverMediaError();
      } else {
        retryStream();
      }
    });

  } else if (audio.canPlayType('application/vnd.apple.mpegurl')) {
    /* Safari native HLS */
    audio.src = url;
    var p = audio.play();
    if (p) p.catch(function () { showBuffer(false); showError(true); clearWatchdog(); });

  } else {
    showBuffer(false);
    showError(true);
    clearWatchdog();
  }
}

/* Audio events */
audio.addEventListener('playing', function () {
  clearWatchdog();
  resetRetry();
  showBuffer(false);
  showError(false);
  record.classList.add('spinning');
  playing = true;
  setPlayIcon(true);
});

audio.addEventListener('waiting', function () { showBuffer(true); startWatchdog(); });
audio.addEventListener('stalled', function () { showBuffer(true); startWatchdog(); });
audio.addEventListener('canplay', function () { clearWatchdog(); });

audio.addEventListener('pause', function () {
  if (!playing) record.classList.remove('spinning');
});

audio.addEventListener('error', function () {
  if (armed) { showBuffer(false); retryStream(); }
});

/* ── Playback control ───────────────────────────────────────────────────── */

function startPlayback(url, genreLabel) {
  if (!url) return;

  armed        = true;
  currentUrl   = url;
  currentLabel = genreLabel || '';

  setPlayIcon(true);
  showError(false);
  armPlay();

  if (trackGenre && genreLabel) trackGenre.textContent = genreLabel;

  record.classList.remove('spinning');
  setBodyState('state-on');

  /* CRITICAL: load stream immediately — preserves browser autoplay gesture context.
     CD animation runs in parallel, never delays audio.play(). */
  _loadStream(url);
  swapCD();
}

function stopPlayback() {
  armed   = false;
  playing = false;
  resetRetry();
  _destroyHls();

  setPlayIcon(false);
  showBuffer(false);
  showError(false);
  record.classList.remove('spinning');
  armRest();

  currentUrl   = null;
  currentLabel = '';
  setBodyState('state-off');
}

function togglePlayPause() {
  if (!currentUrl) {
    if (window.Popup) window.Popup.open();
    return;
  }

  if (playing) {
    clearWatchdog();
    clearTimeout(retryTimer);
    audio.pause();
    playing = false;
    setPlayIcon(false);
    record.classList.remove('spinning');
    showBuffer(false);
    armRest();
    setBodyState('state-paused');
  } else {
    startPlayback(currentUrl, currentLabel);
  }
}

btnPlay.addEventListener('click', function () { togglePlayPause(); });
record.addEventListener('click',  function () { togglePlayPause(); });

volumeSlider.addEventListener('input', function (e) {
  var vol = parseFloat(e.target.value);
  audio.volume = vol;
  try { localStorage.setItem('24fm_volume', String(vol)); } catch (_) {}
});

window.Player = {
  start: startPlayback,
  stop:  stopPlayback,
  setCurrentUrl: function (url, label) {
    currentUrl   = url;
    currentLabel = label || '';
    armed        = !!url;
  },
  isPlaying: function () { return playing; },
};
