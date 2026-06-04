'use strict';

// ── Cookie Utilities (exposed globally for app.js) ─────────────────────────
function _setCookie(name, val, hours) {
  var exp = new Date(Date.now() + hours * 3600000).toUTCString();
  document.cookie = name + '=' + encodeURIComponent(val) +
    ';expires=' + exp + ';path=/;SameSite=Lax';
}
function _getCookie(name) {
  var nameEQ = name + '=';
  var ca = document.cookie.split(';');
  for (var i = 0; i < ca.length; i++) {
    var c = ca[i];
    while (c.charAt(0) === ' ') c = c.substring(1, c.length);
    if (c.indexOf(nameEQ) === 0)
      return decodeURIComponent(c.substring(nameEQ.length, c.length));
  }
  return null;
}
function _removeCookie(name) {
  document.cookie = name + '=;expires=Thu, 01 Jan 1970 00:00:00 UTC;path=/;SameSite=Lax';
}
window.CookieUtils = { set: _setCookie, get: _getCookie, remove: _removeCookie };

// ── Genre Data ─────────────────────────────────────────────────────────────

var GENRES_FA = [
  { key: 'dance-fa',   label: 'بزن و برقص',      emoji: '💃', url: 'https://api.radiomusic.app/v3/Dance/radio_live.m3u8' },
  { key: 'iran',       label: 'ایرونی',            emoji: '🎶', url: 'https://api.radiomusic.app/v3/iran/radio_live.m3u8' },
  { key: 'rap-fa-1',   label: 'رپ فارسی موج ۱',  emoji: '🎤', url: 'https://radio.9craft.ir:7443/prap' },
  { key: 'rap-fa-2',   label: 'رپ فارسی موج ۲',  emoji: '🎙️', url: 'https://stream.radiox.pro/listen/rapfa/radio.mp3' },
  { key: 'remix-fa',   label: 'ریمیکس',            emoji: '🔀', url: 'https://stream.radiox.pro/listen/remixfa/radio.mp3' },
  { key: 'fresh-fa',   label: 'معجون تازه‌ها',    emoji: '🌟', url: 'https://stream.radiox.pro/listen/popularfa/radio.mp3' },
  { key: 'nostalgy-1', label: 'نوستالژیک موج ۱', emoji: '📼', url: 'https://radio.9craft.ir:7443/persian' },
  { key: 'nostalgy-2', label: 'نوستالژیک موج ۲', emoji: '🗃️', url: 'https://stream.radiox.pro/listen/khaaz/radio.mp3' },
  { key: 'nostalgy-3', label: 'نوستالژی موج ۳',  emoji: '📻', url: 'https://api.radiomusic.app/v3/Nostalogy/radio_live.m3u8' },
  { key: 'nostalgy-4', label: 'نوستالژی موج ۴',  emoji: '🎞️', url: 'https://stream.radiox.pro/listen/classic/radio.mp3' },
  { key: 'young-fa',   label: 'جوون پسند',         emoji: '🔥', url: 'https://api.radiomusic.app/v3/Zirzamin/radio_live.m3u8' },
  { key: 'shooti',     label: 'شوتی',              emoji: '😄', url: 'https://stream.radiox.pro/listen/shooti/radio.mp3' },
  { key: 'road-1',     label: 'تو جاده موج ۱',   emoji: '🚗', url: 'https://api.radiomusic.app/v3/Couple/radio_live.m3u8' },
  { key: 'road-2',     label: 'تو جاده موج ۲',   emoji: '🛣️', url: 'https://api.radiomusic.app/v3/Romantic/radio_live.m3u8' },
];

