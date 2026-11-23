'use strict';

// Playerless per PC: the phone app's screens and player, in a page. Ported from MainActivity,
// PlaybackService, PlayerScreen, Downloads, Playlists, PlayStats and LoadLog.

const ACCENTS = [
  ['RED', 'Rosso', '#FF0000'], ['ORANGE', 'Arancione', '#FF6D00'], ['YELLOW', 'Giallo', '#FFD600'],
  ['GREEN', 'Verde', '#00E676'], ['CYAN', 'Azzurro', '#00B0FF'], ['BLUE', 'Blu', '#2979FF'],
  ['PURPLE', 'Viola', '#D500F9'], ['PINK', 'Rosa', '#FF4081'], ['WHITE', 'Bianco', '#FFFFFF'],
];
const QUALITIES = [320, 160, 96];
// YouTube's AAC stream, itag 140.
const YOUTUBE_KBPS = 128;
// The phone shows 2; a desktop row has room for more.
const MOST_PLAYED_COUNT = 6;
// A song counts once 30 s of it were heard, so skipping through a queue doesn't inflate the stats.
const LISTENED_S = 30;
const MAX_PLAYS = 400;
const LOG_DELAY_MS = 300;
const LOG_TICK_MS = 100;
const LOG_LINES = 8;
const NON_WORD = /[^\p{L}\p{N}]+/gu;
const MARKS = /\p{M}+/gu;
const APOSTROPHES = /['’‘`´]/g;
// A guest in a title, "(con JUL)" or "(feat. Drake)": see matchKey.
const FEATURING = /\s*[(\[](?:con|feat\.?|ft\.?|with|featuring)\s[^)\]]*[)\]]/gi;

// The phone's vector icons.
const ICONS = {
  home: ['0 0 32 32',
    'M23,29H10.08C7.06,29,6,27.45,6,26V18H5.68a2.68,2.68,0,0,1-2-4.45l7.18-8.2a6.86,6.86,0,0,1,10.3,0l7.18,8.2a2.63,2.63,0,0,1,.43,2.87A2.66,2.66,0,0,1,26.32,18H26v8A3,3,0,0,1,23,29ZM16,5a4.86,4.86,0,0,0-3.65,1.65l-7.18,8.2A.68.68,0,0,0,5.68,16H7a1,1,0,0,1,1,1v9c0,.87,1.31,1,2.09,1H23a1,1,0,0,0,1-1V17a1,1,0,0,1,.3-.71A1,1,0,0,1,25,16h1.31a.68.68,0,0,0,.51-1.13l-7.18-8.2A4.85,4.85,0,0,0,16,5Z',
    'M19,29H13a1,1,0,0,1-1-1V21a4,4,0,0,1,8,0v7A1,1,0,0,1,19,29Zm-5-2h4V21a2,2,0,0,0-4,0Z'],
  download: ['0 0 51.619 51.619',
    'M26.31,0c-1.104,0-2,0.896-2,2v32.844l-8.523-8.771c-0.781-0.781-1.922-0.781-2.704,0c-0.781,0.781-0.718,2.048,0.063,2.828l12.924,12.89c0.375,0.375,0.9,0.586,1.429,0.586c0.531,0,1.048-0.211,1.423-0.586l12.894-12.893c0.781-0.781,0.783-2.048,0.002-2.828c-0.781-0.781-2.297-0.781-3.078,0L28.31,36.254V2C28.31,0.895,27.414,0,26.31,0z',
    'M48.31,47.326V41c0-1.104-0.896-2-2-2s-2,0.896-2,2v6.326c0,0.319,0.461,0.293,0.424,0.293H8.548c-0.342,0-1.239-0.027-1.239-0.293V41c0-1.104-0.896-2-2-2s-2,0.896-2,2v6.326c0,2.49,2.714,4.293,5.239,4.293h36.349C46.845,51.619,48.31,50.555,48.31,47.326z'],
  settings: ['0 0 32 32',
    'M27.526,18.036L27,17.732c-0.626-0.361-1-1.009-1-1.732s0.374-1.371,1-1.732l0.526-0.304c1.436-0.83,1.927-2.662,1.098-4.098l-1-1.732c-0.827-1.433-2.666-1.925-4.098-1.098L23,7.339c-0.626,0.362-1.375,0.362-2,0c-0.626-0.362-1-1.009-1-1.732V5c0-1.654-1.346-3-3-3h-2c-1.654,0-3,1.346-3,3v0.608c0,0.723-0.374,1.37-1,1.732c-0.626,0.361-1.374,0.362-2,0L8.474,7.036C7.042,6.209,5.203,6.701,4.375,8.134l-1,1.732c-0.829,1.436-0.338,3.269,1.098,4.098L5,14.268C5.626,14.629,6,15.277,6,16s-0.374,1.371-1,1.732l-0.526,0.304c-1.436,0.829-1.927,2.662-1.098,4.098l1,1.732c0.828,1.433,2.667,1.925,4.098,1.098L9,24.661c0.626-0.363,1.374-0.361,2,0c0.626,0.362,1,1.009,1,1.732V27c0,1.654,1.346,3,3,3h2c1.654,0,3-1.346,3-3v-0.608c0-0.723,0.374-1.37,1-1.732c0.625-0.361,1.374-0.362,2,0l0.526,0.304c1.432,0.826,3.271,0.334,4.098-1.098l1-1.732C29.453,20.698,28.962,18.865,27.526,18.036z M16,21c-2.757,0-5-2.243-5-5s2.243-5,5-5s5,2.243,5,5S18.757,21,16,21z'],
  search: ['0 0 32 32',
    'M27.414,24.586l-5.077-5.077C23.386,17.928,24,16.035,24,14c0-5.514-4.486-10-10-10S4,8.486,4,14s4.486,10,10,10c2.035,0,3.928-0.614,5.509-1.663l5.077,5.077c0.78,0.781,2.048,0.781,2.828,0C28.195,26.633,28.195,25.367,27.414,24.586z M7,14c0-3.86,3.14-7,7-7s7,3.14,7,7s-3.14,7-7,7S7,17.86,7,14z'],
  play: ['0 0 24 24', 'M8,5v14l11,-7z'],
  pause: ['0 0 24 24', 'M6,19h4V5H6v14zM14,5v14h4V5h-4z'],
  prev: ['0 0 24 24', 'M6,6h2v12H6zM9.5,12l8.5,6V6z'],
  next: ['0 0 24 24', 'M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z'],
  shuffle: ['0 0 24 24', 'M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 20,9.5V4h-5.5zM14.83,13.41l-1.41,1.41 3.13,3.13L14.5,20H20v-5.5l-2.04,2.04 -3.13,-3.13z'],
  delete: ['0 0 24 24', 'M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z'],
  done: ['0 0 24 24', 'M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z'],
  pending: ['0 0 24 24', 'M6,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM18,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z'],
  volume: ['0 0 24 24', 'M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z'],
  close: ['0 0 24 24', 'M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z'],
  left: ['0 0 24 24', 'M15.41,7.41L14,6l-6,6 6,6 1.41,-1.41L10.83,12z'],
  right: ['0 0 24 24', 'M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z'],
  visuals: ['0 0 24 24', 'M19,9l1.25,-2.75L23,5l-2.75,-1.25L19,1l-1.25,2.75L15,5l2.75,1.25L19,9zM11.5,9.5L9,4 6.5,9.5 1,12l5.5,2.5L9,20l2.5,-5.5L17,12l-5.5,-2.5zM19,15l-1.25,2.75L15,19l2.75,1.25L19,23l1.25,-2.75L23,19l-2.75,-1.25L19,15z'],
  fullscreen: ['0 0 24 24', 'M7,14H5v5h5v-2H7v-3zM5,10h2V7h3V5H5v5zM17,17h-3v2h5v-5h-2v3zM14,5v2h3v3h2V5h-5z'],
  fullscreenExit: ['0 0 24 24', 'M5,16h3v3h2v-5H5v2zM8,8H5v2h5V5H8v3zM14,19h2v-3h3v-2h-5v5zM16,8V5h-2v5h5V8h-3z'],
};

