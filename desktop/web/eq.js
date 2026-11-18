'use strict';

// The equalizer, as on the phone: up to 12 bells from 20 Hz to 20 kHz, each a peaking filter
// from the Audio EQ Cookbook (Robert Bristow-Johnson). Web Audio's "peaking" filter is that very
// filter, so the curve drawn is the curve heard.
const Equalizer = {
  MIN_HZ: 20,
  MAX_HZ: 20000,
  MAX_DB: 12,
  MAX_POINTS: 12,
  DEFAULT_Q: 1,
  MIN_Q: 0.3,
  MAX_Q: 8,
  DRAW_RATE: 48000,
  HEADROOM_STEPS: 160,
  // Where a fresh equalizer puts its handles, all flat: something to grab right away.
  DEFAULT_POINTS: [60, 230, 910, 3600, 14000].map((hz) => ({ hz, db: 0, q: 1 })),

  state: { enabled: false, points: [] },

  // Saved as the phone saves it, [hz, db, q] per point, so an import is a plain copy.
  init() {
    const saved = Store.equalizer;
    const points = Array.isArray(saved.points)
      ? saved.points.filter((p) => Array.isArray(p) && p.length >= 3).map((p) => ({ hz: +p[0], db: +p[1], q: +p[2] }))
      : Equalizer.DEFAULT_POINTS;
    Equalizer.state = { enabled: !!saved.enabled, points };
  },

  // Heard at once, in the next block of audio.
  publish(state) {
    Equalizer.state = state;
    EqAudio.apply();
  },

  // Writing on every mouse movement would be wasteful: the screen saves when a gesture ends.
  save() {
    Store.equalizer = { enabled: Equalizer.state.enabled, points: Equalizer.state.points.map((p) => [p.hz, p.db, p.q]) };
    Store.save('equalizer');
  },

  // One peaking filter, normalised so a0 = 1.
  peaking(point, rate) {
    // Above Nyquist a filter can't exist; just under it, it still bends the top end.
    const hz = Math.min(point.hz, rate * 0.45);
    const a = Math.pow(10, point.db / 40);
    const w = (2 * Math.PI * hz) / rate;
    const alpha = Math.sin(w) / (2 * point.q);
    const cosW = Math.cos(w);
    const a0 = 1 + alpha / a;
    return {
      b0: (1 + alpha * a) / a0,
      b1: (-2 * cosW) / a0,
      b2: (1 - alpha * a) / a0,
      a1: (-2 * cosW) / a0,
      a2: (1 - alpha / a) / a0,
    };
  },

  // What a filter does to a sine at hz, in dB.
  gainDb(f, hz, rate) {
    const w = (2 * Math.PI * hz) / rate;
    const c1 = Math.cos(w), s1 = Math.sin(w), c2 = Math.cos(2 * w), s2 = Math.sin(2 * w);
    const numRe = f.b0 + f.b1 * c1 + f.b2 * c2;
    const numIm = f.b1 * s1 + f.b2 * s2;
    const denRe = 1 + f.a1 * c1 + f.a2 * c2;
    const denIm = f.a1 * s1 + f.a2 * s2;
    return 10 * Math.log10((numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm));
  },

  // A point at 0 dB changes nothing, so it isn't a filter.
  filters(points, rate = Equalizer.DRAW_RATE) {
    return points.filter((p) => p.db !== 0).map((p) => Equalizer.peaking(p, rate));
  },

  responseDb(filters, hz, rate = Equalizer.DRAW_RATE) {
    let total = 0;
    for (const f of filters) total += Equalizer.gainDb(f, hz, rate);
    return total;
  },

  // How far the curve rises above 0 dB anywhere. Everything is lowered by as much first, so a
  // boost can't push the music past full scale and distort.
  headroomDb(points) {
    const filters = Equalizer.filters(points);
    if (filters.length === 0) return 0;
    let top = 0;
    for (let i = 0; i <= Equalizer.HEADROOM_STEPS; i++) {
      top = Math.max(top, Equalizer.responseDb(filters, Equalizer.hzAt(i / Equalizer.HEADROOM_STEPS)));
    }
    return top;
  },

  // The frequency at a fraction of the way from 20 Hz to 20 kHz, on the log scale the ear uses.
  hzAt(fraction) {
    return Equalizer.MIN_HZ * Math.pow(Equalizer.MAX_HZ / Equalizer.MIN_HZ, fraction);
  },

  fractionOf(hz) {
    return Math.log10(hz / Equalizer.MIN_HZ) / Math.log10(Equalizer.MAX_HZ / Equalizer.MIN_HZ);
  },

  // Q from a 0..1 slider: 0 is narrowest, 1 widest, evenly spaced to the ear.
  qOf(width) {
    return Equalizer.MAX_Q * Math.pow(Equalizer.MIN_Q / Equalizer.MAX_Q, width);
  },

  widthOf(q) {
    return Math.log10(q / Equalizer.MAX_Q) / Math.log10(Equalizer.MIN_Q / Equalizer.MAX_Q);
  },

  // Bandwidth in octaves, a friendlier number than Q: Q 1.41 is one octave.
  octaves(q) {
    const x = 1 / (2 * q);
    return (2 * Math.log10(x + Math.sqrt(x * x + 1))) / Math.log10(2);
  },
};

