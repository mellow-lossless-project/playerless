'use strict';

// The visuals: a full-window scene that moves with the music. Four of them, each one shader drawn
// by the graphics card. Nothing here runs, and no graphics memory is held, while the screen is
// closed.

// What every scene is told about the music, each frame.
const VISUAL_UNIFORMS = ['uRes', 'uTime', 'uBass', 'uMid', 'uHigh', 'uLevel', 'uKick', 'uBeatAge', 'uBeats', 'uDrive', 'uHue'];

// One triangle that covers the window: the scene is painted by the fragment shader.
const VISUAL_QUAD = `#version 300 es
void main() {
  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}`;

const VISUAL_HEAD = `#version 300 es
precision highp float;
uniform vec2 uRes;
uniform float uTime, uBass, uMid, uHigh, uLevel, uKick, uBeatAge, uBeats, uDrive, uHue;
out vec4 fragColor;
const float TAU = 6.28318530718;

mat2 rot(float a) {
  float c = cos(a), s = sin(a);
  return mat2(c, -s, s, c);
}

float hash21(vec2 p) {
  p = fract(p * vec2(123.34, 456.21));
  p += dot(p, p + 45.32);
  return fract(p.x * p.y);
}

// The colour wheel: three cosines a third of a turn apart.
vec3 pal(float t) {
  return 0.5 + 0.5 * cos(TAU * (t + vec3(0.0, 0.33, 0.67)));
}

// Bright colours roll off instead of clipping, the corners darken, a little noise hides banding.
vec4 finish(vec3 c) {
  vec2 q = gl_FragCoord.xy / uRes - 0.5;
  c = 1.0 - exp(-max(c, 0.0) * 1.3);
  c *= 1.0 - 0.9 * dot(q, q);
  c += (hash21(gl_FragCoord.xy + fract(uTime)) - 0.5) / 255.0;
  return vec4(c, 1.0);
}
`;

// A flight down a bending tube of neon panels. Every kick sends a ring of light ahead and
// relights the panels; the louder the song, the faster the flight.
const TUNNEL = VISUAL_HEAD + `
// Everything along the tube repeats after LOOP, so the flight can go on for hours: the distance
// flown starts over there and nothing jumps.
const float LOOP = 628.318530718;
const float RINGS = 480.0;
const float SIDES = 20.0;

vec2 path(float z) {
  return vec2(sin(z * 0.21) * 1.5 + sin(z * 0.09), cos(z * 0.17) * 1.2 + sin(z * 0.07 + 1.0) * 0.8);
}

void main() {
  vec2 uv = (gl_FragCoord.xy * 2.0 - uRes) / uRes.y;
  float z = uDrive * 6.0;
  vec3 ro = vec3(path(z), z);
  vec3 fw = normalize(vec3(path(z + 3.0), z + 3.0) - ro);
  vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
  vec3 up = cross(fw, rt);
  uv *= rot(sin(uTime * 0.13) * 0.7 + sin(z * 0.05) * 0.5);
  // A kick widens the view for a moment: it feels like a push forward.
  vec3 rd = normalize(fw * (0.85 - uKick * 0.12) + rt * uv.x + up * uv.y);

  float t = 0.0;
  for (int i = 0; i < 64; i++) {
    vec3 p = ro + rd * t;
    float radius = 2.0 + 0.2 * uBass * sin(p.z * 1.2 - uTime * 4.0);
    float d = radius - length(p.xy - path(p.z));
    if (d < 0.003 || t > 90.0) break;
    t += d * 0.85;
  }

  vec3 p = ro + rd * t;
  vec2 q = p.xy - path(p.z);
  float a = atan(q.y, q.x) / TAU + 0.5 + 0.08 * sin(p.z * 0.05);
  vec2 c = vec2(a * SIDES, p.z * RINGS / LOOP);
  vec2 id = mod(floor(c), vec2(SIDES, RINGS));
  vec2 f = fract(c);
  // How far the nearest panel border is, in the tube's own units.
  float edge = min(min(f.x, 1.0 - f.x) * 0.63, min(f.y, 1.0 - f.y) * 1.31);
  float line = 1.0 - smoothstep(0.0, 0.03 + t * 0.003, edge);
  float glow = exp(-edge * 10.0);
  float rnd = hash21(id + 0.5);
  // Some panels are lit, more of them the louder it gets; every kick picks others.
  float lit = step(0.86 - uLevel * 0.3, fract(rnd + uBeats * 0.618));
  float fill = lit * smoothstep(0.0, 0.3, edge) * (0.15 + uKick * 1.1);
  float wave = exp(-pow((p.z - ro.z - uBeatAge * 55.0) * 0.3, 2.0)) * exp(-uBeatAge * 1.4);

  // The colour changes along the tube, and only a little around it.
  float hue = p.z * 19.0 / LOOP + 0.15 * sin(TAU * a) + uHue;
  vec3 tint = pal(hue);
  vec3 col = tint * (line * 1.3 + glow * 0.35)
    + pal(hue + 0.4 + rnd * 0.2) * fill
    + (tint + 0.4) * wave * (0.25 + line + glow);
  // The far end is a light the bass turns up.
  float far = 1.0 - exp(-t * 0.03);
  col = col * exp(-t * 0.05) + pal(uHue + 0.55) * far * far * (0.45 + uBass * 0.9);
  fragColor = finish(col);
}`;