function icon(name) {
  const [box, ...paths] = ICONS[name];
  return `<svg viewBox="${box}" aria-hidden="true">${paths.map((d) => `<path d="${d}"/>`).join('')}</svg>`;
}

const $ = (id) => document.getElementById(id);

// Builds an element. Text always goes in as text; "html" is only ever our own icons.
function h(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value == null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key === 'html') node.innerHTML = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children) if (child != null) node.append(child);
  return node;
}

function accentColor() {
  return (ACCENTS.find((a) => a[0] === Store.settings.accent) || ACCENTS[0])[2];
}

function applyAccent() {
  document.documentElement.style.setProperty('--accent', accentColor());
}

let toastTimer = 0;

function toast(text, long = false) {
  const node = $('toast');
  node.textContent = text;
  node.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { node.hidden = true; }, long ? 3500 : 2000);
}

// --- Covers -----------------------------------------------------------------------------------

const Covers = {
  // Search returns 150x150 covers, too small for a tile. JioSaavn names the size in the file,
  // YouTube after an "=".
  big(url) {
    return url.replace('150x150', '500x500').replace(/=w\d+-h\d+/, '=w500-h500');
  },

  // A downloaded song's own cover first, the online one if that's missing.
  set(img, song, big) {
    const online = song && song.image ? (big ? Covers.big(song.image) : song.image) : '';
    const local = song && Downloads.isDownloaded(song) ? Downloads.coverUrl(song) : '';
    const first = local || online;
    img.onerror = local && online ? () => { img.onerror = null; img.src = online; } : null;
    if (!first) img.removeAttribute('src');
    else if (img.getAttribute('src') !== first) img.src = first;
  },
};

// --- Downloads: songs on disk, in Playerless.exe's music folder, listed newest first ------------

const Downloads = {
  inProgress: new Set(),
  ids: new Set(),

  changed() {
    Downloads.ids = new Set(Store.downloads.map((s) => s.id));
  },

  isDownloaded(song) { return Downloads.ids.has(song.id); },
  isDownloading(song) { return Downloads.inProgress.has(song.id); },
  audioUrl(song) { return '/music/' + safeName(song.id) + '.m4a'; },
  coverUrl(song) { return '/music/' + safeName(song.id) + '.jpg'; },

  // Every word of the query has to appear in the title or the artist, matched as the online search does.
  search(query) {
    const words = queryWords(query);
    return Store.downloads.filter((song) => matchesAll(words, song));
  },

  // A null bitrate keeps the URL as it is, for streams that don't name theirs.
  async download(song, kbps) {
    if (Downloads.inProgress.has(song.id)) return;
    Downloads.inProgress.add(song.id);
    refresh();
    try {
      // A YouTube song has one rendition, the one it streams.
      const youtube = YouTube.isYouTube(song);
      const url = youtube ? await YtStream.resolve(song.url.slice(YouTube.PREFIX.length), () => {})
        : kbps != null ? Saavn.withQuality(song.url, kbps) : song.url;
      const response = await fetch('/api/download', {
        method: 'POST',
        body: JSON.stringify({ name: safeName(song.id), url, ranged: youtube, cover: song.image ? Covers.big(song.image) : null }),
      });
      if (!response.ok) throw new Error(await response.text());
      // Keeps the online URL, so the song still plays anywhere it's saved if the file is deleted.
      const saved = { id: song.id, title: song.title, artist: song.artist, image: song.image, url: song.url };
      const bitrate = youtube ? YOUTUBE_KBPS : kbps;
      if (bitrate != null) saved.kbps = bitrate;
      Store.downloads = [saved, ...Store.downloads.filter((s) => s.id !== song.id)];
      Downloads.changed();
      Store.save('downloads');
      toast(`Scaricato: ${song.title}`);
    } catch (e) {
      toast(`Download fallito: ${song.title}`);
      fetch('/api/log', { method: 'POST', body: `Download di ${song.id}: ${e.message}` }).catch(() => {});
    } finally {
      Downloads.inProgress.delete(song.id);
      refresh();
    }
  },

  async remove(song) {
    Store.downloads = Store.downloads.filter((s) => s.id !== song.id);
    Downloads.changed();
    Store.save('downloads');
    await fetch('/api/download?name=' + encodeURIComponent(safeName(song.id)), { method: 'DELETE' }).catch(() => {});
    refresh();
  },
};

// --- Playlists and stats ----------------------------------------------------------------------

function plainSong(song) {
  const copy = { id: song.id, title: song.title, artist: song.artist, image: song.image || '', url: song.url };
  if (song.kbps) copy.kbps = song.kbps;
  return copy;
}

const Playlists = {
  get(id) { return Store.playlists.find((p) => p.id === id) || null; },

  create(name) {
    const playlist = { id: crypto.randomUUID(), name, songs: [] };
    Store.playlists = [...Store.playlists, playlist];
    Store.save('playlists');
    return playlist;
  },

  rename(id, name) { Playlists.update(id, (p) => ({ ...p, name })); },

  delete(id) {
    Store.playlists = Store.playlists.filter((p) => p.id !== id);
    Store.save('playlists');
  },

  // False when the song was already in the playlist.
  add(id, song) {
    const playlist = Playlists.get(id);
    if (!playlist || playlist.songs.some((s) => s.id === song.id)) return false;
    Playlists.update(id, (p) => ({ ...p, songs: [...p.songs, plainSong(song)] }));
    return true;
  },

  remove(id, song) { Playlists.update(id, (p) => ({ ...p, songs: p.songs.filter((s) => s.id !== song.id) })); },

  update(id, change) {
    Store.playlists = Store.playlists.map((p) => (p.id === id ? change(p) : p));
    Store.save('playlists');
  },
};