var GENRES_EN = [
  { key: 'world-1',    label: 'برگزیده‌های جهان موج ۱', emoji: '🌍', url: 'https://radio.9craft.ir:7443/pop' },
  { key: 'world-2',    label: 'برگزیده‌های جهان موج ۲', emoji: '🌏', url: 'https://radio.9craft.ir:7443/pop2' },
  { key: 'world-3',    label: 'برگزیده‌های جهان موج ۳', emoji: '🌐', url: 'https://stream.radiox.pro/listen/pop/radio.mp3' },
  { key: 'country',    label: 'کانتری',               emoji: '🤠', url: 'https://radio.9craft.ir:7443/country' },
  { key: 'rock',       label: 'راک و متال',            emoji: '🤘', url: 'https://radio.9craft.ir:7443/rock' },
  { key: 'hiphop',     label: 'هیپ‌هاپ',              emoji: '🎵', url: 'https://radio.9craft.ir:7443/hiphop' },
  { key: 'phonk-1',    label: 'فونک موج ۱',           emoji: '🌑', url: 'https://radio.9craft.ir:7443/phonk' },
  { key: 'phonk-2',    label: 'فونک موج ۲',           emoji: '⚫', url: 'https://radio.9craft.ir:7443/phonk2' },
  { key: 'gym',        label: 'باشگاه ورزشی',         emoji: '💪', url: 'https://api.radiomusic.app/v3/BorodoRadio/radio_live.m3u8' },
  { key: 'soundtrack', label: 'ساندترک',               emoji: '🎬', url: 'https://radio.9craft.ir:7443/ost' },
  { key: 'soul',       label: 'موسیقی روح',           emoji: '✨', url: 'https://radio.9craft.ir:7443/soul' },
  { key: 'relax',      label: 'فارغ ز دنیا',           emoji: '🍃', url: 'https://radio.9craft.ir:7443/relax' },
];

var ALL_GENRES = GENRES_FA.concat(GENRES_EN);

// ── DOM refs ──────────────────────────────────────────────────────────────
var overlay      = document.getElementById('popup-overlay');
var btnGenre     = document.getElementById('btn-genre');
var btnClose     = document.getElementById('popup-close');
var btnPowerOff  = document.getElementById('btn-poweroff');
var genreGrid    = document.getElementById('genre-grid');
var popupTitle   = document.getElementById('popup-title');
var popupLangBar = document.getElementById('popup-lang-bar');

var _onGenreSelect = null;
var _onPowerOff    = null;
var activeKey      = null;
var activeLang     = null;

var COOKIE_GENRE = '24fm_genre';
var COOKIE_LANG  = '24fm_lang';
var COOKIE_HOURS = 24;

// ── Language Step ─────────────────────────────────────────────────────────
function buildLangStep() {
  if (popupTitle) popupTitle.textContent = 'سبک موسیقی را انتخاب کنید';

  if (popupLangBar) {
    popupLangBar.innerHTML = '';
    var q = document.createElement('p');
    q.className = 'lang-question';
    q.textContent = 'میخواهید سبک آهنگ‌های شما به چه زبانی باشد؟';
    popupLangBar.appendChild(q);
  }

  genreGrid.innerHTML = '';
  genreGrid.className = 'genre-grid lang-step';

  var langs = [
    { code: 'fa', flag: '🇮🇷', label: 'فارسی' },
    { code: 'en', flag: '🇬🇧', label: 'انگلیسی' },
  ];

  langs.forEach(function (lang, i) {
    var btn = document.createElement('button');
    btn.className = 'genre-item lang-item' + (lang.code === activeLang ? ' active' : '');
    btn.innerHTML =
      '<span class="emoji lang-flag">' + lang.flag + '</span>' +
      '<span class="lang-label">' + lang.label + '</span>';

    btn.addEventListener('click', function () {
      activeLang = lang.code;
      _setCookie(COOKIE_LANG, lang.code, COOKIE_HOURS);
      buildGenreStep();
    });

    requestAnimationFrame(function () {
      btn.style.animationDelay = (i * 0.08) + 's';
      btn.classList.add(i === 0 ? 'anim-from-right' : 'anim-from-left');
    });

    genreGrid.appendChild(btn);
  });
}