// The player's audio on its way out: element, the filters, one gain for headroom and volume.
// Built at the first song, when the browser lets a page make sound.
const EqAudio = {
  context: null,
  source: null,
  filters: [],
  gain: null,
  // Whoever watches the music (the visuals) hangs a node here. It gets the song as recorded,
  // before the equalizer and the volume, so turning it down doesn't calm the picture.
  tap: null,

  listen(node) {
    if (EqAudio.tap) EqAudio.source.disconnect(EqAudio.tap);
    EqAudio.tap = node;
    if (node) EqAudio.source.connect(node);
  },

  attach(audio) {
    if (!EqAudio.context) {
      EqAudio.context = new AudioContext({ latencyHint: 'playback' });
      EqAudio.source = EqAudio.context.createMediaElementSource(audio);
      EqAudio.gain = EqAudio.context.createGain();
      EqAudio.gain.connect(EqAudio.context.destination);
      EqAudio.source.connect(EqAudio.gain);
      EqAudio.apply();
    }
    if (EqAudio.context.state === 'suspended') EqAudio.context.resume().catch(() => {});
  },

  // Moving a point retunes the filters in place, so dragging doesn't click; adding or removing
  // one rebuilds the chain.
  apply() {
    const context = EqAudio.context;
    if (!context) return;
    const state = Equalizer.state;
    const points = state.enabled ? state.points.filter((p) => p.db !== 0) : [];
    if (points.length !== EqAudio.filters.length) {
      EqAudio.source.disconnect();
      if (EqAudio.tap) EqAudio.source.connect(EqAudio.tap);
      EqAudio.filters.forEach((f) => f.disconnect());
      EqAudio.filters = points.map(() => {
        const filter = context.createBiquadFilter();
        filter.type = 'peaking';
        return filter;
      });
      let node = EqAudio.source;
      for (const filter of EqAudio.filters) {
        node.connect(filter);
        node = filter;
      }
      node.connect(EqAudio.gain);
    }
    const now = context.currentTime;
    points.forEach((p, i) => {
      const filter = EqAudio.filters[i];
      filter.frequency.setValueAtTime(Math.min(p.hz, context.sampleRate * 0.45), now);
      filter.Q.setValueAtTime(p.q, now);
      filter.gain.setValueAtTime(p.db, now);
    });
    const headroom = state.enabled ? Equalizer.headroomDb(state.points) : 0;
    const volume = Store.settings.volume == null ? 1 : Store.settings.volume;
    EqAudio.gain.gain.setTargetAtTime(volume * volume * Math.pow(10, -headroom / 20), now, 0.015);
  },
};

const GRID_HZ = [50, 100, 200, 500, 1000, 2000, 5000, 10000];
const GRID_LABELS = [[20, '20'], [100, '100'], [1000, '1k'], [10000, '10k'], [20000, '20k']];
const GRID_DB = [-12, -6, 6, 12];
// Within this of 0 dB a point lands on it exactly, so flattening one is easy.
const SNAP_DB = 0.3;