// How many times each song was listened to, newest first.
const PlayStats = {
  // Sorting keeps ties in order, so the newest wins.
  top(count) {
    return [...Store.plays].sort((a, b) => b.plays - a.plays).slice(0, count);
  },

  record(song) {
    const old = Store.plays.find((e) => e.id === song.id);
    let entries = [{ ...plainSong(song), plays: (old ? old.plays : 0) + 1 }, ...Store.plays.filter((e) => e.id !== song.id)];
    if (entries.length > MAX_PLAYS) entries = [...entries].sort((a, b) => b.plays - a.plays).slice(0, MAX_PLAYS / 2);
    Store.plays = entries;
    Store.save('plays');
    if (screen === 'HOME') refresh();
  },
};

// --- The loading log --------------------------------------------------------------------------

// What happens between pressing play and hearing the song.
const LoadLog = {
  FAILURE_MS: 4000,
  id: null,
  startedAt: 0,
  entries: [],
  failure: null,
  failedUntil: 0,
  title: '',
  loading: false,

  // A failure stays up a few seconds, also over the next song the player skips to.
  get visible() { return LoadLog.loading || performance.now() < LoadLog.failedUntil; },
  get elapsedMs() { return performance.now() - LoadLog.startedAt; },

  add(id, text) {
    const at = performance.now();
    LoadLog.switchTo(id, at);
    LoadLog.entries.push(LoadLog.line(at - LoadLog.startedAt, text));
    renderLog();
  },

  // A song is loading from the moment it should play until it first does.
  update(id, title, loading) {
    const switched = id != null && LoadLog.switchTo(id, performance.now());
    if (!switched && loading === LoadLog.loading && title === LoadLog.title) return;
    LoadLog.loading = loading;
    LoadLog.title = title;
    renderLog();
  },

  fail(text) {
    LoadLog.failure = LoadLog.line(LoadLog.elapsedMs, `Non riproducibile: ${LoadLog.title} (${text})`);
    LoadLog.entries.push(LoadLog.failure);
    LoadLog.loading = false;
    LoadLog.failedUntil = performance.now() + LoadLog.FAILURE_MS;
    setTimeout(renderLog, LoadLog.FAILURE_MS + 10);
    renderLog();
  },

  switchTo(id, at) {
    if (id === LoadLog.id) return false;
    LoadLog.id = id;
    LoadLog.startedAt = at;
    LoadLog.entries = LoadLog.failure && performance.now() < LoadLog.failedUntil ? [LoadLog.failure] : [];
    return true;
  },

  line(ms, text) {
    return `${oneDecimal(ms / 1000).padStart(4)} s  ${text}`;
  },
};

let logTimer = 0;

// Shown only past a short wait, so songs that start at once never flash it.
function renderLog() {
  const node = $('loadLog');
  clearTimeout(logTimer);
  // The clock keeps moving while it loads: proof the app isn't stuck.
  if (LoadLog.loading) logTimer = setTimeout(renderLog, LOG_TICK_MS);
  if (!LoadLog.visible || (LoadLog.loading && LoadLog.elapsedMs < LOG_DELAY_MS)) {
    node.hidden = true;
    return;
  }
  node.replaceChildren();
  if (LoadLog.loading) node.append(h('span', { class: 'now-line', text: `${LoadLog.title} · ${oneDecimal(LoadLog.elapsedMs / 1000)} s` }));
  const lines = LoadLog.entries.slice(-LOG_LINES);
  lines.forEach((line, i) => {
    if (node.childNodes.length) node.append('\n');
    // A failure in the accent color; otherwise the newest step stands out from the ones done.
    if (line === LoadLog.failure) node.append(h('span', { class: 'failure', text: line }));
    else node.append(i === lines.length - 1 ? h('span', { class: 'latest', text: line }) : line);
  });
  node.hidden = false;
}

// --- The player -------------------------------------------------------------------------------

const audio = $('audio');

const Player = {
  queue: [],
  index: -1,
  // Each song started gets a new number; anything still busy with an older one gives up.
  token: 0,
  // Whether the user wants sound: play pressed and not paused since.
  wantPlay: false,
  started: false,
  counted: false,
  error: null,

  current() { return Player.queue[Player.index] || null; },
  hasNext() { return Player.index + 1 < Player.queue.length; },

  start(queue, index) {
    Player.queue = queue;
    Player.playAt(index);
  },

  async playAt(index) {
    const song = Player.queue[index];
    if (!song) return;
    const token = ++Player.token;
    Object.assign(Player, { index, wantPlay: true, started: false, counted: false, error: null });
    audio.pause();
    audio.removeAttribute('src');
    audio.load();
    EqAudio.attach(audio);
    Player.changed(true);

    const log = (text) => { if (token === Player.token) LoadLog.add(song.id, text); };
    try {
      let src;
      if (Downloads.isDownloaded(song)) {
        log('Apro il brano scaricato');
        src = Downloads.audioUrl(song);
      } else if (YouTube.isYouTube(song)) {
        // The stream URL is found the moment it's needed.
        const url = await YtStream.resolve(song.url.slice(YouTube.PREFIX.length), log);
        if (token !== Player.token) return;
        log("Link pronto, carico l'audio");
        src = Net.stream(url);
      } else {
        log("Carico l'audio da JioSaavn");
        src = Net.stream(Saavn.withQuality(song.url, Store.settings.quality));
      }
      if (token !== Player.token) return;
      audio.src = src;
      if (Player.wantPlay) await audio.play();
    } catch (e) {
      if (token !== Player.token || (e && e.name === 'AbortError')) return;
      Player.fail(e && e.message ? e.message : String(e));
    }
  },

  // A song that can't load (offline and not downloaded) shouldn't end the whole queue.
  fail(message) {
    const song = Player.current();
    if (!song) return;
    if (YouTube.isYouTube(song)) YtStream.forget(song.url.slice(YouTube.PREFIX.length));
    Player.error = message;
    LoadLog.fail(message);
    Player.changed(false);
    if (Player.hasNext()) Player.playAt(Player.index + 1);
  },

  // Also restarts after an error and at the end of the queue.
  toggle() {
    if (!Player.current()) return;
    if (Player.error) {
      Player.playAt(Player.index);
    } else if (!audio.getAttribute('src')) {
      Player.wantPlay = !Player.wantPlay;
      Player.changed(false);
    } else if (audio.paused || audio.ended) {
      Player.wantPlay = true;
      EqAudio.attach(audio);
      audio.play().catch(() => {});
    } else {
      Player.wantPlay = false;
      audio.pause();
    }
  },

  // Back to the start of the song, or to the one before if it only just started.
  previous() {
    if (!Player.current()) return;
    if (Player.index > 0 && (audio.currentTime <= 3 || Player.error)) Player.playAt(Player.index - 1);
    else audio.currentTime = 0;
  },

  next() {
    if (Player.hasNext()) Player.playAt(Player.index + 1);
  },

  seek(seconds) {
    if (audio.getAttribute('src') && isFinite(seconds)) audio.currentTime = seconds;
  },

  showsPlay() {
    return !Player.wantPlay || !!Player.error || audio.ended;
  },

  loading() {
    return !!Player.current() && !Player.started && Player.wantPlay && !Player.error;
  },

  changed(newSong) {
    const song = Player.current();
    LoadLog.update(song ? song.id : null, song ? song.title : '', Player.loading());
    renderPlayer(newSong);
  },
};