// The eighties: a grid road to a striped sun, between mountains the bass pushes up.
const SYNTHWAVE = VISUAL_HEAD + `
// The mountains repeat after WRAP squares of road, so the road never ends.
const float WRAP = 1024.0;
const vec3 HAZE = vec3(0.8, 0.2, 0.45);

// The height of one corner of the grid: flat on the road, rising at the sides.
float corner(vec2 i) {
  i.y = mod(i.y, WRAP);
  float side = smoothstep(1.5, 8.0, abs(i.x));
  float big = 0.5 + 0.5 * sin(i.x * 0.43 + 2.0 * sin(i.y * 0.19021362));
  big *= 0.6 + 0.4 * sin(i.y * 0.30679616 + i.x * 0.2);
  return side * (hash21(i) * 0.6 + big * 1.6) * (0.6 + uBass * 1.5);
}

// Flat faces between the corners, like the low-poly originals.
float terrain(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  return mix(mix(corner(i), corner(i + vec2(1.0, 0.0)), f.x),
    mix(corner(i + vec2(0.0, 1.0)), corner(i + vec2(1.0, 1.0)), f.x), f.y);
}

vec3 sky(vec3 rd) {
  float y = max(rd.y, 0.0);
  vec3 c = mix(vec3(1.0, 0.25, 0.55), vec3(0.16, 0.03, 0.32), smoothstep(0.0, 0.3, y));
  c = mix(c, vec3(0.01, 0.0, 0.05), smoothstep(0.2, 0.75, y));

  // Stars, twinkling with the cymbals.
  vec2 g = rd.xy / rd.z * 60.0;
  vec2 id = floor(g);
  float star = step(0.985, hash21(id)) * (1.0 - smoothstep(0.0, 0.3, length(fract(g) - 0.5)));
  float twinkle = 0.5 + 0.5 * sin(uTime * 3.0 + hash21(id + 7.0) * 40.0);
  c += star * smoothstep(0.1, 0.5, y) * (0.3 + twinkle * (0.4 + uHigh * 1.2));

  // The sun, cut by gaps that widen towards the horizon.
  vec2 s = rd.xy / rd.z - vec2(0.0, 0.22);
  float r = 0.28 + 0.02 * uBass + 0.025 * uKick;
  float d = length(s);
  float h = (s.y + r) / (2.0 * r);
  float gap = (0.5 - h) * 1.1;
  float kept = smoothstep(gap - 0.03, gap + 0.03, fract(s.y * 13.0 - uTime * 0.35));
  float disc = (1.0 - smoothstep(r - 0.006, r, d)) * kept;
  vec3 sun = mix(vec3(1.0, 0.15, 0.55), vec3(1.0, 0.92, 0.25), clamp(h, 0.0, 1.0));
  c = mix(c, sun * (1.1 + uKick * 0.4), disc);
  c += vec3(1.0, 0.3, 0.5) * exp(-d * 3.2) * (0.3 + uBass * 0.45);
  return c;
}

void main() {
  vec2 uv = (gl_FragCoord.xy * 2.0 - uRes) / uRes.y;
  uv *= rot(sin(uTime * 0.11) * 0.03);
  vec3 ro = vec3(sin(uTime * 0.17) * 0.5, 0.8 + 0.06 * sin(uTime * 0.7), 0.0);
  vec3 rd = normalize(vec3(uv.x, uv.y + 0.1, 1.5));
  vec2 scroll = vec2(0.0, uDrive * 4.0);
  vec3 col = sky(rd);

  float t = 0.0, before = 0.0;
  bool hit = false;
  for (int i = 0; i < 96; i++) {
    vec3 p = ro + rd * t;
    float above = p.y - terrain(p.xz + scroll);
    if (above < 0.0) {
      hit = true;
      break;
    }
    if (t > 70.0 || (rd.y > 0.0 && p.y > 4.8)) break;
    before = t;
    t += max(above * 0.4, 0.02 + t * 0.012);
  }

  if (hit) {
    // The steps are coarse: halving the last one a few times makes the grid lines crisp.
    for (int i = 0; i < 5; i++) {
      float middle = 0.5 * (before + t);
      vec3 p = ro + rd * middle;
      if (p.y < terrain(p.xz + scroll)) t = middle;
      else before = middle;
    }
    vec3 p = ro + rd * t;
    vec2 dl = 0.5 - abs(fract(p.xz + scroll) - 0.5);
    float line = 1.0 - smoothstep(0.0, 0.03 + t * 0.006, min(dl.x, dl.y));
    float lift = clamp(p.y * 0.3, 0.0, 1.0);
    vec3 neon = mix(vec3(0.1, 0.85, 1.0), vec3(1.0, 0.15, 0.75), clamp(lift + 0.35 + 0.35 * sin(uTime * 0.23), 0.0, 1.0));
    // A kick lights a band of the grid that runs from the horizon to the viewer.
    float wave = exp(-pow((t - 45.0 + uBeatAge * 70.0) * 0.25, 2.0)) * exp(-uBeatAge * 1.5);
    vec3 ground = vec3(0.04, 0.0, 0.09) + vec3(0.3, 0.02, 0.35) * lift * lift;
    ground += neon * line * (1.0 + uKick * 1.2 + wave * 2.5);
    col = mix(HAZE, ground, exp(-t * 0.035));
  }
  fragColor = finish(col);
}`;

