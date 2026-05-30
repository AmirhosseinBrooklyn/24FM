'use strict';

const GENRES = [
  { key: 'party',      label: 'پارتی',             emoji: '🎉', url: 'https://radio.zaringo.sbs/v3/Party/radio_live.m3u8' },
  { key: 'dance',      label: 'بزن و رقص',          emoji: '💃', url: 'https://radio.zaringo.sbs/v3/Dance/radio_live.m3u8' },
  { key: 'remix',      label: 'ریمیکس',            emoji: '🔀', url: 'https://radio.zaringo.sbs/v3/mix/radio_live.m3u8' },
  { key: 'underground',label: 'زیرزمینی',           emoji: '🌑', url: 'https://radio.zaringo.sbs/v3/Zirzamin/radio_live.m3u8' },
  { key: 'rock',       label: 'راک و متال',         emoji: '🤘', url: 'https://radio.zaringo.sbs/v3/Rock/radio_live.m3u8' },
  { key: 'romantic',   label: 'عاشقانه',           emoji: '💗', url: 'https://radio.zaringo.sbs/v3/Romantic/radio_live.m3u8' },
  { key: 'couple',     label: 'دو نفره',            emoji: '👫', url: 'https://radio.zaringo.sbs/v3/Couple/radio_live.m3u8' },
  { key: 'alone',      label: 'تنهایی',            emoji: '🌙', url: 'https://radio.zaringo.sbs/v3/Alone/radio_live.m3u8' },
  { key: 'electronic', label: 'الکترونیک',         emoji: '⚡', url: 'https://radio.zaringo.sbs/v3/BorodoRadio/radio_live.m3u8' },
  { key: 'nostalgy',   label: 'نوستالژیک',         emoji: '📼', url: 'https://radio.zaringo.sbs/v3/Nostalogy/radio_live.m3u8' },
  { key: 'relax',      label: 'فارغ ز غوغای جهان', emoji: '🍃', url: 'https://radio.zaringo.sbs/v3/relax/radio_live.m3u8' },
];

const overlay     = document.getElementById('popup-overlay');
const btnGenre    = document.getElementById('btn-genre');
const btnClose    = document.getElementById('popup-close');
const btnPowerOff = document.getElementById('btn-poweroff');
const genreGrid   = document.getElementById('genre-grid');

let _onGenreSelect = null;
let _onPowerOff    = null;

let activeKey = null;

function buildGrid() {
  genreGrid.innerHTML = '';

  GENRES.forEach(function (genre, i) {
    const btn = document.createElement('button');
    btn.className = 'genre-item';
    btn.dataset.key = genre.key;

    if (genre.key === activeKey) btn.classList.add('active');

    const emojiSpan = document.createElement('span');
    emojiSpan.className = 'emoji';
    emojiSpan.textContent = genre.emoji;

    const labelSpan = document.createElement('span');
    labelSpan.textContent = genre.label;

    btn.appendChild(emojiSpan);
    btn.appendChild(labelSpan);

    btn.addEventListener('click', function () {
      handleSelect(genre);
    });

    genreGrid.appendChild(btn);

    const col       = i % 2;
    const rowDelay  = Math.floor(i / 2) * 0.1;
    const animClass = col === 0 ? 'anim-from-right' : 'anim-from-left';

    requestAnimationFrame(function () {
      btn.style.animationDelay = rowDelay + 's';
      btn.classList.add(animClass);
    });
  });
}

function openPopup() {
  buildGrid();
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
  try { localStorage.setItem('24fm_last_genre', genre.key); } catch (_) {}
  if (typeof _onGenreSelect === 'function') _onGenreSelect(genre);
}

btnPowerOff.addEventListener('click', function () {
  activeKey = null;
  try { localStorage.removeItem('24fm_last_genre'); } catch (_) {}
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

window.Popup = {
  open:          openPopup,
  close:         closePopup,
  setActiveKey:  function (key) { activeKey = key; },
  onGenreSelect: function (cb)  { _onGenreSelect = cb; },
  onPowerOff:    function (cb)  { _onPowerOff = cb; },
  getGenres:     function ()    { return GENRES; },
};
