'use strict';

// Where the music comes from: JioSaavn and YouTube Music. A port of the phone's SaavnApi,
// YouTube, YtSolver, PoToken and YtStream, which came from Convx.

// JioSaavn's private API, the same one its official app uses.
const Saavn = {
  BASE: 'https://www.jiosaavn.com/api.php',
  UA: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36',
  SUFFIX: /_(48|96|160|320)\.(mp4|aac|mp3)$/,

  // The bitrate a stream URL points at, when its name says so.
  kbpsOf(url) {
    const match = Saavn.SUFFIX.exec(url);
    return match ? Number(match[1]) : null;
  },

  // Only ever lowers the bitrate: a track without a 320 rendition stays as it is.
  withQuality(url, kbps) {
    const match = Saavn.SUFFIX.exec(url);
    if (!match || kbps >= Number(match[1])) return url;
    return url.slice(0, match.index) + `_${kbps}.${match[2]}`;
  },

  async search(query) {
    const params = {
      __call: 'search.getResults', _format: 'json', _marker: '0', api_version: '4',
      ctx: 'android', q: query, p: '1', n: '30',
    };
    const url = Saavn.BASE + '?' + Object.entries(params).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
    const json = await Net.json(url, {
      headers: {
        Accept: 'application/json',
        'User-Agent': Saavn.UA,
        'Accept-Language': 'en-IN,en;q=0.9',
        Cookie: 'explicit_content=1',
        'X-Forwarded-For': '49.36.0.1',
        'X-Real-IP': '49.36.0.1',
      },
    });
    const results = (json.results || []).filter((item) => item && item.more_info && item.more_info.encrypted_media_url);
    if (results.length === 0) return [];
    // The stream links come DES-encrypted; Playerless.exe has DES, the page doesn't.
    const response = await fetch('/api/des', { method: 'POST', body: JSON.stringify(results.map((item) => item.more_info.encrypted_media_url)) });
    const links = await response.json();
    // The explicit recording is nearly always there, but its clean edit often comes first.
    const songs = results.map((item, i) => Saavn.parse(item, links[i])).filter(Boolean);
    return oneEach(songs, (song) => Number((Saavn.SUFFIX.exec(song.url) || [])[1]) || 0);
  },

  parse(json, link) {
    if (!link) return null;
    const info = json.more_info;
    // The catalogue says per track whether a 320 rendition exists; asking the CDN anyway fails.
    const match = Saavn.SUFFIX.exec(link);
    const url = String(info['320kbps']).toLowerCase() === 'true' && match ? link.slice(0, match.index) + `_320.${match[2]}` : link;
    const artists = (info.artistMap && info.artistMap.primary_artists) || [];
    return {
      id: String(json.id || ''),
      title: unescapeHtml(String(json.title || '')),
      artist: unescapeHtml(artists.map((a) => a.name || '').join(', ')),
      image: String(json.image || '').replace(/http:\/\//g, 'https://'),
      url,
      seconds: parseInt(info.duration, 10) || 0,
      explicit: String(json.explicit_content) === '1',
    };
  },
};

// YouTube Music's own API, as its web app and the VR app call it. Songs from here have ids and
// URLs "yt:<videoId>"; YtStream turns those into streams at play time.
const YouTube = {
  PREFIX: 'yt:',
  WEB_UA: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0',
  ORIGIN: 'https://music.youtube.com',
  SONGS_ONLY: 'EgWKAQIIAWoKEAkQBRAKEAMQBA==',
  COVER_SIZE: /=w\d+-h\d+/,
  DURATION: /^\d+(:\d{2}){1,2}$/,
  VISITOR: /"(Cg[ts][A-Za-z0-9%_-]{20,})"/,

  clients: {
    WEB_REMIX: {
      name: 'WEB_REMIX', version: '1.20260213.01.00', id: '67',
      ua: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0',
      api: 'https://music.youtube.com/youtubei/v1/',
    },
    // Answers with plain stream URLs, nothing to decipher; YouTube doesn't always let it.
    ANDROID_VR: {
      name: 'ANDROID_VR', version: '1.43.32', id: '28',
      ua: 'com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)',
      api: 'https://www.youtube.com/youtubei/v1/',
    },
  },

  isYouTube(song) {
    return song.id.startsWith(YouTube.PREFIX);
  },

  // YouTube's id for this anonymous visitor. Streams and tokens are tied to it, so it's kept.
  async visitorData() {
    if (Store.youtube.visitor) return Store.youtube.visitor;
    const page = await Net.text(YouTube.ORIGIN + '/sw.js_data', { headers: { 'User-Agent': YouTube.WEB_UA } });
    const match = YouTube.VISITOR.exec(page);
    if (!match) throw new YtError('YouTube non ha dato un visitorData');
    Store.youtube.visitor = match[1];
    Store.save('youtube');
    return match[1];
  },

  async search(query) {
    const client = YouTube.clients.WEB_REMIX;
    const body = { context: YouTube.context(client, Store.youtube.visitor), query, params: YouTube.SONGS_ONLY };
    const json = await Net.json(client.api + 'search?prettyPrint=false', {
      method: 'POST', headers: YouTube.headers(client, null), body: JSON.stringify(body),
    });

    // A search hands out a visitor id too, which saves fetching one at the first play.
    const visitor = json.responseContext && json.responseContext.visitorData;
    if (visitor && !Store.youtube.visitor) {
      Store.youtube.visitor = visitor;
      Store.save('youtube');
    }

    const tab = json.contents && json.contents.tabbedSearchResultsRenderer && json.contents.tabbedSearchResultsRenderer.tabs &&
      json.contents.tabbedSearchResultsRenderer.tabs[0];
    const sections = (tab && tab.tabRenderer && tab.tabRenderer.content && tab.tabRenderer.content.sectionListRenderer &&
      tab.tabRenderer.content.sectionListRenderer.contents) || [];
    const songs = [];
    for (const section of sections) {
      const items = (section && section.musicShelfRenderer && section.musicShelfRenderer.contents) || [];
      for (const item of items) {
        const song = item && item.musicResponsiveListItemRenderer && YouTube.parseSong(item.musicResponsiveListItemRenderer);
        if (song) songs.push(song);
      }
    }
    return oneEach(songs);
  },

  // The answer to "how do I play this video", streams included.
  async player(client, videoId, visitor, signatureTimestamp) {
    const body = { context: YouTube.context(client, visitor), videoId, contentCheckOk: true, racyCheckOk: true };
    if (signatureTimestamp != null) body.playbackContext = { contentPlaybackContext: { signatureTimestamp } };
    return Net.json(client.api + 'player?prettyPrint=false', {
      method: 'POST', headers: YouTube.headers(client, visitor), body: JSON.stringify(body),
    });
  },

  context(client, visitor) {
    const info = { clientName: client.name, clientVersion: client.version, hl: 'it', gl: 'IT' };
    if (visitor) info.visitorData = visitor;
    if (client === YouTube.clients.ANDROID_VR) {
      Object.assign(info, { deviceMake: 'Oculus', deviceModel: 'Quest 3', osName: 'Android', osVersion: '12', androidSdkVersion: 32 });
    }
    return { client: info };
  },

  headers(client, visitor) {
    const headers = {
      'Content-Type': 'application/json',
      'User-Agent': client.ua,
      'X-YouTube-Client-Name': client.id,
      'X-YouTube-Client-Version': client.version,
      'X-Origin': YouTube.ORIGIN,
      Referer: YouTube.ORIGIN + '/',
    };
    if (visitor) headers['X-Goog-Visitor-Id'] = visitor;
    return headers;
  },

  parseSong(item) {
    const videoId = (item.playlistItemData && item.playlistItemData.videoId) || '';
    if (!videoId) return null;
    const columns = item.flexColumns || [];
    const first = YouTube.runs(columns, 0);
    const title = (first && first[0] && first[0].text) || '';
    if (!title) return null;

    // "Artist • Album • 3:20": the artists are the runs that link to an artist page.
    const runs = (YouTube.runs(columns, 1) || []).filter(Boolean);
    const artists = runs.filter((run) => YouTube.pageType(run) === 'MUSIC_PAGE_TYPE_ARTIST').map((run) => run.text || '');
    const artist = artists.join(', ') || (runs[0] && runs[0].text) || '';

    const thumbs = item.thumbnail && item.thumbnail.musicThumbnailRenderer && item.thumbnail.musicThumbnailRenderer.thumbnail &&
      item.thumbnail.musicThumbnailRenderer.thumbnail.thumbnails;
    const image = (thumbs && thumbs.length && thumbs[thumbs.length - 1].url) || '';
    const last = runs.length ? String(runs[runs.length - 1].text || '') : '';

    return {
      id: YouTube.PREFIX + videoId,
      title,
      artist,
      // The size is in the URL; 150 matches what JioSaavn gives for rows.
      image: image.replace(YouTube.COVER_SIZE, '=w150-h150'),
      url: YouTube.PREFIX + videoId,
      seconds: YouTube.DURATION.test(last) ? last.split(':').reduce((total, part) => total * 60 + Number(part), 0) : 0,
      // The "E" YouTube Music shows next to a title.
      explicit: (item.badges || []).some((badge) => {
        const icon = badge && badge.musicInlineBadgeRenderer && badge.musicInlineBadgeRenderer.icon;
        return Boolean(icon) && icon.iconType === 'MUSIC_EXPLICIT_BADGE';
      }),
    };
  },

  runs(columns, index) {
    const column = columns[index] && columns[index].musicResponsiveListItemFlexColumnRenderer;
    return column && column.text && column.text.runs;
  },

  pageType(run) {
    const endpoint = run.navigationEndpoint && run.navigationEndpoint.browseEndpoint;
    const config = endpoint && endpoint.browseEndpointContextSupportedConfigs &&
      endpoint.browseEndpointContextSupportedConfigs.browseEndpointContextMusicConfig;
    return config && config.pageType;
  },
};

// yt-dlp's solver, in a worker of its own: preparing a new player takes seconds, and the page
// shouldn't freeze meanwhile.
class SolverWorker {
  constructor() {
    this.worker = new Worker('solver-worker.js');
    this.calls = new Map();
    this.ids = 0;
    this.dead = false;
    this.worker.onmessage = (event) => {
      const call = this.calls.get(event.data.id);
      if (!call) return;
      this.calls.delete(event.data.id);
      if (event.data.error != null) call.reject(new YtError(event.data.error));
      else call.resolve(event.data);
    };
    this.worker.onerror = (event) => {
      event.preventDefault();
      this.fail(event.message || 'Errore nel decifratore');
    };
  }

  call(message, timeoutMs = 30000) {
    if (this.dead) return Promise.reject(new YtError('Decifratore chiuso'));
    const id = ++this.ids;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.calls.delete(id);
        reject(new YtError('JavaScript non risponde'));
      }, timeoutMs);
      this.calls.set(id, {
        resolve: (value) => { clearTimeout(timer); resolve(value); },
        reject: (error) => { clearTimeout(timer); reject(error); },
      });
      this.worker.postMessage({ ...message, id });
    });
  }

  async solve(n, sig) {
    return (await this.call({ type: 'solve', n, sig })).result;
  }

  fail(message) {
    this.dead = true;
    for (const call of this.calls.values()) call.reject(new YtError(message));
    this.calls.clear();
  }

  close() {
    this.fail('Decifratore chiuso');
    this.worker.terminate();
  }
}

