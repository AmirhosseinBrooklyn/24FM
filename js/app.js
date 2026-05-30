'use strict';

(function () {
  const LS_GENRE = '24fm_last_genre';

  Popup.onGenreSelect(function (genre) {
    Popup.setActiveKey(genre.key);

    if (window.Analytics) {
      Analytics.trackGenreSelect(genre.key);
      Analytics.trackPlay(genre.key);
    }

    Player.start(genre.url, genre.label);
  });

  Popup.onPowerOff(function () {
    Player.stop();           
    if (window.Analytics) Analytics.trackStop();
  });

  var savedKey = null;
  try { savedKey = localStorage.getItem(LS_GENRE); } catch (_) {}

  if (savedKey) {
    var genres = Popup.getGenres();
    var found  = null;
    for (var i = 0; i < genres.length; i++) {
      if (genres[i].key === savedKey) { found = genres[i]; break; }
    }
    if (found) {
      Popup.setActiveKey(found.key);
      
      Player.setCurrentUrl(found.url, found.label);
      
      var lbl = document.getElementById('track-genre-label');
      if (lbl) lbl.textContent = found.label;
    }
  }

  document.body.classList.add('state-off');
  document.body.classList.remove('state-on');
})();