// A drop of liquid metal that the bass swells, the voices wrinkle and the cymbals make shiver.
const SPHERE = VISUAL_HEAD + `
mat3 spin;
float flow;

float swell(vec3 p) {
  vec3 q = spin * p;
  float a = sin(q.x * 2.6 + flow) * sin(q.y * 2.9 + flow * 1.3) * sin(q.z * 2.3 + flow * 0.7);
  float b = sin(q.x * 6.1 + flow * 2.0) * sin(q.y * 7.3 - flow * 1.7) * sin(q.z * 6.7 + flow);
  float c = sin(q.x * 15.0 - flow * 3.0) * sin(q.y * 17.0 + flow * 2.3) * sin(q.z * 13.0 + flow * 2.9);
  return a * (0.14 + uBass * 0.38) + b * (0.03 + uMid * 0.13) + c * uHigh * 0.035;
}

float map(vec3 p) {
  return length(p) - 1.0 - 0.1 * uBass - swell(p);
}

vec3 normal(vec3 p) {
  vec2 e = vec2(0.002, -0.002);
  return normalize(e.xyy * map(p + e.xyy) + e.yyx * map(p + e.yyx) + e.yxy * map(p + e.yxy) + e.xxx * map(p + e.xxx));
}

void main() {
  vec2 uv = (gl_FragCoord.xy * 2.0 - uRes) / uRes.y;
  flow = uDrive * 2.0;
  float ca = cos(uTime * 0.23), sa = sin(uTime * 0.23), cb = cos(uTime * 0.17), sb = sin(uTime * 0.17);
  spin = mat3(ca, 0.0, -sa, 0.0, 1.0, 0.0, sa, 0.0, ca) * mat3(1.0, 0.0, 0.0, 0.0, cb, sb, 0.0, -sb, cb);
  vec3 ro = vec3(0.0, 0.0, 3.6 - uKick * 0.2);
  vec3 rd = normalize(vec3(uv, -1.7));

  // Behind it: slow rays, a halo, and a ring that every kick throws outwards.
  float r = length(uv);
  float angle = atan(uv.y, uv.x);
  float rays = pow(0.5 + 0.5 * sin(angle * 7.0 + uTime * 0.3 + 2.0 * sin(angle * 3.0 - uTime * 0.2)), 3.0);
  vec3 col = pal(uHue + 0.5) * rays * exp(-r * 1.3) * (0.08 + uMid * 0.35);
  col += pal(uHue + 0.1) * exp(-max(r - 0.5, 0.0) * 4.0) * (0.12 + uLevel * 0.3);
  col += pal(uHue + 0.3) * exp(-pow((r - 0.5 - uBeatAge * 2.4) * 12.0, 2.0)) * exp(-uBeatAge * 3.0) * 0.8;

  // Only the rays that cross the ball the drop can't leave are worth marching.
  float b = dot(ro, rd);
  float inside = b * b - dot(ro, ro) + 1.85 * 1.85;
  if (inside > 0.0) {
    float t = -b - sqrt(inside), end = -b + sqrt(inside);
    float glow = 0.0;
    bool hit = false;
    for (int i = 0; i < 80; i++) {
      float d = map(ro + rd * t);
      if (d < 0.002) {
        hit = true;
        break;
      }
      glow += exp(-d * 7.0);
      t += d * 0.4;
      if (t > end) break;
    }
    col += pal(uHue + 0.2) * glow * 0.012 * (0.5 + uBass);
    if (hit) {
      vec3 p = ro + rd * t;
      vec3 n = normal(p);
      vec3 mirror = reflect(rd, n);
      float rim = pow(1.0 - max(dot(n, -rd), 0.0), 2.5);
      vec3 body = pal(swell(p) * 0.6 + n.y * 0.12 + uHue);
      // There is no room to mirror, so it mirrors bands of colour.
      vec3 room = pal(mirror.y * 0.5 + mirror.x * 0.25 + uHue + 0.35) * (0.55 + 0.45 * sin(mirror.y * 7.0 + uTime * 0.8));
      vec3 key = normalize(vec3(0.6, 0.7, 0.5)), back = normalize(vec3(-0.7, -0.4, 0.4));
      col = body * (0.05 + 0.4 * max(dot(n, key), 0.0) + 0.2 * max(dot(n, back), 0.0));
      col += room * (0.2 + rim * 1.3);
      col += vec3(1.3) * pow(max(dot(mirror, key), 0.0), 48.0);
      col += pal(uHue + 0.5) * pow(max(dot(mirror, back), 0.0), 16.0) * 0.6;
      col += body * uKick * 0.35;
    }
  }
  fragColor = finish(col);
}`;