audio.addEventListener('playing', () => {
  Player.started = true;
  Player.wantPlay = true;
  Player.changed(false);
  // The next YouTube song gets its link now, so it starts at once.
  const next = Player.queue[Player.index + 1];
  if (next && YouTube.isYouTube(next) && !Downloads.isDownloaded(next)) {
    YtStream.resolve(next.url.slice(YouTube.PREFIX.length), () => {}).catch(() => {});
  }
});
audio.addEventListener('pause', () => Player.changed(false));
audio.addEventListener('play', () => Player.changed(false));
audio.addEventListener('durationchange', renderProgress);
audio.addEventListener('timeupdate', () => {
  renderProgress();
  const song = Player.current();
  if (song && !Player.counted && audio.currentTime >= LISTENED_S) {
    Player.counted = true;
    PlayStats.record(song);
  }
});
audio.addEventListener('ended', () => {
  if (Player.hasNext()) Player.playAt(Player.index + 1);
  else Player.changed(false);
});
audio.addEventListener('error', () => {
  if (!audio.getAttribute('src') || !Player.current()) return;
  const error = audio.error;
  Player.fail(error && error.message ? error.message : 'Brano non riproducibile');
});

// Offline the queue is what's on disk, so skipping never waits on network timeouts.
function playable(songs) {
  return navigator.onLine ? songs : songs.filter((s) => Downloads.isDownloaded(s));
}

function play(songs, position) {
  const queue = playable(songs);
  const start = queue.indexOf(songs[position]);
  if (start < 0) {
    toast('Senza rete: brano non scaricato');
    return;
  }
  Player.start(queue, start);
}

function playAll(songs) {
  const queue = playable(songs);
  if (queue.length === 0) {
    toast('Senza rete: brano non scaricato');
    return;
  }
  Player.start(queue, 0);
}

// --- The player bar, the full-screen player, media keys ---------------------------------------

let nowOpen = false;
let seeking = false;

function renderPlayer(newSong) {
  const song = Player.current();
  $('bar').hidden = !song;
  if (!song) {
    document.title = 'Playerless';
    closeNow();
    Visuals.close();
    return;
  }
  $('barTitle').textContent = song.title;
  $('barArtist').textContent = Player.error ? 'Brano non riproducibile' : song.artist;
  Covers.set($('barCover'), song, false);
  const button = Player.showsPlay() ? 'play' : 'pause';
  $('playPause').innerHTML = icon(button);
  $('nowPlayPause').innerHTML = icon(button);
  $('prev').disabled = false;
  $('next').disabled = !Player.hasNext();
  document.title = `${song.title} · ${song.artist}`;
  if (nowOpen) {
    $('nowTitle').textContent = song.title;
    Covers.set($('nowCover'), song, true);
  }
  if (Visuals.shown) Visuals.describe(song, button, newSong);
  if (newSong) {
    markPlaying();
    updateMediaSession(song);
  }
  if ('mediaSession' in navigator) navigator.mediaSession.playbackState = Player.showsPlay() ? 'paused' : 'playing';
  renderProgress();
}

function renderProgress() {
  const duration = isFinite(audio.duration) ? audio.duration : 0;
  const position = audio.getAttribute('src') ? audio.currentTime : 0;
  for (const [range, elapsed, total] of [['progress', 'elapsed', 'duration'], ['nowProgress', 'nowElapsed', 'nowDuration']]) {
    $(range).max = duration;
    if (!seeking) {
      $(range).value = position;
      $(elapsed).textContent = clock(position);
    }
    $(total).textContent = clock(duration);
  }
  if ('mediaSession' in navigator && duration > 0) {
    try {
      navigator.mediaSession.setPositionState({ duration, position: Math.min(position, duration), playbackRate: 1 });
    } catch (e) { /* Some states aren't accepted mid-load. */ }
  }
}

function markPlaying() {
  const song = Player.current();
  for (const row of document.querySelectorAll('.song')) {
    row.classList.toggle('playing', !!song && row.dataset.id === song.id);
  }
}

function updateMediaSession(song) {
  if (!('mediaSession' in navigator)) return;
  const image = Downloads.isDownloaded(song) ? Downloads.coverUrl(song) : Covers.big(song.image || '');
  navigator.mediaSession.metadata = new MediaMetadata({
    title: song.title,
    artist: song.artist,
    artwork: image ? [{ src: new URL(image, location.href).href, sizes: '500x500' }] : [],
  });
}

function openNow() {
  if (!Player.current()) return;
  nowOpen = true;
  $('now').hidden = false;
  renderPlayer(false);
}

function closeNow() {
  nowOpen = false;
  $('now').hidden = true;
}

function setVolume(value, save) {
  Store.settings.volume = value;
  $('volume').value = value;
  $('nowVolume').value = value;
  // Through the equalizer's gain once it exists; before, the element's own volume.
  if (EqAudio.context) {
    audio.volume = 1;
    EqAudio.apply();
  } else {
    audio.volume = value * value;
  }
  if (save) Store.save('settings');
}

