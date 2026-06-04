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

// ── Audio element (no crossOrigin — avoids CORS failures on Icecast streams) ──
var audio = new Audio();
audio.preload = 'none';

var hls        = null;
var playing    = false;
var armed      = false;
var destroying = false;   // true during _destroyHls to silence spurious errors
var currentUrl   = null;
var currentLabel = '';

var watchdogTimer = null;
var retryTimer    = null;
var retryCount    = 0;
var streamStartTime = 0;

var WATCHDOG_MS       = 12000;
var RETRY_BASE_MS     = 3000;
var RETRY_MAX_MS      = 20000;
var MAX_RETRIES       = 4;
var BLOCKED_THRESHOLD = 1200;  // errors within 1.2 s on first attempt = likely ad-blocked

// ── Mute state ────────────────────────────────────────────────────────────
var isMuted    = false;
var prevVolume = 1.0;

var savedVol = parseFloat(localStorage.getItem('24fm_volume'));
audio.volume = isNaN(savedVol) ? 1.0 : savedVol;
volumeSlider.value = audio.volume;

// ── Arm positions ─────────────────────────────────────────────────────────
toneArm.style.transition = 'none';
toneArm.style.transform  = 'rotate(22deg)';
requestAnimationFrame(function () {
  requestAnimationFrame(function () { toneArm.style.transition = ''; });
});

function armRest() { toneArm.style.transform = 'rotate(22deg)'; }
function armPlay() { toneArm.style.transform = 'rotate(55deg)'; }

// ── UI helpers ────────────────────────────────────────────────────────────
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

// ── Watchdog ──────────────────────────────────────────────────────────────
function startWatchdog() {
  clearTimeout(watchdogTimer);
  watchdogTimer = setTimeout(function () {
    if (armed && currentUrl && !playing) retryStream();
  }, WATCHDOG_MS);
}

function clearWatchdog() {
  clearTimeout(watchdogTimer);
  watchdogTimer = null;
}

// ── Retry logic ───────────────────────────────────────────────────────────
function retryStream() {
  clearWatchdog();
  clearTimeout(retryTimer);
  if (!armed || !currentUrl) return;
  if (playing) return;                        // already recovered — don't interrupt
  if (retryCount >= MAX_RETRIES) {
    showBuffer(false);
    showError(true);
    return;
  }
  retryCount++;
  showBuffer(true);
  showError(false);
  var delay = Math.min(RETRY_BASE_MS * retryCount, RETRY_MAX_MS);
  retryTimer = setTimeout(function () {
    if (armed && currentUrl && !playing) _loadStream(currentUrl);
  }, delay);
}

function resetRetry() {
  retryCount = 0;
  clearTimeout(retryTimer);
  retryTimer = null;
  clearWatchdog();
}

// ── CD swap animation ─────────────────────────────────────────────────────
var cdSwapLocked = false;

function swapCD() {
  if (cdSwapLocked) return;
  cdSwapLocked = true;
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
  setTimeout(function () {
    if (cdSwapLocked) {
      cdWrap.classList.remove('sliding-out', 'sliding-in');
      cdSwapLocked = false;
    }
  }, 2000);
}

// ── Stream helpers ────────────────────────────────────────────────────────
function isHLSUrl(url) {
  return /\.m3u8(\?|$)/i.test(url);
}

function _destroyHls() {
  destroying = true;
  if (hls) { hls.destroy(); hls = null; }
  audio.pause();
  audio.removeAttribute('src');
  audio.load();
  // Clear flag async — audio.load() can dispatch events after the current stack
  setTimeout(function () { destroying = false; }, 100);
}