const GALAXY_POINTS = 120000;

// A spiral galaxy of points of light. The bass makes it breathe and ripple, every kick sends a
// shock wave from the core to the rim.
const GALAXY_VERTEX = `#version 300 es
precision highp float;
// Four random numbers per point: where it sits never changes, so nothing is uploaded per frame.
layout(location = 0) in vec4 aSeed;
uniform vec2 uRes;
uniform float uTime, uBass, uMid, uHigh, uLevel, uKick, uBeatAge, uDrive, uHue;
out vec3 vColor;
const float TAU = 6.28318530718;

vec3 pal(float t) {
  return 0.5 + 0.5 * cos(TAU * (t + vec3(0.0, 0.33, 0.67)));
}

void main() {
  vec3 p;
  vec3 col;
  float size;
  if (aSeed.x > 0.94) {
    // The few left over are the stars far behind.
    float u = aSeed.y * 2.0 - 1.0, around = aSeed.z * TAU, s = sqrt(1.0 - u * u);
    p = vec3(s * cos(around), u, s * sin(around)) * (22.0 + aSeed.w * 14.0);
    float twinkle = 0.5 + 0.5 * sin(uTime * (1.0 + aSeed.w * 3.0) + aSeed.y * 90.0);
    col = mix(vec3(0.7, 0.8, 1.0), vec3(1.0, 0.85, 0.7), aSeed.w) * (0.25 + twinkle * (0.2 + uHigh * 0.8));
    size = 0.05 + 0.07 * aSeed.w;
  } else {
    float arm = floor(aSeed.x / 0.94 * 4.0);
    float r = 0.15 + pow(aSeed.y, 1.3) * 6.5;
    // Most points keep close to the middle of their arm.
    float off = aSeed.z - 0.5;
    off *= abs(off) * 4.0;
    float angle = arm / 4.0 * TAU + r * (0.75 + uMid * 0.12) + off * (1.4 / (0.5 + r * 0.4));
    // The core turns faster than the rim, as in a real one.
    angle += uDrive * (0.08 + 0.5 / (1.0 + r));
    float up = aSeed.w - 0.5;
    p = vec3(cos(angle) * r, up * (0.12 + exp(-r * 0.55)), sin(angle) * r);
    p.xz *= 1.0 + uBass * 0.18;
    p.y += sin(r * 2.4 - uTime * 2.5) * 0.22 * uBass * smoothstep(0.0, 1.5, r);
    float shock = exp(-pow((r - uBeatAge * 9.0) * 0.9, 2.0)) * exp(-uBeatAge * 1.6);
    p.y += shock * (up * 4.0 + 0.5 * sin(aSeed.z * 40.0));
    p.xz *= 1.0 + shock * 0.12;
    p += vec3(sin(aSeed.z * 311.0 + uTime * 7.0), sin(aSeed.w * 173.0 + uTime * 9.0), sin(aSeed.y * 97.0 + uTime * 8.0)) * uHigh * 0.06;

    float core = exp(-r * 0.9);
    col = mix(pal(0.6 + r * 0.085 + arm * 0.03 + uHue), vec3(1.0, 0.9, 0.75), core);
    col *= 0.2 + 0.15 * core + shock * 0.6 + uKick * 0.12 + uLevel * 0.18;
    size = 0.03 + 0.08 * pow(fract(aSeed.w * 37.0), 8.0) + shock * 0.04;
    // A few are wide and faint instead: together they're the haze the arms glow in.
    if (fract(aSeed.w * 91.0) < 0.012) {
      size = 1.3;
      col *= 0.05;
    }
  }

  // The camera circles slowly, higher and lower, and leans in on the bass.
  float around = uTime * 0.06, height = 0.55 + 0.25 * sin(uTime * 0.11);
  vec3 ro = (11.0 - uKick * 0.5 - uBass * 0.8) * vec3(cos(height) * sin(around), sin(height), cos(height) * cos(around));
  vec3 fw = normalize(-ro);
  vec3 rt = normalize(cross(fw, vec3(0.0, 1.0, 0.0)));
  vec3 q = p - ro;
  vec3 v = vec3(dot(q, rt), dot(q, cross(rt, fw)), dot(q, fw));
  gl_Position = vec4(v.x * 1.7 * uRes.y / uRes.x, v.y * 1.7, 0.0, v.z);
  gl_PointSize = clamp(size * 0.85 * uRes.y / max(v.z, 0.1), 1.5, 200.0);
  vColor = col;
}`;

