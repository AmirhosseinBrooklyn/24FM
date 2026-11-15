'use strict';

(function () {
  var COOKIE_GENRE = '24fm_genre';
  var COOKIE_HOURS = 24;

  // CookieUtils is defined by popup.js (loaded first)
  var CU = window.CookieUtils || {
    set: function () {},
    get: function () { return null; },
    remove: function () {},
  };

  // ── Genre select handler ──────────────────────────────────────────────────
  Popup.onGenreSelect(function (genre) {
    Popup.setActiveKey(genre.key);
    if (window.Analytics) {
      Analytics.trackGenreSelect(genre.key);
      Analytics.trackPlay(genre.key);
    }
    Player.start(genre.url, genre.label);
  });

  // ── Power-off handler ─────────────────────────────────────────────────────
  Popup.onPowerOff(function () {
    Player.stop();
    if (window.Analytics) Analytics.trackStop();
  });

  // ── Restore saved genre on page load ──────────────────────────────────────
  var savedKey = CU.get(COOKIE_GENRE);

  // Migrate from old localStorage key (one-time migration for existing users)
  if (!savedKey) {
    try {
      var oldKey = localStorage.getItem('24fm_last_genre');
      if (oldKey) {
        savedKey = oldKey;
        CU.set(COOKIE_GENRE, oldKey, COOKIE_HOURS);
        localStorage.removeItem('24fm_last_genre');
      }
    } catch (_) {}
  }

  if (savedKey) {
    var genres = Popup.getGenres();
    var found  = null;
    for (var i = 0; i < genres.length; i++) {
      if (genres[i].key === savedKey) { found = genres[i]; break; }
    }
    if (found) {
      Popup.setActiveKey(found.key);
      Player.setCurrentUrl(found.url, found.label);  // also adds has-genre to body

      var lbl = document.getElementById('track-genre-label');
      if (lbl) lbl.textContent = found.label;

      // track-info is made visible via body.has-genre CSS rule (no play needed)
    }
  }

  // Always start powered off; playback only begins on a tap.
  document.body.classList.add('state-off');
  document.body.classList.remove('state-on');
})();