function setUpPlayerControls() {
  for (const [id, name] of [['prev', 'prev'], ['next', 'next'], ['nowPrev', 'prev'], ['nowNext', 'next'], ['nowClose', 'close']]) {
    $(id).innerHTML = icon(name);
  }
  $('volumeIcon').innerHTML = icon('volume');
  for (const id of ['barVisuals', 'nowVisuals']) {
    $(id).innerHTML = icon('visuals');
    $(id).addEventListener('click', () => Visuals.open());
  }
  $('prev').addEventListener('click', () => Player.previous());
  $('next').addEventListener('click', () => Player.next());
  $('playPause').addEventListener('click', () => Player.toggle());
  $('nowPrev').addEventListener('click', () => Player.previous());
  $('nowNext').addEventListener('click', () => Player.next());
  $('nowPlayPause').addEventListener('click', () => Player.toggle());
  $('barSong').addEventListener('click', openNow);
  $('nowClose').addEventListener('click', closeNow);

  for (const id of ['progress', 'nowProgress']) {
    const range = $(id);
    range.addEventListener('input', () => {
      seeking = true;
      $(id === 'progress' ? 'elapsed' : 'nowElapsed').textContent = clock(Number(range.value));
    });
    range.addEventListener('change', () => {
      seeking = false;
      Player.seek(Number(range.value));
    });
  }
  for (const id of ['volume', 'nowVolume']) {
    $(id).addEventListener('input', () => setVolume(Number($(id).value), false));
    $(id).addEventListener('change', () => setVolume(Number($(id).value), true));
  }
  setVolume(Store.settings.volume == null ? 1 : Store.settings.volume, false);

  // The keyboard's media keys and Windows' media overlay.
  if ('mediaSession' in navigator) {
    const session = navigator.mediaSession;
    const handlers = {
      play: () => { if (Player.showsPlay()) Player.toggle(); },
      pause: () => { if (!Player.showsPlay()) Player.toggle(); },
      previoustrack: () => Player.previous(),
      nexttrack: () => Player.next(),
      seekto: (details) => Player.seek(details.seekTime),
    };
    for (const [action, handler] of Object.entries(handlers)) {
      try { session.setActionHandler(action, handler); } catch (e) { /* Not supported here. */ }
    }
  }
}

// --- Screens ----------------------------------------------------------------------------------

let screen = 'HOME';
let playlistId = null;
let searchResults = [];
let searchStatus = null;
let searchId = 0;
let eqScreen = null;
const content = $('content');

function showScreen(target) {
  screen = target;
  refresh(false);
  content.scrollTop = 0;
}

// Rebuilds the screen. The scroll stays where it was, unless it's a new screen.
function refresh(keepScroll = true) {
  const scroll = content.scrollTop;
  const eq = screen === 'EQUALIZER';
  if (!eq) content.replaceChildren();
  switch (screen) {
    case 'HOME':
      showHeader(null, null);
      content.append(homeGrid());
      break;
    case 'SETTINGS':
      showHeader('Impostazioni', null);
      content.append(settingsRows());
      break;
    case 'EQUALIZER':
      showEqualizer();
      break;
    case 'SEARCH':
      showHeader(null, searchStatus);
      content.append(songList(searchResults));
      break;
    case 'DOWNLOADS': {
      const songs = Store.downloads;
      showHeader('Scaricati', songs.length ? null : 'Nessun brano scaricato');
      content.append(songList(songs));
      break;
    }
    case 'PLAYLIST': {
      const playlist = Playlists.get(playlistId);
      if (!playlist) {
        showScreen('HOME');
        return;
      }
      showHeader(playlist.name, playlist.songs.length ? null : 'Clic destro su un brano per aggiungerlo qui');
      content.append(songList(playlist.songs));
      break;
    }
  }
  if (keepScroll) content.scrollTop = scroll;
  for (const [id, screens] of [['navHome', ['HOME', 'PLAYLIST', 'SEARCH']], ['navDownloads', ['DOWNLOADS']], ['navSettings', ['SETTINGS', 'EQUALIZER']]]) {
    $(id).classList.toggle('active', screens.includes(screen));
  }
}

function showHeader(title, message) {
  $('titleRow').hidden = title == null;
  $('listTitle').textContent = title || '';
  const canPlayAll = screen === 'PLAYLIST' && message == null;
  $('playAll').hidden = !canPlayAll;
  $('shuffleAll').hidden = !canPlayAll;
  $('status').hidden = message == null;
  $('status').textContent = message || '';
}

// Built the first time, and kept: the curve stays as it was.
function showEqualizer() {
  if (!eqScreen) eqScreen = new EqScreen(h('div', { class: 'eq' }));
  content.replaceChildren(eqScreen.root);
  eqScreen.graph.draw();
  showHeader('Equalizzatore', null);
}

function shownSongs() {
  if (screen === 'SEARCH') return searchResults;
  if (screen === 'DOWNLOADS') return Store.downloads;
  if (screen === 'PLAYLIST') return (Playlists.get(playlistId) || { songs: [] }).songs;
  return [];
}

function songList(songs) {
  const current = Player.current();
  const list = h('div', { class: 'songs' });
  songs.forEach((song, i) => {
    const cover = h('img', { class: 'cover', alt: '', loading: 'lazy', decoding: 'async' });
    Covers.set(cover, song, false);
    const action = actionFor(song);
    list.append(h('div', { class: 'song' + (current && current.id === song.id ? ' playing' : ''), 'data-index': i, 'data-id': song.id },
      cover,
      h('div', { class: 'text' }, h('span', { class: 'title', text: song.title }), h('span', { class: 'artist', text: subtitleOf(song) })),
      h('button', { class: 'action', title: action.title, 'aria-label': action.title, html: icon(action.icon) })));
  });
  list.addEventListener('click', (e) => {
    const row = e.target.closest('.song');
    if (!row) return;
    const song = songs[Number(row.dataset.index)];
    if (e.target.closest('.action')) onSongAction(song);
    else play(songs, Number(row.dataset.index));
  });
  // The phone's long press.
  list.addEventListener('contextmenu', (e) => {
    const row = e.target.closest('.song');
    if (!row) return;
    e.preventDefault();
    showSongMenu(songs[Number(row.dataset.index)]);
  });
  return list;
}

function actionFor(song) {
  if (screen === 'DOWNLOADS') return { icon: 'delete', title: 'Elimina' };
  if (Downloads.isDownloading(song)) return { icon: 'pending', title: 'Download in corso' };
  if (Downloads.isDownloaded(song)) return { icon: 'done', title: 'Scaricato' };
  return { icon: 'download', title: 'Scarica' };
}

function subtitleOf(song) {
  if (screen !== 'DOWNLOADS') return YouTube.isYouTube(song) ? `${song.artist} · YouTube` : song.artist;
  const kbps = song.kbps || Saavn.kbpsOf(song.url);
  return kbps ? `${song.artist} · ${kbps} kbps` : song.artist;
}

async function onSongAction(song) {
  if (screen === 'DOWNLOADS') {
    if (await Dialog.confirm(`Eliminare ${song.title}?`, 'Elimina')) Downloads.remove(song);
  } else if (!Downloads.isDownloaded(song) && !Downloads.isDownloading(song)) {
    chooseDownloadQuality(song);
  }
}

async function chooseDownloadQuality(song) {
  const best = Saavn.kbpsOf(song.url);
  const qualities = QUALITIES.filter((q) => best != null && q <= best);
  // Nothing to choose: one rendition, or a stream that doesn't name its bitrate.
  if (qualities.length < 2) {
    Downloads.download(song, qualities.length ? qualities[0] : null);
    return;
  }
  const which = await Dialog.choose('Scarica in', qualities.map((q) => `${q} kbps`));
  if (which >= 0) Downloads.download(song, qualities[which]);
}