const GALAXY_FRAGMENT = `#version 300 es
precision mediump float;
in vec3 vColor;
out vec4 fragColor;

void main() {
  vec2 c = gl_PointCoord * 2.0 - 1.0;
  float d = dot(c, c);
  if (d > 1.0) discard;
  fragColor = vec4(vColor * exp(-d * 4.0), 1.0);
}`;

// "loop" is where a scene's travel starts over without a visible jump: the numbers a shader works
// with are too coarse to count a whole evening of flight.
const VISUAL_SCENES = [
  { id: 'tunnel', name: 'Tunnel', fragment: TUNNEL, loop: (200 * Math.PI) / 6 },
  { id: 'synthwave', name: 'Synthwave', fragment: SYNTHWAVE, loop: 256 },
  { id: 'galaxy', name: 'Galassia', vertex: GALAXY_VERTEX, fragment: GALAXY_FRAGMENT, points: GALAXY_POINTS, loop: 0 },
  { id: 'sphere', name: 'Sfera liquida', fragment: SPHERE, loop: 10 * Math.PI },
];

// The music as a few numbers from 0 to 1: how much bass, voice and treble there is right now, and
// when the last kick landed. Each is measured against the loudest the song has recently been, so
// a quiet song moves the scenes as much as a loud one.
class Pulse {
  constructor() {
    this.bass = 0;
    this.mid = 0;
    this.high = 0;
    this.level = 0;
    this.kick = 0;
    this.beatAge = 10;
    this.beats = 0;
    this.drive = 0;
    this.peaks = [Pulse.FLOOR, Pulse.FLOOR, Pulse.FLOOR];
    this.average = 0;
    this.previous = 0;
    this.analyser = null;
    this.bins = null;
    this.ranges = [];

    const context = EqAudio.context;
    if (!context) return;
    const analyser = context.createAnalyser();
    analyser.fftSize = 2048;
    analyser.smoothingTimeConstant = 0.4;
    analyser.minDecibels = -90;
    analyser.maxDecibels = -12;
    this.analyser = analyser;
    this.bins = new Uint8Array(analyser.frequencyBinCount);
    const hzPerBin = context.sampleRate / analyser.fftSize;
    this.ranges = Pulse.BANDS_HZ.map(([from, to]) => {
      const first = Math.max(1, Math.round(from / hzPerBin));
      return [first, Math.max(first + 1, Math.min(this.bins.length, Math.round(to / hzPerBin)))];
    });
    EqAudio.listen(analyser);
  }

  release() {
    if (this.analyser) EqAudio.listen(null);
    this.analyser = null;
  }

