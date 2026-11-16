'use strict';
(function () {
  var INITIAL_DELAY = 1000;               // ms after the page has fully loaded (page-load intro only)
  // Letter indexes of "24FM" that flicker on successive successful genre changes: "4" + "M", then "2" + "F".
  var PAIRS = [[1, 3], [0, 2]];
  var motion = window.matchMedia('(prefers-reduced-motion: reduce)');
  var el, core, halo, introMs = 0, initialTimer, introEndTimer, flickerTimer;
  var initialStarted = false;             // the page-load intro has run (or was superseded by a pause)
  var striking = false;                   // an intro is running
  var pendingFlicker = false;             // a genre change succeeded while the logo was busy or waiting for its intro
  var pendingKey = null;                  // genre whose playback we are waiting to see confirmed
  var engaged = false;                    // playback has been confirmed and not stopped since
  var successes = 0;                      // successful genre changes, for alternating the pairs

  function rnd(a, b) { return a + Math.random() * (b - a); }
  function copies() { return [core, halo]; }
  function isOff() { return el.classList.contains('is-off'); }

  function build() {
    core = document.createElement('span'); core.className = 's';
    halo = document.createElement('span'); halo.className = 'g';
    halo.setAttribute('aria-hidden', 'true');
    Array.from(el.dataset.text).forEach(function (ch) {
      var l = document.createElement('span'); l.className = 'l'; l.textContent = ch;
      var d = rnd(2.2, 4.5);
      l.style.setProperty('--d', d.toFixed(2) + 's');
      l.style.setProperty('--dl', '0s');
      introMs = Math.max(introMs, d * 1000);
      core.appendChild(l);
    });
    halo.innerHTML = core.innerHTML;       // the halo copy shares the core's timing
    el.textContent = ''; el.append(halo, core);
  }

  // ── Intro: page load (after a delay) and every confirmed resume (no delay) ──
  function startIntro() {
    clearTimeout(introEndTimer);
    clearFlicker();
    if (motion.matches) { el.classList.remove('is-off'); finishIntro(); return; }
    striking = true;
    el.classList.add('is-striking');
    el.classList.remove('is-off');
    introEndTimer = setTimeout(finishIntro, introMs + 100);
  }
  // The second half of the intro is a steady lit state, so ending it is invisible.
  // Dropping the class keeps the intro from re-running when other classes change.
  function finishIntro() {
    clearTimeout(introEndTimer);
    striking = false;
    el.classList.remove('is-striking');
    if (pendingFlicker) { pendingFlicker = false; flicker(); }
  }
  function startInitial() {
    initialTimer = null;
    if (initialStarted) return;
    initialStarted = true;
    startIntro();
  }

  // ── Pause / stop: back to the off state, cancelling anything still scheduled ─
  function stopLogo() {
    clearTimeout(initialTimer); initialTimer = null; initialStarted = true;
    clearTimeout(introEndTimer); striking = false; pendingFlicker = false;
    clearFlicker();
    el.classList.remove('is-striking');
    el.classList.add('is-off');
  }

  // ── Successful genre change: "tired tube" flicker on one pair, played once ──
  function clearFlicker() {
    clearTimeout(flickerTimer); flickerTimer = null;
    copies().forEach(function (copy) {
      Array.prototype.forEach.call(copy.children, function (l) {
        l.classList.remove('is-tired');
        l.style.removeProperty('--td'); l.style.removeProperty('--tdl');
      });
    });
  }
  function flicker() {
    clearFlicker();                        // the previous pair is lit again straight away
    if (motion.matches) return;
    void el.offsetWidth;
    var longest = 0;
    PAIRS[(successes - 1) % PAIRS.length].forEach(function (i) {
      var cycle = rnd(6, 11), first = rnd(0.3, 1.6);   // cycle length, and seconds until the first dropout
      longest = Math.max(longest, first + cycle * 0.2);   // time left in the cycle once it has started
      var dur = cycle.toFixed(2) + 's', delay = (first - cycle * 0.8).toFixed(2) + 's';
      copies().forEach(function (copy) {
        var l = copy.children[i];
        l.style.setProperty('--td', dur); l.style.setProperty('--tdl', delay);
        l.classList.add('is-tired');
      });
    });
    flickerTimer = setTimeout(clearFlicker, longest * 1000 + 150);   // one play, then drop the class
  }
  function genreChanged() {
    successes++;
    if (striking) { pendingFlicker = true; return; }                 // never interrupt an intro
    if (isOff()) {
      if (!initialStarted) { pendingFlicker = true; return; }        // the page-load intro is still to come
      el.classList.remove('is-off');                                  // lit without an intro; the flicker is the only animation
    }
    flicker();
  }

  // ── Playback events ─────────────────────────────────────────────────────────
  function onGenreChange(e) { pendingKey = e.detail && e.detail.key || null; }
  function onState(e) {
    var d = e.detail || {};
    if (d.state === 'live') {
      engaged = true;
      if (pendingKey && d.genre === pendingKey) { pendingKey = null; genreChanged(); }
      else if (isOff() && initialStarted) startIntro();               // confirmed resume
    } else if (d.state === 'off') {
      pendingKey = null;                                              // paused or failed: a pending change never took effect
      if (engaged) { engaged = false; stopLogo(); }
    }
  }

  function preferenceChanged() {
    if (!el || !motion.matches) return;
    clearFlicker();
    if (striking) finishIntro();
  }
  motion.addEventListener('change', preferenceChanged);

  function init() {
    el = document.querySelector('.fluo');
    if (!el) return;
    build();
    document.addEventListener('24fm:genrechange', onGenreChange);
    document.addEventListener('24fm:statechange', onState);
    if (document.readyState === 'complete') initialTimer = setTimeout(startInitial, INITIAL_DELAY);
    else window.addEventListener('load', function () { initialTimer = setTimeout(startInitial, INITIAL_DELAY); }, { once: true });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once: true });
  else init();
})();