// ── Genre Step ────────────────────────────────────────────────────────────
function buildGenreStep() {
  if (popupTitle) popupTitle.textContent = 'سبک موسیقی را انتخاب کنید';

  // Language tabs
  if (popupLangBar) {
    popupLangBar.innerHTML = '';
    var tabs = document.createElement('div');
    tabs.className = 'lang-tabs';

    [
      { code: 'fa', flag: '🇮🇷', label: 'فارسی' },
      { code: 'en', flag: '🇬🇧', label: 'انگلیسی' },
    ].forEach(function (lang) {
      var tab = document.createElement('button');
      tab.className = 'lang-tab' + (lang.code === activeLang ? ' active' : '');
      tab.innerHTML = '<span>' + lang.flag + '</span><span>' + lang.label + '</span>';
      tab.addEventListener('click', function () {
        activeLang = lang.code;
        _setCookie(COOKIE_LANG, lang.code, COOKIE_HOURS);
        buildGenreStep();
      });
      tabs.appendChild(tab);
    });

    popupLangBar.appendChild(tabs);
  }

  // Genre items
  genreGrid.innerHTML = '';
  genreGrid.className = 'genre-grid';

  var genres = activeLang === 'en' ? GENRES_EN : GENRES_FA;

  genres.forEach(function (genre, i) {
    var btn = document.createElement('button');
    btn.className = 'genre-item' + (genre.key === activeKey ? ' active' : '');
    btn.dataset.key = genre.key;

    var emojiSpan = document.createElement('span');
    emojiSpan.className = 'emoji';
    emojiSpan.textContent = genre.emoji;

    var labelSpan = document.createElement('span');
    labelSpan.textContent = genre.label;

    btn.appendChild(emojiSpan);
    btn.appendChild(labelSpan);

    btn.addEventListener('click', function () { handleSelect(genre); });

    genreGrid.appendChild(btn);

    var col   = i % 2;
    var delay = Math.floor(i / 2) * 0.05;
    requestAnimationFrame(function () {
      btn.style.animationDelay = delay + 's';
      btn.classList.add(col === 0 ? 'anim-from-right' : 'anim-from-left');
    });
  });
}

// ── Open / Close ──────────────────────────────────────────────────────────
function openPopup() {
  var savedLang = _getCookie(COOKIE_LANG);
  if (savedLang) activeLang = savedLang;

  if (!activeLang) {
    buildLangStep();
  } else {
    buildGenreStep();
  }

  overlay.removeAttribute('aria-hidden');
  overlay.classList.add('open');
  document.body.classList.add('popup-open');
}

function closePopup() {
  overlay.classList.remove('open');
  overlay.setAttribute('aria-hidden', 'true');
  document.body.classList.remove('popup-open');
}

function handleSelect(genre) {
  activeKey = genre.key;
  closePopup();
  _setCookie(COOKIE_GENRE, genre.key, COOKIE_HOURS);
  if (typeof _onGenreSelect === 'function') _onGenreSelect(genre);
}

// ── Event listeners ───────────────────────────────────────────────────────
btnPowerOff.addEventListener('click', function () {
  activeKey  = null;
  activeLang = null;
  _removeCookie(COOKIE_GENRE);
  _removeCookie(COOKIE_LANG);
  closePopup();
  if (typeof _onPowerOff === 'function') _onPowerOff();
});

btnGenre.addEventListener('click', openPopup);
btnClose.addEventListener('click', closePopup);

overlay.addEventListener('click', function (e) {
  if (e.target === overlay) closePopup();
});

document.addEventListener('keydown', function (e) {
  if (e.key === 'Escape' && overlay.classList.contains('open')) closePopup();
});

// ── Public API ────────────────────────────────────────────────────────────
window.Popup = {
  open:          openPopup,
  close:         closePopup,
  setActiveKey:  function (key) { activeKey = key; },
  onGenreSelect: function (cb)  { _onGenreSelect = cb; },
  onPowerOff:    function (cb)  { _onPowerOff = cb; },
  getGenres:     function ()    { return ALL_GENRES; },
};