async function showSongMenu(song) {
  const openPlaylist = screen === 'PLAYLIST' ? playlistId : null;
  const items = ['Aggiungi a playlist'];
  if (openPlaylist) items.push('Rimuovi dalla playlist');
  const which = await Dialog.choose(song.title, items);
  if (which === 0) {
    choosePlaylist(song);
  } else if (which === 1) {
    Playlists.remove(openPlaylist, song);
    refresh();
  }
}

async function choosePlaylist(song) {
  const playlists = Store.playlists;
  const which = await Dialog.choose('Aggiungi a playlist', [...playlists.map((p) => p.name), '+ Nuova playlist']);
  if (which < 0) return;
  let playlist = playlists[which];
  if (!playlist) {
    const name = await Dialog.ask('', 'Nome della playlist');
    if (!name) return;
    playlist = Playlists.create(name);
  }
  const added = Playlists.add(playlist.id, song);
  toast(added ? `Aggiunto a ${playlist.name}` : `Già in ${playlist.name}`);
  refresh();
}

async function showPlaylistMenu(playlist) {
  const which = await Dialog.choose(playlist.name, ['Rinomina', 'Elimina']);
  if (which === 0) {
    const name = await Dialog.ask(playlist.name, 'Nome della playlist');
    if (name) {
      Playlists.rename(playlist.id, name);
      refresh();
    }
  } else if (which === 1 && (await Dialog.confirm(`Eliminare ${playlist.name}?`, 'Elimina'))) {
    Playlists.delete(playlist.id);
    refresh();
  }
}

function tile(title, song, onClick, onMenu) {
  const node = h('div', { class: 'tile', tabindex: 0, role: 'button' });
  const image = song ? (Downloads.isDownloaded(song) ? Downloads.coverUrl(song) : song.image) : '';
  if (image) {
    node.classList.add('with-cover');
    const img = h('img', { alt: '', loading: 'lazy', decoding: 'async' });
    Covers.set(img, song, true);
    node.append(img);
  }
  node.append(h('span', { class: 'label', text: title }));
  node.addEventListener('click', onClick);
  node.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') onClick();
  });
  if (onMenu) {
    node.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      onMenu();
    });
  }
  return node;
}

function row(title, subtitle, onClick) {
  const node = h('div', { class: 'row' + (onClick ? ' clickable' : ''), tabindex: onClick ? 0 : null, role: onClick ? 'button' : null },
    h('span', { class: 'title', text: title }),
    subtitle == null ? null : h('span', { class: 'subtitle', text: subtitle }));
  if (onClick) {
    node.addEventListener('click', onClick);
    node.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') onClick();
    });
  }
  return node;
}

function homeGrid() {
  const grid = h('div', { class: 'grid' });
  if (PhoneImport.progress) grid.append(row(PhoneImport.progress, null));
  grid.append(h('h2', { class: 'section-title', text: 'Playlist' }));
  for (const playlist of Store.playlists) {
    grid.append(tile(playlist.name, playlist.songs[0], () => {
      playlistId = playlist.id;
      showScreen('PLAYLIST');
    }, () => showPlaylistMenu(playlist)));
  }
  grid.append(tile('+ Nuova playlist', null, async () => {
    const name = await Dialog.ask('', 'Nome della playlist');
    if (name) {
      Playlists.create(name);
      refresh();
    }
  }));

  grid.append(h('h2', { class: 'section-title', text: 'Ascolti più spesso' }));
  const top = PlayStats.top(MOST_PLAYED_COUNT);
  if (top.length === 0) grid.append(row('Compariranno qui i brani che ascolti di più', null));
  top.forEach((song, i) => grid.append(tile(song.title, song, () => play(top, i), () => showSongMenu(song))));
  return grid;
}

function settingsRows() {
  const eq = Equalizer.state;
  const accent = ACCENTS.find((a) => a[0] === Store.settings.accent) || ACCENTS[0];
  return h('div', { class: 'rows' },
    row('Qualità streaming', `${Store.settings.quality} kbps`, chooseQuality),
    row('Equalizzatore', eq.enabled ? `Attivo · ${eq.points.length} punti` : 'Spento', () => showScreen('EQUALIZER')),
    row('Colore', accent[1], chooseAccent),
    row('Importa dal telefono',
      PhoneImport.progress || 'Playlist, ascolti, brani scaricati, equalizzatore e impostazioni: col cavo USB, come per Spotify',
      PhoneImport.progress ? null : () => PhoneImport.run()));
}

async function chooseQuality() {
  const which = await Dialog.choose('Qualità streaming', QUALITIES.map((q) => `${q} kbps`), QUALITIES.indexOf(Store.settings.quality));
  if (which < 0) return;
  Store.settings.quality = QUALITIES[which];
  Store.save('settings');
  refresh();
}

async function chooseAccent() {
  const index = ACCENTS.findIndex((a) => a[0] === Store.settings.accent);
  const which = await Dialog.choose('Colore', ACCENTS.map((a) => a[1]), Math.max(0, index), ACCENTS.map((a) => a[2]));
  if (which < 0) return;
  Store.settings.accent = ACCENTS[which][0];
  Store.save('settings');
  applyAccent();
  if (eqScreen) eqScreen.graph.draw();
  refresh();
}

// --- Search -----------------------------------------------------------------------------------

async function runSearch(query) {
  if (!query) return;
  $('search').blur();
  // Downloaded songs show up at once and keep working without a network.
  const local = Downloads.search(query);
  searchResults = local;
  searchStatus = local.length ? null : 'Cerco…';
  showScreen('SEARCH');

  const id = ++searchId;
  // Both catalogs at once: the slower one sets the pace.
  const [saavn, youtube] = await Promise.allSettled([Saavn.search(query), YouTube.search(query)]);
  if (id !== searchId) return;
  const found = merge(query, saavn.value || [], youtube.value || []);
  const localIds = new Set(local.map((s) => s.id));
  searchResults = local.concat(found.filter((s) => !localIds.has(s.id)));
  if (searchResults.length) searchStatus = null;
  else if (saavn.status === 'rejected' && youtube.status === 'rejected') searchStatus = 'Errore di rete';
  else searchStatus = 'Nessun risultato';
  if (screen === 'SEARCH') refresh(false);
  if (found.some((s) => YouTube.isYouTube(s))) YtStream.warmUp();
}