  update(dt) {
    const now = [0, 0, 0];
    if (this.analyser) {
      this.analyser.getByteFrequencyData(this.bins);
      this.ranges.forEach(([from, to], band) => {
        let sum = 0;
        for (let i = from; i < to; i++) sum += this.bins[i] * this.bins[i];
        now[band] = sum / ((to - from) * 255 * 255);
      });
    }
    const levels = [this.bass, this.mid, this.high].map((level, band) => {
      this.peaks[band] = Math.max(now[band], this.peaks[band] * Math.exp(-dt / Pulse.PEAK_S), Pulse.FLOOR);
      // What stays under a third of the peak is the song's floor, not a hit: it counts as nothing,
      // so the scenes move between rest and full instead of hovering near the top.
      const share = Math.max(0, now[band] / this.peaks[band] - Pulse.GATE) / (1 - Pulse.GATE);
      const target = share * share;
      // Up at once, down slowly: a hit is seen, and doesn't flicker.
      const seconds = target > level ? Pulse.ATTACK_S : Pulse.RELEASE_S;
      return level + (target - level) * (1 - Math.exp(-dt / seconds));
    });
    [this.bass, this.mid, this.high] = levels;
    this.level = 0.5 * this.bass + 0.3 * this.mid + 0.2 * this.high;

    // A kick: the bass jumping well above what it has been for the last moment.
    const bass = now[0];
    this.average += (bass - this.average) * (1 - Math.exp(-dt / Pulse.AVERAGE_S));
    this.beatAge += dt;
    this.kick *= Math.exp(-dt / Pulse.KICK_S);
    if (bass > this.average * 1.25 && bass > this.peaks[0] * 0.3 && bass > this.previous && this.beatAge > Pulse.BEAT_GAP_S) {
      this.kick = 1;
      this.beatAge = 0;
      this.beats = (this.beats + 1) % 1000;
    }
    this.previous = bass;
    // How far the scenes have travelled: slowly in silence, fast when it's loud.
    this.drive += dt * (0.3 + 1.2 * this.level + 0.5 * this.kick);
  }
}

Pulse.BANDS_HZ = [[30, 150], [150, 2500], [2500, 12000]];
// Below this a band counts as silence, so hiss doesn't get stretched into movement.
Pulse.FLOOR = 0.03;
Pulse.GATE = 0.3;
Pulse.PEAK_S = 8;
Pulse.ATTACK_S = 0.025;
Pulse.RELEASE_S = 0.2;
Pulse.AVERAGE_S = 0.7;
Pulse.KICK_S = 0.2;
// No song has kicks closer than this.
Pulse.BEAT_GAP_S = 0.22;

// The canvas and its frames, for as long as the visuals are open.
class VisualStage {
  constructor(canvas, onError) {
    this.canvas = canvas;
    this.onError = onError;
    this.gl = canvas.getContext('webgl2', { antialias: false, alpha: false, depth: false, stencil: false, powerPreference: 'high-performance' });
    if (!this.gl) throw new Error('WebGL 2 non disponibile');
    this.programs = new Map();
    this.pulse = new Pulse();
    this.scene = VISUAL_SCENES[0];
    this.scale = 1;
    this.frames = 0;
    this.seconds = 0;
    this.started = 0;
    this.last = 0;
    this.request = 0;
    this.disposed = false;
    this.tick = (now) => this.frame(now);
    // The graphics driver may take the canvas away (a driver update, a sleeping PC). Closing
    // gives it back on purpose, and that's no error.
    canvas.addEventListener('webglcontextlost', () => {
      if (!this.disposed) this.onError(new Error('Scheda grafica persa'));
    });
  }

  show(scene) {
    this.scene = scene;
    this.scale = 1;
    this.frames = 0;
    this.seconds = 0;
  }

  start() {
    this.started = this.last = performance.now();
    this.request = requestAnimationFrame(this.tick);
  }

  dispose() {
    this.disposed = true;
    cancelAnimationFrame(this.request);
    this.pulse.release();
    const lose = this.gl.getExtension('WEBGL_lose_context');
    if (lose) lose.loseContext();
  }

  frame(now) {
    this.request = requestAnimationFrame(this.tick);
    const dt = Math.min(0.1, (now - this.last) / 1000);
    this.last = now;
    this.pulse.update(dt);
    this.pace(dt);
    try {
      this.draw((now - this.started) / 1000);
    } catch (e) {
      cancelAnimationFrame(this.request);
      this.onError(e);
    }
  }

  // A scene too heavy for the graphics card is drawn smaller and stretched, never slower.
  pace(dt) {
    this.frames++;
    this.seconds += dt;
    if (this.frames < VisualStage.PACE_FRAMES) return;
    if (this.seconds / this.frames > VisualStage.SLOW_S && this.scale > VisualStage.MIN_SCALE) {
      this.scale = Math.max(VisualStage.MIN_SCALE, this.scale * 0.8);
    }
    this.frames = 0;
    this.seconds = 0;
  }

