/*
 * Small layout touches for the app, injected by it; the website itself is unchanged.
 *
 * The site is built for a tall portrait screen and hides overflow, so in phone landscape or
 * split-screen its player controls fall off the bottom. When the viewport is short, the page
 * is allowed to scroll instead.
 */
(function () {
  'use strict';
  if (document.getElementById('fm24-app-layout')) return;
  var style = document.createElement('style');
  style.id = 'fm24-app-layout';
  // Long-pressing the record or buttons shouldn't select text like a web page would.
  style.textContent =
    'body { -webkit-user-select: none; user-select: none; -webkit-touch-callout: none; }' +
    '@media (max-height: 600px) {' +
    '  html { height: auto !important; overflow-y: auto !important; }' +
    '  body { overflow: visible !important; min-height: 100vh; }' +
    '}';
  (document.head || document.documentElement).appendChild(style);
})();