// YouTube's player script holds the functions that unscramble stream links. The script is kept on
// disk and checked once a day; the solver runs it in a worker, kept for the next songs.
const YtSolver = {
  CHECK_EVERY_MS: 24 * 3600e3,
  RETRY_BROKEN_MS: 10 * 60e3,
  HASH: /player\\?\/([A-Za-z0-9_-]{6,})\\?\//,
  STS: /(?:signatureTimestamp|sts)\s*[:=]\s*(\d{5})/,
  lock: serialized(),
  worker: null,
  workerHash: null,
  // A player the solver can't read fails the same way for every song: say so at once.
  broken: null,
  brokenHash: null,
  brokenUntil: 0,

  playerUrl(hash) {
    return `https://www.youtube.com/s/player/${hash}/player_ias.vflset/en_GB/base.js`;
  },

  // refresh asks YouTube for its current player even if the day isn't over.
  get(log, refresh = false) {
    return YtSolver.lock(() => YtSolver.load(log, refresh));
  },

  async load(log, refresh) {
    const saved = Store.youtube;
    let hash = saved.player || null;
    let sts = saved.sts || 0;
    let code = null;
    const stale = Date.now() - (saved.checked || 0) > YtSolver.CHECK_EVERY_MS;
    if (!hash || refresh || stale || !(await Cache.has(`player-${hash}.js`))) {
      const iframe = await Net.text('https://www.youtube.com/iframe_api', { headers: { 'User-Agent': YouTube.WEB_UA } });
      const match = YtSolver.HASH.exec(iframe);
      if (!match) throw new YtError('Versione del player sconosciuta');
      const current = match[1];
      if (current !== hash || !(await Cache.has(`player-${current}.js`))) {
        log('Scarico il player di YouTube');
        code = await Net.text(YtSolver.playerUrl(current), { headers: { 'User-Agent': YouTube.WEB_UA } });
        const found = YtSolver.STS.exec(code);
        if (!found) throw new YtError('Player di YouTube non riconosciuto');
        sts = Number(found[1]);
        await Cache.clear();
        await Cache.put(`player-${current}.js`, code);
        hash = current;
      }
      Object.assign(saved, { player: hash, sts, checked: Date.now() });
      Store.save('youtube');
    }

    if (YtSolver.worker && !YtSolver.worker.dead && YtSolver.workerHash === hash) return { sts, solver: YtSolver.worker };
    if (hash === YtSolver.brokenHash && Date.now() < YtSolver.brokenUntil) throw new YtError(YtSolver.broken);
    if (YtSolver.worker) YtSolver.worker.close();
    YtSolver.worker = null;

    const prepared = await Cache.get(`prep-${hash}.js`);
    // Preparing a new player takes seconds; the line says why the wait is longer.
    log(prepared ? 'Avvio il decifratore' : 'Preparo il decifratore (1ª volta)');
    const worker = new SolverWorker();
    try {
      const player = prepared ? null : code || (await Cache.get(`player-${hash}.js`));
      const result = await worker.call({ type: 'load', prepared, player }, 120000);
      if (result.preprocessed) Cache.put(`prep-${hash}.js`, result.preprocessed);
    } catch (e) {
      worker.close();
      YtSolver.broken = 'Decifratore: ' + e.message;
      YtSolver.brokenHash = hash;
      YtSolver.brokenUntil = Date.now() + YtSolver.RETRY_BROKEN_MS;
      throw new YtError(YtSolver.broken);
    }
    YtSolver.worker = worker;
    YtSolver.workerHash = hash;
    return { sts, solver: worker };
  },
};