// Best match first, as on the phone: the more of the query a song has in its title and artist, the
// higher. For artists JioSaavn lacks it fills its answer with other songs of the same name; the song
// itself then comes from YouTube and must still come first. On a tie JioSaavn wins: it starts at
// once and goes up to 320 kbps. A YouTube song JioSaavn also has is left out, unless JioSaavn has only
// its clean edit: then the uncensored one from YouTube takes that place.
function merge(query, saavn, youtube) {
  const words = queryWords(query);
  const uncensored = new Set(saavn.concat(youtube).filter((s) => s.explicit).map(matchKey));
  const kept = saavn.filter((s) => s.explicit || !uncensored.has(matchKey(s)));
  const onSaavn = new Set(kept.map(matchKey));
  return kept.concat(youtube.filter((s) => !onSaavn.has(matchKey(s))))
    .map((song, i) => ({ song, i, score: matchScore(words, song) }))
    .sort((a, b) => b.score - a.score || a.i - b.i)
    .map((x) => x.song);
}

// Guests don't count, since only some catalogs put them in the title: YouTube's "Moulaga (con JUL)"
// is JioSaavn's "Moulaga".
function matchKey(song) {
  return normalize(song.title.replace(FEATURING, '')) + '|' + normalize(song.artist.split(',')[0]);
}

// One entry per recording, as on the phone. A catalog lists a song again on every compilation, and
// next to it its clean edit under the same title, often first: same title, first artist and length
// (within 2 s) count as one, in the place of the first. The uncensored one wins, then the bitrate.
function oneEach(songs, kbps = () => 0) {
  const kept = [];
  const keys = [];
  const rank = (song) => (song.explicit ? 10000 : 0) + kbps(song);
  const sameLength = (a, b) => (a.seconds > 0 && b.seconds > 0 ? Math.abs(a.seconds - b.seconds) <= 2 : a.seconds === b.seconds);
  for (const song of songs) {
    const key = matchKey(song);
    const twin = kept.findIndex((other, i) => keys[i] === key && sameLength(other, song));
    if (twin < 0) {
      kept.push(song);
      keys.push(key);
    } else if (rank(song) > rank(kept[twin])) {
      kept[twin] = song;
    }
  }
  return kept;
}

// Lowercase, accents and apostrophes dropped, anything else that isn't a letter or digit one space:
// "Shotta's" is "shottas", "Fousheé" is "foushee", as people and catalogs write them.
function normalize(text) {
  return text.toLowerCase().normalize('NFD').replace(MARKS, '').replace(APOSTROPHES, '').replace(NON_WORD, ' ').trim();
}

function queryWords(query) {
  return normalize(query).split(' ').filter(Boolean);
}

// The letters of the query's words a song has in its title or artist. Spaces don't count, so
// "season25" finds "Season 25".
function matchScore(words, song) {
  const text = normalize(`${song.title} ${song.artist}`);
  const joined = text.replace(/ /g, '');
  return words.reduce((sum, w) => sum + (text.includes(w) || joined.includes(w) ? w.length : 0), 0);
}

function matchesAll(words, song) {
  const text = normalize(`${song.title} ${song.artist}`);
  const joined = text.replace(/ /g, '');
  return words.every((w) => text.includes(w) || joined.includes(w));
}

// --- Importing from the phone -----------------------------------------------------------------

// Everything the phone has, over USB through adb (see ExportProvider.kt on the phone and
// PhoneData in Playerless.exe). Running it again updates instead of copying twice.
const PhoneImport = {
  progress: null,

  setProgress(text) {
    PhoneImport.progress = text;
    if (screen === 'HOME' || screen === 'SETTINGS') refresh();
  },

  async run() {
    if (PhoneImport.progress) return;
    PhoneImport.setProgress('Leggo i dati dal telefono…');
    try {
      const response = await fetch('/api/phone/data', { method: 'POST' });
      const text = await response.text();
      if (!response.ok) throw new Error(text);
      const data = JSON.parse(text);
      if (data.format !== 'playerless') throw new Error('Il telefono ha mandato dati che non riconosco.');

      const playlists = PhoneImport.mergePlaylists(data.playlists || []);
      const plays = PhoneImport.mergePlays(data.plays || []);
      PhoneImport.mergeSettings(data);

      const missing = (data.downloads || []).filter((s) => s && s.id && !Downloads.isDownloaded(s) && s.audioBytes > 0);
      let copied = 0;
      let failure = null;
      for (let i = 0; i < missing.length; i++) {
        const song = missing[i];
        PhoneImport.setProgress(`Copio i brani scaricati dal telefono: ${i + 1} di ${missing.length}`);
        try {
          await PhoneImport.copy(`${safeName(song.id)}.m4a`, song.audioBytes);
          if (song.coverBytes > 0) await PhoneImport.copy(`${safeName(song.id)}.jpg`, song.coverBytes).catch(() => {});
          // Saved after each song: an interrupted import keeps what it copied.
          Store.downloads = [...Store.downloads.filter((s) => s.id !== song.id), plainSong(song)];
          Downloads.changed();
          Store.save('downloads');
          copied++;
        } catch (e) {
          failure = e.message;
        }
      }

      const parts = [`${playlists} playlist`, `${plays} ascolti`, `${copied} ${copied === 1 ? 'brano scaricato' : 'brani scaricati'}`];
      let message = 'Dal telefono: ' + parts.join(', ');
      if (failure) message += `\n${missing.length - copied} brani non copiati: ${failure}`;
      PhoneImport.progress = null;
      refresh();
      if (failure) Dialog.alert(message);
      else toast(message, true);
    } catch (e) {
      PhoneImport.progress = null;
      refresh();
      Dialog.alert(e instanceof SyntaxError ? 'Il telefono ha risposto in un modo che non capisco.' : e.message);
    }
  },

  async copy(name, bytes) {
    const response = await fetch('/api/phone/file', { method: 'POST', body: JSON.stringify({ name, bytes }) });
    if (!response.ok) throw new Error(await response.text());
  },

  // The phone's playlists replace those with the same id or name; the PC's others stay.
  mergePlaylists(phone) {
    const valid = phone.filter((p) => p && p.id && p.name && Array.isArray(p.songs))
      .map((p) => ({ id: p.id, name: p.name, songs: p.songs.map(plainSong) }));
    const replaced = (p) => valid.some((v) => v.id === p.id || v.name === p.name);
    Store.playlists = [...valid, ...Store.playlists.filter((p) => !replaced(p))];
    Store.save('playlists');
    return valid.length;
  },

  // Plays counted on both sides keep the higher count, so importing twice doesn't double them.
  mergePlays(phone) {
    const valid = phone.filter((e) => e && e.id && e.plays > 0);
    const desktop = new Map(Store.plays.map((e) => [e.id, e]));
    const merged = valid.map((e) => ({ ...plainSong(e), plays: Math.max(e.plays, desktop.has(e.id) ? desktop.get(e.id).plays : 0) }));
    const ids = new Set(merged.map((e) => e.id));
    Store.plays = [...merged, ...Store.plays.filter((e) => !ids.has(e.id))].slice(0, MAX_PLAYS);
    Store.save('plays');
    return valid.length;
  },

  mergeSettings(data) {
    const settings = data.settings || {};
    if (QUALITIES.includes(settings.quality)) Store.settings.quality = settings.quality;
    if (ACCENTS.some((a) => a[0] === settings.accent)) Store.settings.accent = settings.accent;
    Store.save('settings');
    applyAccent();
    const eq = data.equalizer || {};
    if (Array.isArray(eq.points)) {
      Store.equalizer = { enabled: !!eq.enabled, points: eq.points };
      Equalizer.init();
      Equalizer.publish(Equalizer.state);
      Store.save('equalizer');
      if (eqScreen) eqScreen.load();
    }
  },
};