  // Compiled the first time a scene is shown.
  program(scene) {
    let program = this.programs.get(scene.id);
    if (program) return program;
    const gl = this.gl;
    const handle = gl.createProgram();
    for (const [type, source] of [[gl.VERTEX_SHADER, scene.vertex || VISUAL_QUAD], [gl.FRAGMENT_SHADER, scene.fragment]]) {
      const shader = gl.createShader(type);
      gl.shaderSource(shader, source);
      gl.compileShader(shader);
      if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error(`${scene.id}: ${gl.getShaderInfoLog(shader)}`);
      gl.attachShader(handle, shader);
    }
    gl.linkProgram(handle);
    if (!gl.getProgramParameter(handle, gl.LINK_STATUS)) throw new Error(`${scene.id}: ${gl.getProgramInfoLog(handle)}`);
    const uniforms = {};
    for (const name of VISUAL_UNIFORMS) uniforms[name] = gl.getUniformLocation(handle, name);
    program = { handle, uniforms, vertices: gl.createVertexArray() };
    if (scene.points) {
      const seeds = new Float32Array(scene.points * 4);
      for (let i = 0; i < seeds.length; i++) seeds[i] = Math.random();
      gl.bindVertexArray(program.vertices);
      gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
      gl.bufferData(gl.ARRAY_BUFFER, seeds, gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0);
      gl.vertexAttribPointer(0, 4, gl.FLOAT, false, 0, 0);
    }
    this.programs.set(scene.id, program);
    return program;
  }

  draw(time) {
    const gl = this.gl;
    const canvas = this.canvas;
    const scene = this.scene;
    const pulse = this.pulse;
    const ratio = Math.min(window.devicePixelRatio || 1, 2) * this.scale;
    const width = Math.max(1, Math.round(canvas.clientWidth * ratio));
    const height = Math.max(1, Math.round(canvas.clientHeight * ratio));
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
    }
    gl.viewport(0, 0, width, height);

    const program = this.program(scene);
    const u = program.uniforms;
    gl.useProgram(program.handle);
    gl.bindVertexArray(program.vertices);
    gl.uniform2f(u.uRes, width, height);
    gl.uniform1f(u.uTime, time);
    gl.uniform1f(u.uBass, pulse.bass);
    gl.uniform1f(u.uMid, pulse.mid);
    gl.uniform1f(u.uHigh, pulse.high);
    gl.uniform1f(u.uLevel, pulse.level);
    gl.uniform1f(u.uKick, pulse.kick);
    gl.uniform1f(u.uBeatAge, pulse.beatAge);
    gl.uniform1f(u.uBeats, pulse.beats);
    gl.uniform1f(u.uDrive, scene.loop ? pulse.drive % scene.loop : pulse.drive);
    // The colours drift round the wheel, a full turn in under a minute.
    gl.uniform1f(u.uHue, (time * 0.02) % 1);

    if (scene.points) {
      // Points add their light to what's behind them, so crowded places glow.
      gl.clearColor(0.008, 0, 0.02, 1);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.ONE, gl.ONE);
      gl.drawArrays(gl.POINTS, 0, scene.points);
      gl.disable(gl.BLEND);
    } else {
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    }
  }
}

VisualStage.PACE_FRAMES = 40;
// Under about 40 frames a second the picture shrinks, down to half size.
VisualStage.SLOW_S = 0.024;
VisualStage.MIN_SCALE = 0.5;

const HUD_IDLE_MS = 2500;