// The token YouTube wants before it serves more than the first megabyte of a stream, one per
// video. Minting needs BotGuard, Google's attestation script: its page stays open in a hidden
// frame while its integrity token lasts, hours, and then mints each song's token in milliseconds.
const PoToken = {
  API: 'https://www.youtube.com/api/jnn/v1/',
  REQUEST_KEY: 'O43z0dpjhgX20SCx4KAo',
  API_KEY: 'AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw',
  MARGIN_MS: 10 * 60e3,
  lock: serialized(),
  frame: null,
  expires: 0,

  // The browser that runs BotGuard is the one that asks for its tokens.
  headers() {
    return {
      'User-Agent': navigator.userAgent,
      Accept: 'application/json',
      'Content-Type': 'application/json+protobuf',
      'x-goog-api-key': PoToken.API_KEY,
      'x-user-agent': 'grpc-web-javascript/0.1',
    };
  },

  async forVideo(videoId, log) {
    const page = await PoToken.ready(log);
    return String(await page.mint(videoId));
  },

  // Readies BotGuard ahead of the first song.
  ready(log) {
    return PoToken.lock(() => PoToken.open(log));
  },

  async open(log) {
    if (PoToken.frame && Date.now() < PoToken.expires) return PoToken.frame.contentWindow;
    PoToken.forget();

    log('Avvio BotGuard (anti-bot)');
    const frame = document.createElement('iframe');
    frame.hidden = true;
    frame.src = 'botguard.html';
    try {
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new YtError('La pagina di BotGuard non si carica')), 15000);
        frame.onload = () => { clearTimeout(timer); resolve(); };
        document.body.appendChild(frame);
      });
      const page = frame.contentWindow;
      const challenge = await Net.text(PoToken.API + 'Create', {
        method: 'POST', headers: PoToken.headers(), body: JSON.stringify([PoToken.REQUEST_KEY]),
      });
      const answer = String(await page.start(challenge));
      const integrity = JSON.parse(await Net.text(PoToken.API + 'GenerateIT', {
        method: 'POST', headers: PoToken.headers(), body: JSON.stringify([PoToken.REQUEST_KEY, answer]),
      }));
      await page.setToken(String(integrity[0]));
      PoToken.expires = Date.now() + Number(integrity[1]) * 1000 - PoToken.MARGIN_MS;
    } catch (e) {
      frame.remove();
      throw e instanceof YtError ? e : new YtError('BotGuard: ' + (e && e.message ? e.message : e));
    }
    PoToken.frame = frame;
    log('BotGuard pronto');
    return frame.contentWindow;
  },

  // When YouTube turns down a link, BotGuard starts over.
  forget() {
    if (PoToken.frame) PoToken.frame.remove();
    PoToken.frame = null;
  },
};

