'use strict';

// yt-dlp's solver (github.com/yt-dlp/ejs) finds the functions in YouTube's player that unscramble
// a stream link: one for the signature, one for the "n" parameter. Written for Deno, it fits a
// worker: no page around it, nothing to navigate away from.
importScripts('yt.solver.lib.min.js');
var meriyah = lib.meriyah;
var astring = lib.astring;
importScripts('yt.solver.core.js');

var solvers = null;

onmessage = function (event) {
  var message = event.data;
  try {
    if (message.type === 'load') {
      // Preparing a player takes seconds, so the result goes back to be saved and later just run.
      var code = message.prepared;
      var preprocessed = null;
      if (!code) {
        var out = jsc({ type: 'player', player: message.player, requests: [], output_preprocessed: true });
        if (out.type !== 'result') throw new Error(out.error);
        code = preprocessed = out.preprocessed_player;
      }
      var found = { n: null, sig: null };
      new Function('_result', code)(found);
      if (typeof found.n !== 'function' || typeof found.sig !== 'function') throw new Error('Funzioni del player non trovate');
      solvers = found;
      postMessage({ id: message.id, preprocessed: preprocessed });
    } else if (message.type === 'solve') {
      postMessage({
        id: message.id,
        result: { n: message.n ? solvers.n(message.n) : null, sig: message.sig ? solvers.sig(message.sig) : null },
      });
    }
  } catch (e) {
    postMessage({ id: message.id, error: String((e && e.message) || e) });
  }
};