// The visuals' screen: the scene, and over it what the mouse wakes up: the song, the player's
// buttons, the choice of scene.
const Visuals = {
  stage: null,
  index: 0,
  idleTimer: 0,
  mouse: [-1, -1],

  get shown() { return !!Visuals.stage; },

  open() {
    if (Visuals.stage || !Player.current()) return;
    const root = $('visuals');
    if (!root.firstChild) Visuals.build(root);
    const canvas = h('canvas', { class: 'vis-canvas' });
    try {
      Visuals.stage = new VisualStage(canvas, Visuals.failed);
    } catch (e) {
      toast('Questo PC non riesce a disegnare le visuali');
      return;
    }
    root.prepend(canvas);
    root.hidden = false;
    Visuals.choose(Math.max(0, VISUAL_SCENES.findIndex((s) => s.id === Store.settings.visual)), false);
    Visuals.stage.start();
    renderPlayer(false);
    Visuals.wake();
  },

  // Everything is let go: closed, the visuals cost nothing.
  close() {
    if (!Visuals.stage) return;
    Visuals.stage.dispose();
    Visuals.stage = null;
    clearTimeout(Visuals.idleTimer);
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    const root = $('visuals');
    root.hidden = true;
    root.querySelector('canvas').remove();
  },

  failed(error) {
    Visuals.close();
    toast('Le visuali si sono fermate');
    fetch('/api/log', { method: 'POST', body: `Visuali: ${error.message}` }).catch(() => {});
  },

  // The HUD is built the first time the screen opens.
  build(root) {
    root.innerHTML = `
      <div class="vis-hud">
        <button class="icon-button vis-close" title="Chiudi (Esc)" aria-label="Chiudi">${icon('close')}</button>
        <div class="vis-scene">
          <button class="icon-button vis-back" title="Scena precedente (←)" aria-label="Scena precedente">${icon('left')}</button>
          <span class="vis-name"></span>
          <button class="icon-button vis-forward" title="Scena successiva (→)" aria-label="Scena successiva">${icon('right')}</button>
        </div>
        <button class="icon-button vis-full" title="Schermo intero (F)" aria-label="Schermo intero">${icon('fullscreen')}</button>
        <div class="vis-song"><span class="vis-title"></span><span class="vis-artist"></span></div>
        <div class="vis-controls">
          <button class="icon-button vis-prev" aria-label="Precedente">${icon('prev')}</button>
          <button class="icon-button vis-play" aria-label="Play o pausa"></button>
          <button class="icon-button vis-next" aria-label="Successivo">${icon('next')}</button>
        </div>
      </div>`;
    const on = (name, action) => root.querySelector(name).addEventListener('click', action);
    on('.vis-close', () => Visuals.close());
    on('.vis-back', () => Visuals.step(-1));
    on('.vis-forward', () => Visuals.step(1));
    on('.vis-full', () => Visuals.fullscreen());
    on('.vis-prev', () => Player.previous());
    on('.vis-play', () => Player.toggle());
    on('.vis-next', () => Player.next());
    root.addEventListener('dblclick', (e) => {
      if (!e.target.closest('button')) Visuals.fullscreen();
    });
    // The picture changing under a still mouse also counts as a move for the browser.
    root.addEventListener('mousemove', (e) => {
      if (e.clientX === Visuals.mouse[0] && e.clientY === Visuals.mouse[1]) return;
      Visuals.mouse = [e.clientX, e.clientY];
      Visuals.wake();
    });
    document.addEventListener('fullscreenchange', () => {
      root.querySelector('.vis-full').innerHTML = icon(document.fullscreenElement ? 'fullscreenExit' : 'fullscreen');
    });
  },

  choose(index, save) {
    const count = VISUAL_SCENES.length;
    Visuals.index = ((index % count) + count) % count;
    const scene = VISUAL_SCENES[Visuals.index];
    Visuals.stage.show(scene);
    const root = $('visuals');
    root.querySelector('.vis-name').textContent = scene.name;
    root.querySelector('canvas').animate([{ opacity: 0 }, { opacity: 1 }], 450);
    if (save) {
      Store.settings.visual = scene.id;
      Store.save('settings');
    }
  },

  step(by) {
    Visuals.choose(Visuals.index + by, true);
    Visuals.wake();
  },

  fullscreen() {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else $('visuals').requestFullscreen().catch(() => {});
  },

  // Shows the HUD and the mouse; a moment after the last move they're gone again.
  wake() {
    const root = $('visuals');
    root.classList.remove('idle');
    clearTimeout(Visuals.idleTimer);
    Visuals.idleTimer = setTimeout(() => {
      if (root.querySelector('button:hover')) Visuals.wake();
      else root.classList.add('idle');
    }, HUD_IDLE_MS);
  },

  // From the player, whenever the song or its state changes. A new song shows its name a moment.
  describe(song, button, newSong) {
    const root = $('visuals');
    root.querySelector('.vis-title').textContent = song.title;
    root.querySelector('.vis-artist').textContent = song.artist;
    root.querySelector('.vis-play').innerHTML = icon(button);
    root.querySelector('.vis-next').disabled = !Player.hasNext();
    if (newSong) Visuals.wake();
  },

  // True when the key was for the visuals: V opens and closes them, the arrows change scene.
  key(e) {
    if (e.ctrlKey || e.altKey || e.metaKey) return false;
    const key = e.key.toLowerCase();
    if (!Visuals.stage) {
      if (key !== 'v' || !Player.current()) return false;
      Visuals.open();
    } else if (key === 'v' || key === 'escape') {
      Visuals.close();
    } else if (key === 'arrowright') {
      Visuals.step(1);
    } else if (key === 'arrowleft') {
      Visuals.step(-1);
    } else if (key === 'f') {
      Visuals.fullscreen();
    } else {
      return false;
    }
    return true;
  },
};