// Turns a YouTube video id into a stream URL for the player and for downloads. YouTube hands
// clients it doesn't trust links that stop after the first megabyte, so a link counts only once
// its last byte downloads. Two ways in: the VR app's client, instant when YouTube lets it through;
// otherwise the web client, whose links need unscrambling and a BotGuard token.
const YtStream = {
  // The best audio YouTube gives without Premium: Opus in WebM at about 130-160 kbps, the full band
  // to 20 kHz. AAC in MP4 (128 kbps, cut at 16 kHz) only when a video lacks it.
  OPUS: 251,
  AAC: 140,
  DEFAULT_EXPIRY_S: 6 * 3600,
  EXPIRY_MARGIN_MS: 30 * 60e3,
  VR_RETRY_MS: 24 * 3600e3,
  links: new Map(),
  pending: new Map(),

  // Asking a client YouTube turned down costs half a second, so a refusal holds for a day.
  get vrRefused() {
    return Date.now() < (Store.youtube.vrRefusedUntil || 0);
  },

  set vrRefused(value) {
    Store.youtube.vrRefusedUntil = value ? Date.now() + YtStream.VR_RETRY_MS : 0;
    Store.save('youtube');
  },

  // The link: url, and codec and kbps for the downloads list. log gets each step, for the on-screen
  // log. A song asked for twice at once, to play and to get ready as the next one, is resolved once.
  resolve(videoId, log) {
    const link = YtStream.links.get(videoId);
    if (link && link.expires > Date.now()) return Promise.resolve(link);
    let pending = YtStream.pending.get(videoId);
    if (!pending) {
      pending = YtStream.fetch(videoId, log).finally(() => YtStream.pending.delete(videoId));
      YtStream.pending.set(videoId, pending);
    }
    return pending;
  },

  async fetch(videoId, log) {
    log('Chiedo il brano a YouTube');
    const visitor = await YouTube.visitorData();
    const link = (YtStream.vrRefused ? null : await YtStream.fromVr(videoId, visitor, log)) ||
      (await YtStream.fromWeb(videoId, visitor, log, true));
    YtStream.links.set(videoId, link);
    return link;
  },

  // A link that failed while playing shouldn't be handed out again.
  forget(videoId) {
    YtStream.links.delete(videoId);
  },

  // Gets the web client ready while the user is still choosing, so the first song starts sooner.
  warmUp() {
    (async () => {
      await YouTube.visitorData();
      await PoToken.ready(() => {});
      await YtSolver.get(() => {});
    })().catch(() => {});
  },

  async fromVr(videoId, visitor, log) {
    let response = null;
    try {
      response = await YouTube.player(YouTube.clients.ANDROID_VR, videoId, visitor, null);
    } catch (e) {
      response = null;
    }
    const format = response && YtStream.status(response) === 'OK' ? YtStream.audioFormat(response) : null;
    if (format && format.url) {
      log('Verifico il link');
      if (await YtStream.works(format.url, format)) return YtStream.link(format.url, format, response);
    }
    YtStream.vrRefused = true;
    log('Client VR bloccato, uso il web');
    return null;
  },

  async fromWeb(videoId, visitor, log, firstTry) {
    // The token comes from Google, the player from YouTube: fetched side by side.
    const token = PoToken.forVideo(videoId, log);
    token.catch(() => {});
    const player = await YtSolver.get(log, !firstTry);
    const response = await YouTube.player(YouTube.clients.WEB_REMIX, videoId, visitor, player.sts);
    if (YtStream.status(response) !== 'OK') {
      const reason = response.playabilityStatus && response.playabilityStatus.reason;
      throw new YtError(reason || 'YouTube non dà questo brano');
    }
    const format = YtStream.audioFormat(response);
    if (!format) throw new YtError('Nessun audio per questo brano');

    log('Decifro il link');
    const cipher = YtStream.query(format.signatureCipher || '');
    let url = format.url || cipher.url || '';
    if (!url) throw new YtError('Link mancante');
    const n = new URL(url).searchParams.get('n');
    const signature = cipher.s;
    const solved = await player.solver.solve(n || '', signature || '');
    if (n != null) {
      const newN = solved.n || '';
      if (!newN || newN === n || newN.startsWith('enhanced_except')) throw new YtError('Decifratura fallita');
      url = url.replace(/([?&])n=[^&]*/, (_, separator) => separator + 'n=' + encodeURIComponent(newN));
    }
    if (signature != null) url += `&${cipher.sp || 'signature'}=${encodeURIComponent(solved.sig)}`;
    url += '&pot=' + (await token);

    log('Verifico il link');
    if (await YtStream.works(url, format)) return YtStream.link(url, format, response);
    if (!firstTry) throw new YtError('YouTube rifiuta il link');
    log('Link rifiutato, riprovo');
    PoToken.forget();
    return YtStream.fromWeb(videoId, visitor, log, false);
  },

  status(response) {
    return response.playabilityStatus && response.playabilityStatus.status;
  },

  // Opus, else AAC, else the richest audio there is. Never a "DRC" copy, whose loudness is squeezed.
  audioFormat(response) {
    const formats = (response.streamingData && response.streamingData.adaptiveFormats) || [];
    const audio = formats.filter((f) => f && /^audio\/(webm|mp4)/.test(String(f.mimeType || '')) && !f.isDrc);
    return audio.find((f) => f.itag === YtStream.OPUS) || audio.find((f) => f.itag === YtStream.AAC) ||
      audio.sort((a, b) => (Number(b.bitrate) || 0) - (Number(a.bitrate) || 0))[0] || null;
  },

  // averageBitrate is the file's; bitrate, its peak, only stands in when that's missing.
  link(url, format, response) {
    const bps = Number(format.averageBitrate) || Number(format.bitrate) || 0;
    const codec = String(format.mimeType || '').includes('opus') ? 'Opus' : 'AAC';
    return { url, expires: YtStream.expiry(response), codec, kbps: Math.round(bps / 1000) };
  },

  // Whether YouTube would really serve the end of the file.
  async works(url, format) {
    const length = Number(format.contentLength) || 1;
    try {
      const { status } = await Net.fetch(url, { headers: { 'User-Agent': YouTube.WEB_UA, Range: `bytes=${length - 1}-${length - 1}` } });
      return status === 200 || status === 206;
    } catch (e) {
      return false;
    }
  },

  expiry(response) {
    const seconds = Number(response.streamingData && response.streamingData.expiresInSeconds) || YtStream.DEFAULT_EXPIRY_S;
    return Date.now() + seconds * 1000 - YtStream.EXPIRY_MARGIN_MS;
  },

  // signatureCipher is a query string: s, sp and url.
  query(text) {
    const result = {};
    for (const pair of text.split('&')) {
      const at = pair.indexOf('=');
      if (at > 0) result[decodeURIComponent(pair.slice(0, at))] = decodeURIComponent(pair.slice(at + 1));
    }
    return result;
  },
};
