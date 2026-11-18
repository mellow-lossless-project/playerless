'use strict';

// What every other script uses: the saved state, the way out to the network, small helpers.

// A failure with a message for the on-screen log, not worth a retry.
class YtError extends Error {}

// Every file the page keeps, in Playerless.exe's data folder. Each is loaded once at start and
// written whole after every change, in order.
const Store = {
  settings: { quality: 320, accent: 'RED', volume: 1 },
  equalizer: { enabled: false, points: null },
  playlists: [],
  plays: [],
  downloads: [],
  youtube: {},
  writes: {},

  async load() {
    const names = ['settings', 'equalizer', 'playlists', 'plays', 'downloads', 'youtube'];
    const values = await Promise.all(names.map((name) =>
      fetch('/api/data/' + name).then((r) => (r.ok ? r.json() : null)).catch(() => null)));
    names.forEach((name, i) => {
      const value = values[i];
      if (value == null) return;
      Store[name] = Array.isArray(Store[name]) ? (Array.isArray(value) ? value : []) : { ...Store[name], ...value };
    });
  },

  // The text is taken now, so a later change can't slip into an earlier write.
  save(name) {
    const body = JSON.stringify(Store[name]);
    const previous = Store.writes[name] || Promise.resolve();
    Store.writes[name] = previous.then(() => fetch('/api/data/' + name, { method: 'PUT', body })).catch(() => {});
    return Store.writes[name];
  },
};

// The page can't call JioSaavn or YouTube itself: it may not set their headers nor read their
// answers. Playerless.exe makes the request and hands back the status and the body.
const Net = {
  async fetch(url, { method = 'GET', headers = {}, body = null } = {}) {
    const response = await fetch('/api/fetch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url, method, headers, body }),
    });
    const text = await response.text();
    if (!response.ok) throw new Error(text || 'Playerless.exe non risponde');
    return { status: Number(response.headers.get('X-Status')) || 0, text };
  },

  async text(url, options) {
    const { status, text } = await Net.fetch(url, options);
    if (status === 0) throw new Error(text || 'Errore di rete');
    if (status < 200 || status > 299) throw new Error('HTTP ' + status);
    return text;
  },

  async json(url, options) {
    return JSON.parse(await Net.text(url, options));
  },

  // Audio from the web goes through Playerless.exe too, so the equalizer may process it.
  stream(url) {
    return '/api/stream?u=' + encodeURIComponent(url);
  },
};

// YouTube's player script and the solver's prepared version of it.
const Cache = {
  async get(name) {
    const response = await fetch('/api/cache/' + name).catch(() => null);
    return response && response.ok ? response.text() : null;
  },

  async has(name) {
    const response = await fetch('/api/cache/' + name, { method: 'HEAD' }).catch(() => null);
    return !!response && response.ok;
  },

  put(name, text) {
    return fetch('/api/cache/' + name, { method: 'PUT', body: text }).catch(() => {});
  },

  clear() {
    return fetch('/api/cache/', { method: 'DELETE' }).catch(() => {});
  },
};

// Ids come from the APIs; as file names they may only keep letters, digits, _ and -. The phone
// names its files the same way, so imported ones land where this expects them.
function safeName(id) {
  return id.replace(/[^A-Za-z0-9_-]/g, '_');
}

// Titles from JioSaavn arrive HTML-escaped, e.g. &quot;.
function unescapeHtml(text) {
  if (!text.includes('&')) return text;
  return new DOMParser().parseFromString(text, 'text/html').documentElement.textContent;
}

// Runs one call at a time per lock, like the phone's @Synchronized.
function serialized() {
  let last = Promise.resolve();
  return (work) => {
    const next = last.catch(() => {}).then(work);
    last = next;
    return next;
  };
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

const italian = new Intl.NumberFormat('it-IT', { minimumFractionDigits: 1, maximumFractionDigits: 1 });

function oneDecimal(value) {
  return italian.format(value);
}

function signedDecimal(value) {
  return (value >= 0 ? '+' : '') + italian.format(value);
}

function clock(seconds) {
  if (!isFinite(seconds) || seconds < 0) return '0:00';
  const s = Math.floor(seconds);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const rest = String(s % 60).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${rest}` : `${m}:${rest}`;
}
