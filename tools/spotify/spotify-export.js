// Playerless: esporta le tue playlist di Spotify per il telefono.
// Di solito lo usa da solo il bot (importa-spotify.bat). Piano B, a mano: su open.spotify.com, con
// il tuo account aperto, F12, scheda Console, incolla, Invio. Firefox la prima volta blocca
// l'incolla e chiede di scrivere una frase ("consenti incolla" in italiano). Il file scaricato
// lo porta il bot sul telefono con: importa-spotify.bat -File <file>.
//
// It talks to the web player's own API (GraphQL "pathfinder"), with the page's own token: the
// public Web API answers every call made with that token with 429.
(() => {
  // true: the playlists you created, plus your Liked Songs. false: also the ones you follow.
  const ONLY_MINE = true;
  const LIKED = 'Brani che ti piacciono';
  const PATHFINDER = 'https://api-partner.spotify.com/pathfinder/v2/query';
  // The web player names each query by a hash of its text. These are November 2026's; when
  // Spotify changes one (HTTP 412), the current ones are read from the web player's code.
  const HASHES = {
    profileAttributes: '08ffb4730af3746e04a8301396f20875dbbce10c75243803091a9274eacc8ac0',
    libraryV3: '390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455',
    fetchLibraryTracks: '087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240',
    fetchPlaylist: '21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf',
  };
  const nativeFetch = window.fetch;
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const say = (...parts) => console.log('%c[Playerless]', 'color:#1db954;font-weight:bold', ...parts);
  let refreshed = false;

  const refreshHashes = async () => {
    refreshed = true;
    const bundle = [...document.scripts].map((s) => s.src).find((src) => /\/web-player\.[a-f0-9]+\.js/.test(src));
    if (!bundle) return;
    const code = await (await nativeFetch(bundle)).text();
    for (const op of Object.keys(HASHES)) {
      const match = code.match(new RegExp(`"${op}","query","([a-f0-9]{64})"`));
      if (match) HASHES[op] = match[1];
    }
  };

  const query = async (token, op, variables) => {
    for (let attempt = 0; ; attempt++) {
      const response = await nativeFetch(PATHFINDER, {
        method: 'POST',
        headers: { authorization: token, 'content-type': 'application/json;charset=UTF-8', 'app-platform': 'WebPlayer' },
        body: JSON.stringify({ variables, operationName: op, extensions: { persistedQuery: { version: 1, sha256Hash: HASHES[op] } } }),
      });
      if (response.status === 412 && !refreshed) {
        await refreshHashes();
        continue;
      }
      if ((response.status === 429 || response.status >= 500) && attempt < 5) {
        await sleep(1000 * (Number(response.headers.get('retry-after')) || 2 ** attempt));
        continue;
      }
      if (!response.ok) throw new Error(`Spotify risponde ${response.status} a ${op}`);
      const json = await response.json();
      if (json.errors && json.errors.length) throw new Error(`${op}: ${json.errors[0].message}`);
      return json.data;
    }
  };

  // Songs only: no podcast episodes, no files that live on someone's computer.
  const track = (data) => (data && data.__typename === 'Track' && data.name ? {
    title: data.name,
    artists: ((data.artists && data.artists.items) || []).map((a) => a && a.profile && a.profile.name).filter(Boolean),
    seconds: Math.round(((data.trackDuration || data.duration || {}).totalMilliseconds || 0) / 1000),
  } : null);

  // Every page of a paged query: read(data) gives one page's { items, count, total }, count being
  // the entries Spotify sent before any were filtered out.
  const pages = async (token, op, variables, size, read) => {
    const items = [];
    for (let offset = 0; ; offset += size) {
      const data = await query(token, op, { ...variables, offset, limit: size });
      let page;
      try {
        page = read(data);
      } catch (e) {
        throw new Error(`${op}: Spotify ha cambiato il formato delle risposte (${e.message})`);
      }
      items.push(...page.items);
      if (!page.count || offset + size >= page.total) return items;
    }
  };

  // Who is signed in. The uri tells your playlists from the ones you follow; without it they all go.
  const me = async (token) => {
    const data = await query(token, 'profileAttributes', {});
    if (!data || !data.me) throw new Error('Spotify non dice chi sei: accedi al tuo account');
    const profile = data.me.profile || {};
    return { name: profile.name || profile.username || '', uri: profile.uri || null };
  };

  const likedTracks = (token) => pages(token, 'fetchLibraryTracks', {}, 50, (data) => {
    const tracks = data.me.library.tracks;
    const items = tracks.items.map((item) => track(item.track && item.track.data)).filter(Boolean);
    return { items, count: tracks.items.length, total: tracks.totalCount };
  });

  const playlistTracks = (token, uri) => pages(token, 'fetchPlaylist', { uri, enableWatchFeedEntrypoint: false }, 100, (data) => {
    const content = data.playlistV2.content;
    const items = content.items.map((item) => track(item.itemV2 && item.itemV2.data)).filter(Boolean);
    return { items, count: content.items.length, total: content.totalCount };
  });

  const libraryPlaylists = (token) => pages(token, 'libraryV3', {
    filters: ['Playlists'], order: null, textFilter: '', features: ['LIKED_SONGS', 'YOUR_EPISODES_V2', 'PRERELEASES', 'EVENTS'],
    flatten: true, expandedFolders: [], folderUri: null, includeFoldersWhenFlattening: false,
  }, 50, (data) => {
    const library = data.me.libraryV3;
    const playlists = library.items.map((entry) => entry && entry.item)
      .filter((wrapper) => wrapper && wrapper.__typename === 'PlaylistResponseWrapper' && wrapper.data && wrapper.data.__typename === 'Playlist')
      .map((wrapper) => ({
        uri: wrapper.data.uri || wrapper._uri,
        name: wrapper.data.name,
        owner: wrapper.data.ownerV2 && wrapper.data.ownerV2.data && wrapper.data.ownerV2.data.uri,
      }));
    return { items: playlists, count: library.items.length, total: library.totalCount };
  });

  // The whole library, as the phone imports it. progress gets a line per step.
  const exportLibrary = async (token, progress = say) => {
    const profile = await me(token);
    progress(`Account: ${profile.name || profile.uri || 'collegato'}`);
    const out = { exported: new Date().toISOString(), playlists: [] };
    const liked = await likedTracks(token);
    out.playlists.push({ name: LIKED, tracks: liked });
    progress(`${LIKED}: ${liked.length} brani`);
    const library = await libraryPlaylists(token);
    // Telling yours from the others needs both your uri and the owners'; without them, all go.
    const canTell = ONLY_MINE && profile.uri && library.some((p) => p.owner);
    const playlists = canTell ? library.filter((p) => p.owner === profile.uri) : library;
    for (const playlist of playlists) {
      const tracks = await playlistTracks(token, playlist.uri);
      out.playlists.push({ name: playlist.name, tracks });
      progress(`${playlist.name}: ${tracks.length} brani`);
    }
    return out;
  };

  window.playerless = { exportLibrary, playlistTracks, likedTracks, libraryPlaylists, me, HASHES };
  // The bot loads this file into a page it watches from the start, and runs the export itself.
  if (window.__playerlessBot) return;

  // By hand: the token comes from the web player's next request.
  (async () => {
    say('Aspetto una richiesta del web player. Se entro qualche secondo non succede niente, clicca una playlist a sinistra.');
    const token = await new Promise((resolve) => {
      window.fetch = function (input, init) {
        try {
          const value = new Headers((init && init.headers) || (input instanceof Request ? input.headers : undefined)).get('authorization');
          if (/^Bearer \S+$/.test(value || '')) {
            window.fetch = nativeFetch;
            resolve(value);
          }
        } catch (e) {}
        return nativeFetch.apply(this, arguments);
      };
    });
    const out = await exportLibrary(token);
    const link = document.createElement('a');
    link.href = URL.createObjectURL(new Blob([JSON.stringify(out)], { type: 'application/json' }));
    link.download = 'playerless-spotify.json';
    document.body.appendChild(link);
    link.click();
    link.remove();
    const songs = out.playlists.reduce((sum, p) => sum + p.tracks.length, 0);
    say(`Fatto: ${out.playlists.length} playlist, ${songs} brani. Il file è nei Download.`);
  })().catch((error) => console.error('[Playerless] Esportazione fallita:', error));
})();
