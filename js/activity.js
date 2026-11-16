'use strict';
(function () {
  var client = crypto.randomUUID ? crypto.randomUUID() : Array.from(crypto.getRandomValues(new Uint8Array(16)), function (n) { return n.toString(16).padStart(2, '0'); }).join('');
  var sequence = 0, latest = { state: 'off', attemptId: '', genre: '' };
  var stopped = false, inFlight = false, dirty = false, timer, heartbeat, controller;
  var delay = 10000, count = 0, included = false;
  var number = new Intl.NumberFormat('fa-IR');
  var capsule = document.getElementById('listener-count');
  var value = document.getElementById('listener-count-value');
  function payload(closed) {
    return { client_id: client, sequence: ++sequence, source: 'web', closed: !!closed,
      state: window.__fm24PresenceOwnedNatively ? 'off' : latest.state,
      attempt_id: latest.attemptId || '', genre: latest.genre || '', genre_label: latest.label || '' };
  }
  function render() {
    // The acknowledgement identifies whether this page is already in the server total.
    var local = !window.__fm24PresenceOwnedNatively && latest.state !== 'off';
    value.textContent = number.format(Math.max(0, count + Number(local) - Number(included)));
  }
  function schedule() {
    clearTimeout(timer);
    if (!stopped && !document.hidden) timer = setTimeout(send, delay);
  }
  async function send() {
    if (stopped) return;
    if (inFlight) { dirty = true; return; }
    clearTimeout(timer);
    inFlight = true; dirty = false;
    controller = new AbortController();
    var active = controller, timeout;
    var data = payload(false);
    try {
      var request = Promise.resolve(window.Analytics && Analytics.ready).then(function () {
        if (active.signal.aborted) throw new Error('Activity timed out');
        return fetch('/api/live-users.php', { method: 'POST', credentials: 'same-origin', cache: 'no-store',
          headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data), signal: active.signal });
      }).then(function (r) { if (!r.ok) throw new Error('Presence unavailable'); return r.json(); });
      var result = await Promise.race([request, new Promise(function (_, reject) {
        timeout = setTimeout(function () { active.abort(); reject(new Error('Activity timed out')); }, 8000);
      })]);
      if (!Number.isInteger(result.listeners) || result.listeners < 0 || typeof result.self_listener !== 'boolean') throw new Error('Invalid listener count');
      if (stopped) return;
      count = result.listeners; included = result.self_listener;
      delay = 10000; capsule.classList.remove('unavailable'); render();
    } catch (_) {
      if (!stopped) { delay = Math.min(delay * 2, 60000); capsule.classList.add('unavailable'); render(); }
    } finally {
      clearTimeout(timeout); inFlight = false;
      if (dirty && !stopped) { dirty = false; send(); } else schedule();
    }
  }
  function changed(e) {
    latest = e.detail; render(); send();
  }
  function beacon(closed) {
    navigator.sendBeacon('/api/live-users.php', new Blob([JSON.stringify(payload(closed))], { type: 'application/json' }));
  }
  function visibility() {
    clearTimeout(timer); clearTimeout(heartbeat);
    if (!document.hidden) send();
    else hiddenHeartbeat();
  }
  function hiddenHeartbeat() {
    // No hidden-tab polling. Only keep genuinely playing sessions from expiring.
    if (stopped || !document.hidden) return;
    heartbeat = setTimeout(function () {
      if (!inFlight && latest.state === 'live') beacon(false);
      hiddenHeartbeat();
    }, 30000);
  }
  function hide() {
    stopped = true; dirty = false; clearTimeout(timer); clearTimeout(heartbeat);
    if (controller) controller.abort();
    beacon(true);
  }
  function show(e) { if (e.persisted) { stopped = false; visibility(); } }
  document.addEventListener('24fm:statechange', changed);
  document.addEventListener('visibilitychange', visibility);
  window.addEventListener('pagehide', hide);
  window.addEventListener('pageshow', show);
  window.addEventListener('online', send);
  if (window.Player) latest = Player.getState();
  render(); send();
  if (document.hidden) hiddenHeartbeat();
})();
