'use strict';

(function () {
  var API = '/api/track.php';

  function send(data) {
    try {
      return fetch(API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
        keepalive: true,
      }).catch(function () {});
    } catch (e) {}
  }

  var ready = send({ event: 'visit' });

  /* Send unhandled errors to the server log so we hear about bugs users hit. */
  window.addEventListener('error', function (e) {
    send({
      event:   'fe_error',
      message: (e.message || 'unknown error').slice(0, 300),
      source:  (e.filename || '').replace(/^.*\//, '').slice(0, 80)
              + (e.lineno ? ':' + e.lineno : ''),
    });
  });

  window.addEventListener('unhandledrejection', function (e) {
    send({
      event:   'fe_error',
      message: ('Unhandled promise: ' + (e.reason || '')).slice(0, 300),
      source:  'promise',
    });
  });

  window.Analytics = {
    ready: ready,
    trackPlay: function (genreKey) {
      // Successful plays are confirmed by the presence endpoint, never by button taps.
    },
    trackGenreSelect: function (genreKey) {
      send({ event: 'genre_select', genre: String(genreKey).slice(0, 40) });
    },
  };
})();