// The equalizer as a curve: frequency across, gain up and down. Click empty space to add a
// point, drag one to move it, double-click one to remove it, scroll over one to widen it.
class EqView {
  constructor(canvas, onChange) {
    this.canvas = canvas;
    this.onChange = onChange;
    this.points = [];
    this.active = true;
    this.selected = -1;
    this.dragging = -1;
    this.moved = false;
    this.down = null;
    this.wheelTimer = 0;

    canvas.addEventListener('pointerdown', (e) => this.pointerDown(e));
    canvas.addEventListener('pointermove', (e) => this.pointerMove(e));
    canvas.addEventListener('pointerup', (e) => this.pointerUp(e));
    canvas.addEventListener('pointercancel', (e) => this.pointerUp(e));
    canvas.addEventListener('dblclick', (e) => this.doubleClick(e));
    canvas.addEventListener('wheel', (e) => this.wheel(e), { passive: false });
    canvas.addEventListener('keydown', (e) => this.key(e));
    new ResizeObserver(() => this.draw()).observe(canvas);
  }

  get left() { return 12; }
  get right() { return this.canvas.clientWidth - 12; }
  get top() { return 12; }
  get bottom() { return this.canvas.clientHeight - 20; }

  xOf(hz) { return this.left + Equalizer.fractionOf(hz) * (this.right - this.left); }

  hzOf(x) {
    return Equalizer.hzAt(Math.min(1, Math.max(0, (x - this.left) / (this.right - this.left))));
  }

  yOf(db) { return this.top + ((Equalizer.MAX_DB - db) / (2 * Equalizer.MAX_DB)) * (this.bottom - this.top); }

  dbOf(y) {
    const db = Equalizer.MAX_DB - ((y - this.top) / (this.bottom - this.top)) * 2 * Equalizer.MAX_DB;
    return Math.abs(db) < SNAP_DB ? 0 : Math.min(Equalizer.MAX_DB, Math.max(-Equalizer.MAX_DB, db));
  }

  pointAt(x, y) {
    let best = -1;
    let bestDistance = 14;
    this.points.forEach((p, i) => {
      const distance = Math.hypot(x - this.xOf(p.hz), y - this.yOf(p.db));
      if (distance < bestDistance) {
        best = i;
        bestDistance = distance;
      }
    });
    return best;
  }

  position(e) {
    const box = this.canvas.getBoundingClientRect();
    return [e.clientX - box.left, e.clientY - box.top];
  }

  pointerDown(e) {
    if (e.button !== 0) return;
    const [x, y] = this.position(e);
    this.canvas.setPointerCapture(e.pointerId);
    this.dragging = this.pointAt(x, y);
    this.moved = false;
    this.down = [x, y];
    if (this.dragging >= 0) {
      this.selected = this.dragging;
      this.draw();
      this.onChange(false);
    }
  }

  pointerMove(e) {
    const [x, y] = this.position(e);
    if (this.dragging < 0) {
      this.canvas.style.cursor = this.pointAt(x, y) >= 0 ? 'grab' : 'crosshair';
      return;
    }
    // A click shouldn't nudge the point it lands on.
    if (!this.moved && Math.hypot(x - this.down[0], y - this.down[1]) < 3) return;
    this.moved = true;
    this.canvas.style.cursor = 'grabbing';
    this.points = this.points.map((p, i) => (i === this.dragging ? { ...p, hz: this.hzOf(x), db: this.dbOf(y) } : p));
    this.draw();
    this.onChange(false);
  }

  pointerUp(e) {
    if (!this.down) return;
    const [x, y] = this.down;
    this.down = null;
    if (this.dragging >= 0) {
      this.dragging = -1;
      this.canvas.style.cursor = 'grab';
      this.onChange(true);
    } else if (e.type === 'pointerup' && this.points.length < Equalizer.MAX_POINTS) {
      this.points = this.points.concat({ hz: this.hzOf(x), db: this.dbOf(y), q: Equalizer.DEFAULT_Q });
      this.selected = this.points.length - 1;
      this.draw();
      this.onChange(true);
    }
  }