// --- Dialogs ----------------------------------------------------------------------------------

// The phone's AlertDialogs: a list to choose from, a name to type, a yes or no.
const Dialog = {
  open(build) {
    return new Promise((resolve) => {
      const previous = document.activeElement;
      const scrim = h('div', { class: 'dialog-scrim' });
      const panel = h('div', { class: 'dialog', role: 'dialog', 'aria-modal': 'true' });
      scrim.append(panel);
      const close = (value) => {
        scrim.remove();
        document.removeEventListener('keydown', onKey, true);
        if (previous && previous.focus) previous.focus();
        resolve(value);
      };
      const onKey = (e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          e.stopPropagation();
          close(build.cancel);
        }
      };
      scrim.addEventListener('mousedown', (e) => {
        if (e.target === scrim) close(build.cancel);
      });
      document.addEventListener('keydown', onKey, true);
      build.fill(panel, close);
      document.body.append(scrim);
      const focus = ['input', '.dialog-item.selected', '.dialog-item', 'button'].map((s) => panel.querySelector(s)).find(Boolean);
      if (focus) focus.focus();
    });
  },

  // Resolves to the index chosen, -1 when dismissed. With a selection it's a single-choice list.
  choose(title, labels, selected = -1, colors = null) {
    return Dialog.open({
      cancel: -1,
      fill(panel, close) {
        panel.append(h('div', { class: 'dialog-title', text: title }));
        const items = h('div', { class: 'dialog-items' });
        labels.forEach((label, i) => {
          items.append(h('button', { class: 'dialog-item' + (i === selected ? ' selected' : ''), onclick: () => close(i) },
            selected >= 0 ? h('span', { class: 'radio' + (i === selected ? ' on' : '') }) : null,
            h('span', { text: label, style: colors ? `color:${colors[i]}` : null })));
        });
        panel.append(items);
      },
    });
  },

  ask(initial, hint) {
    return Dialog.open({
      cancel: null,
      fill(panel, close) {
        const input = h('input', { class: 'dialog-input', type: 'text', placeholder: hint, value: initial, spellcheck: 'false' });
        const done = () => close(input.value.trim() || null);
        input.addEventListener('keydown', (e) => {
          if (e.key === 'Enter') done();
        });
        panel.append(input, h('div', { class: 'dialog-buttons' },
          h('button', { text: 'Annulla', onclick: () => close(null) }),
          h('button', { text: 'OK', onclick: done })));
        setTimeout(() => input.select(), 0);
      },
    });
  },

  confirm(message, ok) {
    return Dialog.open({
      cancel: false,
      fill(panel, close) {
        panel.append(h('div', { class: 'dialog-message', text: message }), h('div', { class: 'dialog-buttons' },
          h('button', { text: 'Annulla', onclick: () => close(false) }),
          h('button', { text: ok, onclick: () => close(true) })));
      },
    });
  },

  alert(message) {
    return Dialog.open({
      cancel: null,
      fill(panel, close) {
        panel.append(h('div', { class: 'dialog-message', text: message }), h('div', { class: 'dialog-buttons' },
          h('button', { text: 'OK', onclick: () => close(null) })));
      },
    });
  },
};

// --- Start ------------------------------------------------------------------------------------

function typing(e) {
  return e.target instanceof HTMLInputElement && (e.target.type === 'text' || e.target.type === 'search');
}

function setUpScreens() {
  $('navHome').innerHTML = icon('home');
  $('navDownloads').innerHTML = icon('download');
  $('navSettings').innerHTML = icon('settings');
  $('searchIcon').innerHTML = icon('search');
  $('playAll').innerHTML = icon('play');
  $('shuffleAll').innerHTML = icon('shuffle');

  $('navHome').addEventListener('click', () => showScreen('HOME'));
  $('navDownloads').addEventListener('click', () => showScreen('DOWNLOADS'));
  $('navSettings').addEventListener('click', () => showScreen('SETTINGS'));
  $('playAll').addEventListener('click', () => playAll(shownSongs()));
  $('shuffleAll').addEventListener('click', () => {
    const songs = [...shownSongs()];
    for (let i = songs.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [songs[i], songs[j]] = [songs[j], songs[i]];
    }
    playAll(songs);
  });

  const search = $('search');
  search.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') runSearch(search.value.trim());
    if (e.key === 'Escape') search.blur();
  });
  $('searchIcon').addEventListener('click', () => {
    const query = search.value.trim();
    if (query) runSearch(query);
    else search.focus();
  });

  document.addEventListener('keydown', (e) => {
    if (document.querySelector('.dialog-scrim')) return;
    if ((e.ctrlKey && e.key.toLowerCase() === 'f') || (e.key === '/' && !typing(e))) {
      e.preventDefault();
      search.focus();
      search.select();
    } else if (typing(e)) {
      // Keys belong to the text field.
    } else if (Visuals.key(e)) {
      e.preventDefault();
    } else if (e.key === ' ' && !(e.target instanceof HTMLButtonElement) && !e.target.closest('[role=button]')) {
      e.preventDefault();
      Player.toggle();
    } else if (e.ctrlKey && e.key === 'ArrowRight') {
      Player.next();
    } else if (e.ctrlKey && e.key === 'ArrowLeft') {
      Player.previous();
    } else if (e.key === 'Escape') {
      // The phone's back button.
      if (nowOpen) closeNow();
      else if (screen === 'EQUALIZER') showScreen('SETTINGS');
      else if (screen !== 'HOME') showScreen('HOME');
    }
  });
}

async function start() {
  await Store.load();
  if (!QUALITIES.includes(Store.settings.quality)) Store.settings.quality = QUALITIES[0];
  Downloads.changed();
  Equalizer.init();
  applyAccent();
  setUpScreens();
  setUpPlayerControls();
  showScreen('HOME');

  // Playerless.exe stays while this is connected, and asks through it to bring the window up.
  const events = new EventSource('/api/events');
  events.addEventListener('show', () => window.focus());
}

start();