// ── Core stream loader ────────────────────────────────────────────────────
function _loadStream(url) {
  _destroyHls();
  streamStartTime = Date.now();
  showError(false);
  showBuffer(true);
  startWatchdog();

  if (isHLSUrl(url)) {
    // ── HLS ──────────────────────────────────────────────────────────────
    if (typeof Hls === 'undefined' || !Hls.isSupported()) {
      if (audio.canPlayType('application/vnd.apple.mpegurl')) {
        audio.src = url;
        audio.play().catch(function () { showBuffer(false); showError(true); clearWatchdog(); });
      } else {
        showBuffer(false); showError(true); clearWatchdog();
      }
      return;
    }

    hls = new Hls({
      enableWorker:                true,
      lowLatencyMode:              false,
      backBufferLength:            0,
      maxBufferLength:             20,
      maxMaxBufferLength:          60,
      liveSyncDurationCount:       3,
      liveMaxLatencyDurationCount: 10,
      fragLoadingTimeOut:          10000,
      manifestLoadingTimeOut:      10000,
      levelLoadingTimeOut:         10000,
      fragLoadingMaxRetry:         3,
      manifestLoadingMaxRetry:     3,
      levelLoadingMaxRetry:        3,
      startFragPrefetch:           true,
      testBandwidth:               false,
      xhrSetup: function (xhr) { xhr.withCredentials = false; },
    });

    hls.loadSource(url);
    hls.attachMedia(audio);

    var manifestFired = false;
    hls.on(Hls.Events.MANIFEST_PARSED, function () {
      if (manifestFired) return;   // guard against duplicate fires
      manifestFired = true;
      var p = audio.play();
      if (p !== undefined) {
        p.catch(function (err) {
          if (err.name === 'NotAllowedError') {
            showBuffer(false);
            setPlayIcon(false);
            playing = false;
            document.addEventListener('click', function resume() {
              document.removeEventListener('click', resume);
              if (armed && hls) audio.play().catch(function () {});
            }, { once: true });
          }
        });
      }
    });

    hls.on(Hls.Events.ERROR, function (evt, data) {
      if (!data.fatal) return;
      var elapsed = Date.now() - streamStartTime;
      if (elapsed < BLOCKED_THRESHOLD && retryCount === 0) {
        // Immediate failure on first attempt → likely blocked by ad-blocker
        clearWatchdog();
        showBuffer(false);
        showError(true);
        return;
      }
      if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
        hls.recoverMediaError();
      } else {
        retryStream();
      }
    });

  } else {
    // ── Direct MP3 / Icecast stream ───────────────────────────────────────
    audio.src = url;
    var p = audio.play();
    if (p !== undefined) {
      p.catch(function (err) {
        if (err.name === 'NotAllowedError') {
          showBuffer(false);
          setPlayIcon(false);
          playing = false;
          document.addEventListener('click', function resume() {
            document.removeEventListener('click', resume);
            if (armed) audio.play().catch(function () {});
          }, { once: true });
        } else {
          var elapsed = Date.now() - streamStartTime;
          if (elapsed < BLOCKED_THRESHOLD && retryCount === 0) {
            clearWatchdog();
            showBuffer(false);
            showError(true);
          } else {
            retryStream();
          }
        }
      });
    }
  }
}

// ── Audio events ──────────────────────────────────────────────────────────
audio.addEventListener('playing', function () {
  clearWatchdog();
  resetRetry();
  showBuffer(false);
  showError(false);
  record.classList.add('spinning');
  playing = true;
  setPlayIcon(true);
});

// Only react to buffering events when we are actively supposed to be playing
audio.addEventListener('waiting', function () {
  if (!armed) return;
  showBuffer(true);
  startWatchdog();
});

audio.addEventListener('stalled', function () {
  if (!armed) return;
  showBuffer(true);
  startWatchdog();
});

audio.addEventListener('canplay', function () {
  clearWatchdog();
});

audio.addEventListener('pause', function () {
  // Only tidy up the spinner if playback was not intentional
  if (!playing) record.classList.remove('spinning');
});

audio.addEventListener('error', function () {
  if (destroying) return;   // ignore cleanup noise from _destroyHls
  if (!armed) return;
  var elapsed = Date.now() - streamStartTime;
  showBuffer(false);
  if (elapsed < BLOCKED_THRESHOLD && retryCount === 0) {
    clearWatchdog();
    showError(true);
  } else {
    retryStream();
  }
});

// ── Playback control ──────────────────────────────────────────────────────
function startPlayback(url, genreLabel) {
  if (!url) return;

  armed        = true;
  currentUrl   = url;
  currentLabel = genreLabel || '';

  setPlayIcon(true);
  showError(false);
  armPlay();

  if (trackGenre && genreLabel) trackGenre.textContent = genreLabel;

  document.body.classList.add('has-genre');
  record.classList.remove('spinning');
  setBodyState('state-on');

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
  document.body.classList.remove('has-genre');
  setBodyState('state-off');
}

function togglePlayPause() {
  if (!currentUrl) {
    if (window.Popup) window.Popup.open();
    return;
  }

  if (playing) {
    // ── PAUSE ──────────────────────────────────────────────────────────────
    // Set flags first so async events fired by _destroyHls are ignored
    playing = false;
    armed   = false;
    clearWatchdog();
    clearTimeout(retryTimer);

    // Fully tear down HLS + audio so no background loading can trigger
    // the watchdog or auto-resume
    _destroyHls();

    setPlayIcon(false);
    showBuffer(false);
    showError(false);
    record.classList.remove('spinning');
    armRest();
    setBodyState('state-paused');

  } else {
    // ── RESUME ─────────────────────────────────────────────────────────────
    startPlayback(currentUrl, currentLabel);
  }
}

btnPlay.addEventListener('click', function () { togglePlayPause(); });
record.addEventListener('click',  function () { togglePlayPause(); });

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

// ── Public API ────────────────────────────────────────────────────────────
window.Player = {
  start: startPlayback,
  stop:  stopPlayback,
  setCurrentUrl: function (url, label) {
    currentUrl   = url;
    currentLabel = label || '';
    armed        = !!url;
    if (url) document.body.classList.add('has-genre');
  },
  isPlaying: function () { return playing; },
};