  doubleClick(e) {
    const [x, y] = this.position(e);
    const hit = this.pointAt(x, y);
    if (hit >= 0) this.remove(hit);
  }

  key(e) {
    if ((e.key === 'Delete' || e.key === 'Backspace') && this.selected >= 0) {
      e.preventDefault();
      this.remove(this.selected);
    }
  }

  remove(index) {
    this.points = this.points.filter((_, i) => i !== index);
    this.selected = -1;
    this.dragging = -1;
    this.draw();
    this.onChange(true);
  }

  // The wheel widens or narrows the point under the mouse, or the selected one.
  wheel(e) {
    const [x, y] = this.position(e);
    const hit = this.pointAt(x, y) >= 0 ? this.pointAt(x, y) : this.selected;
    if (hit < 0) return;
    e.preventDefault();
    this.selected = hit;
    const width = Math.min(1, Math.max(0, Equalizer.widthOf(this.points[hit].q) + (e.deltaY < 0 ? 0.04 : -0.04)));
    this.points = this.points.map((p, i) => (i === hit ? { ...p, q: Equalizer.qOf(width) } : p));
    this.draw();
    this.onChange(false);
    clearTimeout(this.wheelTimer);
    this.wheelTimer = setTimeout(() => this.onChange(true), 400);
  }

  draw() {
    const canvas = this.canvas;
    const ratio = window.devicePixelRatio || 1;
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    if (width === 0 || height === 0) return;
    if (canvas.width !== Math.round(width * ratio) || canvas.height !== Math.round(height * ratio)) {
      canvas.width = Math.round(width * ratio);
      canvas.height = Math.round(height * ratio);
    }
    const g = canvas.getContext('2d');
    g.setTransform(ratio, 0, 0, ratio, 0, 0);
    g.fillStyle = '#141414';
    g.fillRect(0, 0, width, height);

    g.lineWidth = 1;
    g.strokeStyle = '#262626';
    g.beginPath();
    for (const hz of GRID_HZ) {
      g.moveTo(this.xOf(hz), this.top);
      g.lineTo(this.xOf(hz), this.bottom);
    }
    for (const db of GRID_DB) {
      g.moveTo(this.left, this.yOf(db));
      g.lineTo(this.right, this.yOf(db));
    }
    g.stroke();
    const zero = this.yOf(0);
    g.strokeStyle = '#4A4A4A';
    g.beginPath();
    g.moveTo(this.left, zero);
    g.lineTo(this.right, zero);
    g.stroke();

    g.fillStyle = '#808080';
    g.font = '11px "Segoe UI", sans-serif';
    for (const [hz, label] of GRID_LABELS) {
      const textWidth = g.measureText(label).width;
      g.fillText(label, Math.min(width - textWidth, Math.max(0, this.xOf(hz) - textWidth / 2)), height - 5);
    }
    for (const db of GRID_DB) g.fillText(db > 0 ? '+' + db : String(db), this.left + 3, this.yOf(db) - 3);

    // The real response of the filters, sampled every couple of pixels.
    const color = this.active ? accentColor() : '#808080';
    const filters = Equalizer.filters(this.points);
    const steps = Math.max(2, Math.floor((this.right - this.left) / 2));
    const curve = new Path2D();
    for (let i = 0; i <= steps; i++) {
      const x = this.left + ((this.right - this.left) * i) / steps;
      const db = Math.min(Equalizer.MAX_DB, Math.max(-Equalizer.MAX_DB, Equalizer.responseDb(filters, this.hzOf(x))));
      if (i === 0) curve.moveTo(x, this.yOf(db));
      else curve.lineTo(x, this.yOf(db));
    }
    const fill = new Path2D(curve);
    fill.lineTo(this.right, zero);
    fill.lineTo(this.left, zero);
    fill.closePath();
    g.globalAlpha = 0.22;
    g.fillStyle = color;
    g.fill(fill);
    g.globalAlpha = 1;

    g.lineWidth = 2.5;
    g.lineJoin = 'round';
    g.strokeStyle = color;
    g.stroke(curve);

    this.points.forEach((p, i) => {
      g.beginPath();
      g.arc(this.xOf(p.hz), this.yOf(p.db), 7, 0, 2 * Math.PI);
      g.fillStyle = color;
      g.fill();
      if (i === this.selected) {
        g.beginPath();
        g.arc(this.xOf(p.hz), this.yOf(p.db), 11, 0, 2 * Math.PI);
        g.lineWidth = 2;
        g.strokeStyle = '#FFFFFF';
        g.stroke();
      }
    });
  }
}

const WIDTH_STEPS = 100;

// The equalizer screen: the curve, the selected point's width, on and off, back to flat. Built
// the first time it's opened.
class EqScreen {
  constructor(root) {
    this.root = root;
    root.innerHTML = `
      <canvas class="eq-graph" tabindex="0" aria-label="Equalizzatore"></canvas>
      <p class="eq-info"></p>
      <label class="eq-width-label">Larghezza del punto</label>
      <input class="eq-width bar" type="range" min="0" max="${WIDTH_STEPS}" aria-label="Larghezza del punto">
      <div class="eq-row">
        <label class="switch"><input class="eq-enabled" type="checkbox"><span></span>Attivo</label>
        <button class="text-button eq-reset">Azzera</button>
      </div>`;
    this.info = root.querySelector('.eq-info');
    this.width = root.querySelector('.eq-width');
    this.widthLabel = root.querySelector('.eq-width-label');
    this.enabled = root.querySelector('.eq-enabled');
    this.graph = new EqView(root.querySelector('.eq-graph'), (done) => this.changed(done));

    this.enabled.addEventListener('change', () => {
      this.graph.active = this.enabled.checked;
      this.graph.draw();
      this.changed(true);
    });
    this.width.addEventListener('input', () => {
      const i = this.graph.selected;
      if (i < 0) return;
      const q = Equalizer.qOf(Number(this.width.value) / WIDTH_STEPS);
      this.graph.points = this.graph.points.map((p, j) => (j === i ? { ...p, q } : p));
      this.graph.draw();
      this.changed(false);
    });
    this.width.addEventListener('change', () => this.changed(true));
    root.querySelector('.eq-reset').addEventListener('click', () => {
      this.graph.points = Equalizer.DEFAULT_POINTS;
      this.graph.selected = -1;
      this.graph.draw();
      this.changed(true);
    });
    this.load();
  }

  // From the saved state: on opening, and after an import.
  load() {
    const state = Equalizer.state;
    this.graph.points = state.points;
    this.graph.active = state.enabled;
    this.graph.selected = -1;
    this.enabled.checked = state.enabled;
    this.graph.draw();
    this.describe();
  }

  // Heard at once; written to disk only when a gesture ends.
  changed(done) {
    Equalizer.publish({ enabled: this.enabled.checked, points: this.graph.points });
    if (done) Equalizer.save();
    this.describe();
  }

  describe() {
    const point = this.graph.points[this.graph.selected];
    // The width belongs to a point: with none selected there's nothing to show.
    this.width.style.visibility = point ? 'visible' : 'hidden';
    this.widthLabel.style.visibility = this.width.style.visibility;
    let text;
    if (!point) {
      text = 'Clicca sul grafico per aggiungere un punto, fino a 12. Trascinalo per spostarlo, ' +
        'doppio clic per toglierlo, rotellina per allargarlo o stringerlo.';
    } else {
      this.width.value = Math.round(Equalizer.widthOf(point.q) * WIDTH_STEPS);
      const hz = point.hz < 1000 ? `${Math.round(point.hz)} Hz` : `${oneDecimal(point.hz / 1000)} kHz`;
      text = `${hz} · ${signedDecimal(point.db)} dB · larga ${oneDecimal(Equalizer.octaves(point.q))} ottave`;
    }
    // Boosts cost loudness: say how much, so a quieter song isn't a mystery.
    const headroom = this.enabled.checked ? Equalizer.headroomDb(this.graph.points) : 0;
    if (headroom >= 0.05) text += `\nVolume generale ${signedDecimal(-headroom)} dB, perché i picchi non distorcano`;
    this.info.textContent = text;
  }
}
