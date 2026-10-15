// คนดิจิทัล 3D — a VRM avatar whose arms, hands and every finger are animated
// procedurally from the speech audio, so gestures never repeat as a loop.
// Mouth shapes come from a live spectrum analysis of the same audio.

import * as THREE from 'three';
import { GLTFLoader } from './vendor/three/addons/loaders/GLTFLoader.js';
import { RoomEnvironment } from './vendor/three/addons/environments/RoomEnvironment.js';
import { VRMLoaderPlugin, VRMUtils, VRMHumanoid } from './vendor/three-vrm.module.min.js';

const $ = (id) => document.getElementById(id);
const statusEl = $('status');
const controls = ['recBtn', 'demoBtn', 'stopBtn', 'audioFile'].map($);

function setStatus(text, sticky) {
  statusEl.textContent = text || '';
  statusEl.classList.toggle('show', !!text);
  clearTimeout(setStatus.t);
  if (text && !sticky) setStatus.t = setTimeout(() => setStatus(''), 3000);
}

const rand = (a, b) => a + Math.random() * (b - a);
const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];
const clamp01 = (x) => Math.min(1, Math.max(0, x));

// ---------- scene ----------

const canvas = $('canvas3d');
const renderer = new THREE.WebGLRenderer({ canvas, alpha: true, antialias: true });
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.05;

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(28, 1, 0.1, 20);
// Soft indoor reflections for the realistic (PBR) skin and clothes.
scene.environment = new THREE.PMREMGenerator(renderer).fromScene(new RoomEnvironment(), 0.04).texture;
scene.environmentIntensity = 0.55;
scene.add(new THREE.HemisphereLight(0xfff4e6, 0x334155, 1.2));
const key = new THREE.DirectionalLight(0xffffff, 1.8);
key.position.set(0.8, 1.6, 2.0);
scene.add(key);
const rim = new THREE.DirectionalLight(0xffc48a, 0.9);
rim.position.set(-1.2, 1.8, -1.5);
scene.add(rim);

let vrm = null;
// Two framings: upper body (talking) and full body (sitting/walking).
// `camZoom` blends between them (0 = upper body, 1 = full body).
let frameUpper = { centerY: 1.2, height: 1.0, halfW: 0.34 };
let frameFull = { centerY: 0.8, height: 1.75, halfW: 0.8 };
let camZoom = 0;

function placeCamera() {
  const lerp = THREE.MathUtils.lerp;
  const centerY = lerp(frameUpper.centerY, frameFull.centerY, camZoom);
  const height = lerp(frameUpper.height, frameFull.height, camZoom);
  const halfW = lerp(frameUpper.halfW, frameFull.halfW, camZoom);
  const t = Math.tan(THREE.MathUtils.degToRad(camera.fov / 2));
  const dist = Math.max(height / 2 / t, halfW / (t * camera.aspect));
  camera.position.set(0, centerY + 0.04, dist);
  camera.lookAt(0, centerY, 0);
}

function resize() {
  renderer.setSize(window.innerWidth, window.innerHeight, false);
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
  placeCamera();
}
window.addEventListener('resize', resize);

// ---------- bones ----------

const SIDES = { left: 1, right: -1 };
const FINGERS = ['Thumb', 'Index', 'Middle', 'Ring', 'Little'];
const bones = {};

function bone(name) {
  return vrm.humanoid.getNormalizedBoneNode(name);
}

function collectBones() {
  for (const n of ['hips', 'spine', 'chest', 'upperChest', 'neck', 'head']) bones[n] = bone(n);
  for (const side of Object.keys(SIDES)) {
    bones[side + 'Leg'] = { upper: bone(side + 'UpperLeg'), lower: bone(side + 'LowerLeg'), foot: bone(side + 'Foot') };
  }
  for (const side of Object.keys(SIDES)) {
    bones[side] = {
      shoulder: bone(side + 'Shoulder'),
      upper: bone(side + 'UpperArm'),
      lower: bone(side + 'LowerArm'),
      hand: bone(side + 'Hand'),
      fingers: FINGERS.map((f) => (f === 'Thumb'
        ? ['ThumbMetacarpal', 'ThumbProximal', 'ThumbDistal']
        : [f + 'Proximal', f + 'Intermediate', f + 'Distal']
      ).map((seg) => bone(side + seg))),
    };
  }
}

// ---------- pose model ----------
// Each arm is described by a few angles (radians) plus a curl per finger.
// `target` is where the arm is heading, `cur` follows it with smoothing.

// `down` is overridden per model at boot.
const REST = {
  down: 1.3, fwd: 0.12, twist: 0.0, elbow: 0.28, fore: -0.2, wrist: 0.05, wristSide: 0.0,
  curl: [0.25, 0.3, 0.35, 0.4, 0.45], spread: 0.1,
};

function makeArm() {
  return { cur: structuredClone(REST), target: structuredClone(REST), speed: 3, beat: 0 };
}
const arms = { left: makeArm(), right: makeArm() };

// Hand shapes: curl per finger [thumb, index, middle, ring, little] and spread.
const HANDSHAPES = [
  { name: 'open', curl: [0.05, 0.05, 0.05, 0.08, 0.1], spread: 0.35 },
  { name: 'relaxed', curl: [0.25, 0.25, 0.32, 0.4, 0.45], spread: 0.15 },
  { name: 'soft', curl: [0.35, 0.15, 0.22, 0.3, 0.38], spread: 0.25 },
  { name: 'point', curl: [0.6, 0.0, 0.9, 0.95, 0.95], spread: 0.05 },
  { name: 'two', curl: [0.7, 0.0, 0.0, 0.95, 0.95], spread: 0.3 },
  { name: 'three', curl: [0.7, 0.0, 0.0, 0.0, 0.95], spread: 0.3 },
  { name: 'four', curl: [0.8, 0.0, 0.0, 0.0, 0.0], spread: 0.35 },
  { name: 'pinch', curl: [0.55, 0.55, 0.3, 0.35, 0.4], spread: 0.1 },
  { name: 'fist', curl: [0.7, 0.95, 0.95, 0.95, 0.95], spread: 0.0 },
  { name: 'cup', curl: [0.3, 0.35, 0.4, 0.45, 0.5], spread: 0.2 },
];

// Arm poses used while talking: ranges for each angle.
const ARM_POSES = [
  { name: 'explain', down: [1.05, 1.3], fwd: [0.25, 0.6], elbow: [1.2, 1.8], fore: [-1.3, -0.6], wrist: [-0.2, 0.3] },
  { name: 'present', down: [0.9, 1.15], fwd: [0.2, 0.45], elbow: [0.6, 1.1], fore: [-1.5, -1.0], wrist: [-0.3, 0.1] },
  { name: 'chest', down: [1.15, 1.35], fwd: [0.35, 0.7], elbow: [1.8, 2.2], fore: [-0.4, 0.2], wrist: [0.0, 0.4] },
  { name: 'raise', down: [0.95, 1.2], fwd: [0.3, 0.6], elbow: [1.9, 2.3], fore: [-0.9, -0.3], wrist: [-0.1, 0.3] },
  { name: 'low', down: [1.2, 1.38], fwd: [0.15, 0.35], elbow: [0.7, 1.2], fore: [-1.0, -0.3], wrist: [-0.1, 0.25] },
];

function setGesture(side, intensity) {
  const a = arms[side];
  const p = pick(ARM_POSES);
  const h = pick(HANDSHAPES);
  const t = a.target;
  t.down = rand(...p.down);
  t.fwd = rand(...p.fwd);
  t.elbow = rand(...p.elbow) * (0.75 + 0.25 * intensity);
  t.fore = rand(...p.fore);
  t.wrist = rand(...p.wrist);
  t.wristSide = rand(-0.25, 0.25);
  t.twist = rand(-0.2, 0.25);
  t.curl = h.curl.map((c) => clamp01(c + rand(-0.08, 0.08)));
  t.spread = h.spread + rand(-0.05, 0.05);
  a.speed = rand(4, 7);
}

function setRest(side, loose) {
  const a = arms[side];
  const t = a.target;
  Object.assign(t, structuredClone(REST));
  if (motion.sitTarget > 0.5) {
    // Hands resting on the lap.
    t.down = 1.2; t.fwd = 0.45; t.elbow = 1.0; t.fore = -0.9;
  }
  t.down += rand(-0.06, 0.04);
  t.fwd += rand(-0.05, 0.1);
  t.elbow += rand(0, loose ? 0.5 : 0.2);
  t.fore += rand(-0.2, 0.2);
  t.curl = REST.curl.map((c) => clamp01(c + rand(-0.1, 0.15)));
  a.speed = rand(1.8, 3);
}

// Small finger "fidgets" so hands never look frozen.
function fidget(side) {
  const t = arms[side].target;
  const i = Math.floor(rand(0, 5));
  t.curl[i] = clamp01(t.curl[i] + rand(-0.25, 0.25));
  t.spread = clamp01(t.spread + rand(-0.08, 0.08));
}

const tmpQ = new THREE.Quaternion();
const qa = new THREE.Quaternion();
const qb = new THREE.Quaternion();
const X = new THREE.Vector3(1, 0, 0);
const Y = new THREE.Vector3(0, 1, 0);
const Z = new THREE.Vector3(0, 0, 1);

function applyArm(side, dt) {
  const s = SIDES[side];
  const a = arms[side];
  const c = a.cur;
  const t = a.target;
  const k = 1 - Math.exp(-dt * a.speed);
  for (const key of ['down', 'fwd', 'twist', 'elbow', 'fore', 'wrist', 'wristSide', 'spread']) {
    c[key] += (t[key] - c[key]) * k;
  }
  for (let i = 0; i < 5; i++) c.curl[i] += (t.curl[i] - c.curl[i]) * Math.min(1, k * 1.6);
  a.beat *= Math.exp(-dt * 9);

  const b = bones[side];
  // Upper arm: lower from T-pose (about Z), then swing forward (about X).
  qa.setFromAxisAngle(Z, -s * c.down);
  qb.setFromAxisAngle(X, -(c.fwd + a.beat * 0.15 + (a.swing || 0)));
  tmpQ.setFromAxisAngle(X, s * c.twist);
  b.upper.quaternion.copy(qb).multiply(qa).multiply(tmpQ);
  // Elbow bends the forearm toward the front; `fore` turns the palm.
  qa.setFromAxisAngle(Y, -s * (c.elbow + a.beat * 0.35));
  qb.setFromAxisAngle(X, s * c.fore);
  b.lower.quaternion.copy(qa).multiply(qb);
  // Wrist.
  qa.setFromAxisAngle(Z, -s * c.wrist);
  qb.setFromAxisAngle(Y, -s * c.wristSide);
  b.hand.quaternion.copy(qa).multiply(qb);
  // Fingers: curl toward the palm; spread fans them apart.
  b.fingers.forEach((segs, fi) => {
    const curl = c.curl[fi];
    if (fi === 0) {
      // Thumb folds across the palm.
      segs[0] && segs[0].quaternion.setFromAxisAngle(Y, s * (0.15 + curl * 0.6)).multiply(qa.setFromAxisAngle(Z, -s * curl * 0.3));
      segs[1] && segs[1].quaternion.setFromAxisAngle(Y, s * curl * 0.6);
      segs[2] && segs[2].quaternion.setFromAxisAngle(Y, s * curl * 0.7);
      return;
    }
    const fan = (fi - 2.5) * c.spread * 0.18;
    segs.forEach((seg, si) => {
      if (!seg) return;
      const ang = curl * (si === 0 ? 1.35 : si === 1 ? 1.6 : 1.1);
      seg.quaternion.setFromAxisAngle(Z, -s * ang);
      if (si === 0) seg.quaternion.multiply(qa.setFromAxisAngle(Y, s * fan));
    });
  });
}

// ---------- audio ----------

let audioCtx = null;
let analyser = null;
let currentSource = null;
const freq = new Uint8Array(512);
const wave = new Float32Array(1024);

function getAudioCtx() {
  if (!audioCtx || audioCtx.state === 'closed') {
    audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    analyser = audioCtx.createAnalyser();
    analyser.fftSize = 1024;
    analyser.smoothingTimeConstant = 0.5;
    analyser.connect(audioCtx.destination);
  }
  if (audioCtx.state === 'suspended') audioCtx.resume();
  return audioCtx;
}

function decode(ctx, buf) {
  return new Promise((resolve, reject) => ctx.decodeAudioData(buf, resolve, reject));
}

function stopSpeaking() {
  if (currentSource) {
    currentSource.onended = null;
    try { currentSource.stop(); } catch (e) { /* already stopped */ }
    currentSource = null;
  }
  document.body.classList.remove('speaking');
}

async function speak(arrayBuffer) {
  stopSpeaking();
  const ctx = getAudioCtx();
  const buf = await decode(ctx, arrayBuffer);
  const src = ctx.createBufferSource();
  src.buffer = buf;
  src.connect(analyser);
  src.onended = () => { if (currentSource === src) { currentSource = null; document.body.classList.remove('speaking'); } };
  src.start(0);
  currentSource = src;
  document.body.classList.add('speaking');
  // Talk only while facing the viewer: stop walking and turn to the camera.
  if (motion.goal !== null) { motion.goal = null; motion.queue = []; }
  motion.targetYaw = 0;
}

let busy = false;
async function speakSafely(getBuffer) {
  if (busy) return;
  busy = true;
  try {
    await speak(await getBuffer());
  } catch (e) {
    console.error(e);
    setStatus('เล่นเสียงไม่ได้: ' + (e && e.message ? e.message : e));
  } finally {
    busy = false;
  }
}

// Loudness and a rough vowel color from the spectrum.
const voice = { level: 0, avg: 0, open: 0, wide: 0, round: 0 };

function analyseVoice(dt) {
  if (!analyser || !currentSource) {
    voice.level += (0 - voice.level) * Math.min(1, dt * 12);
    return;
  }
  analyser.getFloatTimeDomainData(wave);
  let sum = 0;
  for (let i = 0; i < wave.length; i++) sum += wave[i] * wave[i];
  const rms = Math.sqrt(sum / wave.length);
  analyser.getByteFrequencyData(freq);
  const hz = audioCtx.sampleRate / analyser.fftSize;
  const band = (lo, hi) => {
    let e = 0;
    let n = 0;
    for (let i = Math.floor(lo / hz); i < Math.min(freq.length, Math.ceil(hi / hz)); i++) { e += freq[i]; n++; }
    return n ? e / n / 255 : 0;
  };
  const low = band(200, 900);
  const high = band(1800, 4500);
  voice.level = clamp01((rms - 0.012) * 9);
  voice.wide = clamp01((high - low * 0.6) * 3);
  voice.round = clamp01((low - high) * 2.2);
}

// ---------- behaviour ----------

const state = { t: 0, nextGesture: 0, nextIdle: 0, nextFidget: 0, nextBlink: 1.5, blink: 0, silentFor: 0, lastLevel: 0, nod: 0 };

function updateBehaviour(dt) {
  state.t += dt;
  const speaking = !!currentSource && voice.level > 0.05;
  state.silentFor = speaking ? 0 : state.silentFor + dt;

  // Onset of a stressed syllable → small beat in the active hand and a nod.
  const rising = voice.level - state.lastLevel;
  state.lastLevel = voice.level;
  if (speaking && rising > 0.18) {
    for (const side of Object.keys(arms)) if (arms[side].target.elbow > 0.8) arms[side].beat = Math.min(1, arms[side].beat + rising * 2);
    state.nod = Math.min(1, state.nod + rising);
  }

  if (motion.walkAmt > 0.3 || motion.wave > 0.05) {
    // Arms belong to the walk cycle / wave right now.
  } else if (currentSource && state.t > state.nextGesture && state.silentFor < 0.6) {
    const r = Math.random();
    const intensity = 0.6 + voice.level * 0.6;
    if (r < 0.42) { setGesture('right', intensity); if (Math.random() < 0.6) setRest('left', true); }
    else if (r < 0.72) { setGesture('left', intensity); if (Math.random() < 0.6) setRest('right', true); }
    else { setGesture('right', intensity); setGesture('left', intensity); }
    state.nextGesture = state.t + rand(0.7, 1.8);
  } else if (state.silentFor > 0.9 && state.t > state.nextIdle) {
    setRest('left', false);
    setRest('right', false);
    state.nextIdle = state.t + rand(3, 6);
  }
  if (state.t > state.nextFidget) {
    fidget(Math.random() < 0.5 ? 'left' : 'right');
    state.nextFidget = state.t + rand(0.4, 1.4);
  }

  // Blink.
  if (state.t > state.nextBlink) { state.blink = 1; state.nextBlink = state.t + rand(2, 5.5); }
  state.blink = Math.max(0, state.blink - dt * 7);
  state.nod *= Math.exp(-dt * 5);
}

const smooth = { aa: 0, ih: 0, ou: 0, ee: 0, oh: 0 };

function applyFace(dt) {
  const em = vrm.expressionManager;
  if (!em) return;
  const open = voice.level;
  const target = {
    aa: open * (1 - voice.wide * 0.6) * (1 - voice.round * 0.5),
    ee: open * voice.wide * 0.8,
    ih: open * voice.wide * 0.4,
    ou: open * voice.round * 0.7,
    oh: open * voice.round * 0.5,
  };
  const k = Math.min(1, dt * 18);
  for (const v of Object.keys(smooth)) {
    smooth[v] += (target[v] - smooth[v]) * k;
    em.setValue(v, smooth[v]);
  }
  em.setValue('blink', Math.sin(Math.min(1, state.blink) * Math.PI) * (1 - expr.cur.surprised * 0.8));
  for (const name of Object.keys(expr.cur)) {
    const target = expr.until[name] > state.t ? expr.peak[name] : expr.base[name];
    expr.cur[name] += (target - expr.cur[name]) * Math.min(1, dt * 4);
    em.setValue(name, expr.cur[name]);
  }
}

// Facial expressions: a resting mood plus short timed "pulses".
const expr = {
  base: { happy: 0.12, relaxed: 0.25, surprised: 0, sad: 0, angry: 0 },
  cur: { happy: 0, relaxed: 0, surprised: 0, sad: 0, angry: 0 },
  peak: { happy: 0.85, relaxed: 0.6, surprised: 0.7, sad: 0.55, angry: 0.4 },
  until: { happy: 0, relaxed: 0, surprised: 0, sad: 0, angry: 0 },
};
function pulseExpression(name, seconds) {
  expr.until[name] = state.t + seconds;
}

function applyBody() {
  const t = state.t;
  const breathe = Math.sin(t * 1.6) * 0.012;
  const sway = Math.sin(t * 0.37) * 0.02 + Math.sin(t * 0.83 + 1) * 0.01;
  const talk = voice.level;
  if (bones.spine) bones.spine.rotation.set(breathe + talk * 0.02 + motion.lean, sway * 0.6, sway * 0.4);
  if (bones.chest) bones.chest.rotation.set(breathe, sway * 0.5, 0);
  // When the body is turned away, the head turns back toward the viewer a bit.
  const lookBack = -motion.yaw * 0.35;
  if (bones.neck) bones.neck.rotation.set(0.04 + state.nod * 0.06 - motion.lean * 0.5, Math.sin(t * 0.5) * 0.05 + lookBack * 0.5, Math.sin(t * 0.31) * 0.03);
  if (bones.head) bones.head.rotation.set(-0.02 + state.nod * 0.08 + talk * 0.03, Math.sin(t * 0.7 + 2) * 0.06 + lookBack * 0.5, Math.sin(t * 0.45) * 0.04);
  if (bones.hips) bones.hips.rotation.set(0, Math.sin(t * 0.25) * 0.03 * (1 - motion.walkAmt), Math.sin(t * 0.2) * 0.015);
  for (const side of Object.keys(SIDES)) {
    const sh = bones[side].shoulder;
    if (sh) sh.rotation.set(0, 0, -SIDES[side] * (breathe * 0.5 + 0.02));
  }
}

// ---------- locomotion: sit / stand / walk ----------
// Everything is procedural: hips height, leg angles, walk cycle and arm swing
// are computed each frame, so no animation clips are needed.

const root = new THREE.Group(); // carries the avatar; moved/turned when walking
scene.add(root);
const motion = {
  sit: 0, sitTarget: 0, lean: 0,
  goal: null, yaw: 0, targetYaw: 0, walkAmt: 0, phase: 0,
  wave: 0, waveUntil: 0,
  queue: [], busyUntil: 0,
};
const body = { hipsRest: new THREE.Vector3(), hipsY: 0.9, thigh: 0.42, seatHipsY: 0.5 };
const WALK_RANGE = 0.5;
const WALK_SPEED = 0.55; // m/s

function buildProps() {
  // A simple wooden chair behind the avatar, and a soft contact shadow.
  const wood = new THREE.MeshStandardMaterial({ color: 0x7c4a2d, roughness: 0.7 });
  const chair = new THREE.Group();
  const seatTop = body.seatHipsY - 0.08;
  const seat = new THREE.Mesh(new THREE.BoxGeometry(0.46, 0.045, 0.42), wood);
  seat.position.y = seatTop - 0.022;
  chair.add(seat);
  for (const [x, z] of [[-0.2, -0.18], [0.2, -0.18], [-0.2, 0.18], [0.2, 0.18]]) {
    const leg = new THREE.Mesh(new THREE.BoxGeometry(0.035, seatTop - 0.045, 0.035), wood);
    leg.position.set(x, (seatTop - 0.045) / 2, z);
    chair.add(leg);
  }
  const back = new THREE.Mesh(new THREE.BoxGeometry(0.46, 0.42, 0.035), wood);
  back.position.set(0, seatTop + 0.23, -0.2);
  chair.add(back);
  chair.position.z = -body.thigh * 0.75;
  scene.add(chair);

  const c = document.createElement('canvas');
  c.width = c.height = 128;
  const g = c.getContext('2d');
  const grad = g.createRadialGradient(64, 64, 0, 64, 64, 64);
  grad.addColorStop(0, 'rgba(0,0,0,0.45)');
  grad.addColorStop(1, 'rgba(0,0,0,0)');
  g.fillStyle = grad;
  g.fillRect(0, 0, 128, 128);
  const shadow = new THREE.Mesh(
    new THREE.PlaneGeometry(0.7, 0.45),
    new THREE.MeshBasicMaterial({ map: new THREE.CanvasTexture(c), transparent: true, depthWrite: false }),
  );
  shadow.rotation.x = -Math.PI / 2;
  shadow.position.y = 0.002;
  root.add(shadow);
}

// Actions run one after another from a queue.
function act(name) {
  if (name === 'sit') motion.queue.push({ type: 'sit' });
  else if (name === 'stand') motion.queue.push({ type: 'stand' });
  else if (name === 'walk') {
    if (motion.sitTarget > 0.5) motion.queue.push({ type: 'stand' });
    const here = root.position.x;
    let x = rand(-WALK_RANGE, WALK_RANGE);
    if (Math.abs(x - here) < 0.25) x = here > 0 ? -WALK_RANGE * rand(0.6, 1) : WALK_RANGE * rand(0.6, 1);
    motion.queue.push({ type: 'walk', x });
    motion.queue.push({ type: 'pause', s: rand(0.8, 2) });
    motion.queue.push({ type: 'walk', x: 0 });
  } else if (name === 'wave') {
    motion.queue.push({ type: 'wave' });
  }
}

function nextAction() {
  const a = motion.queue.shift();
  if (!a) return;
  if (a.type === 'sit') {
    if (Math.abs(root.position.x) > 0.02) { motion.queue.unshift(a); motion.queue.unshift({ type: 'walk', x: 0 }); return nextAction(); }
    motion.sitTarget = 1;
    motion.busyUntil = state.t + 1.4;
    setRest('left'); setRest('right');
  } else if (a.type === 'stand') {
    motion.sitTarget = 0;
    motion.busyUntil = state.t + 1.4;
    setRest('left'); setRest('right');
  } else if (a.type === 'walk') {
    if (motion.sitTarget > 0.5) { motion.queue.unshift(a); motion.queue.unshift({ type: 'stand' }); return nextAction(); }
    motion.goal = a.x;
    setRest('left'); setRest('right');
  } else if (a.type === 'pause') {
    motion.busyUntil = state.t + a.s;
  } else if (a.type === 'wave') {
    motion.waveUntil = state.t + 2.2;
    motion.busyUntil = state.t + 2.4;
    pulseExpression('happy', 2.5);
  }
}

function updateMotion(dt) {
  // Start the next queued action when the current one is done.
  if (motion.goal === null && state.t > motion.busyUntil && motion.queue.length) nextAction();

  // Walking toward the goal: turn first, then step.
  let moving = 0;
  if (motion.goal !== null) {
    const dx = motion.goal - root.position.x;
    if (Math.abs(dx) > 0.015) {
      motion.targetYaw = Math.sign(dx) * Math.PI / 2 * 0.9;
      if (Math.abs(motion.yaw - motion.targetYaw) < 0.5) {
        root.position.x += Math.sign(dx) * Math.min(Math.abs(dx), WALK_SPEED * dt);
        moving = 1;
      } else {
        moving = 0.35; // stepping in place while turning
      }
    } else {
      root.position.x = motion.goal;
      motion.goal = null;
      motion.targetYaw = 0;
      motion.busyUntil = state.t + 0.6;
    }
  }
  if (motion.goal === null && Math.abs(motion.yaw) > 0.1) moving = Math.max(moving, 0.35);
  motion.yaw += (motion.targetYaw - motion.yaw) * Math.min(1, dt * 4);
  root.rotation.y = motion.yaw;
  motion.walkAmt += (moving - motion.walkAmt) * Math.min(1, dt * 6);
  motion.phase += dt * Math.PI * 2 * 1.7 * Math.max(0.35, motion.walkAmt);

  // Sitting down / standing up: ease, with a forward lean mid-transition.
  const before = motion.sit;
  motion.sit += Math.sign(motion.sitTarget - motion.sit) * Math.min(Math.abs(motion.sitTarget - motion.sit), dt / 1.2);
  const e = motion.sit * motion.sit * (3 - 2 * motion.sit); // smoothstep
  const transition = motion.sit !== before ? 1 : 0;
  motion.lean += ((transition ? 0.45 * Math.sin(Math.PI * motion.sit) : 0.06 * e) - motion.lean) * Math.min(1, dt * 6);

  // Wave hello with the right hand.
  const waving = state.t < motion.waveUntil;
  motion.wave += ((waving ? 1 : 0) - motion.wave) * Math.min(1, dt * 5);

  applyLegs(e);

  // Camera: full body while sitting, walking or away from the center.
  const wantFull = e > 0.02 || motion.goal !== null || Math.abs(root.position.x) > 0.03 || motion.walkAmt > 0.1;
  camZoom += ((wantFull ? 1 : motion.wave > 0.05 ? 0.3 : 0) - camZoom) * Math.min(1, dt * 1.6);
  placeCamera();
}

function applyLegs(sitE) {
  const w = motion.walkAmt;
  const ph = motion.phase;
  const hips = bones.hips;
  hips.position.copy(body.hipsRest);
  hips.position.y -= (body.hipsY - body.seatHipsY) * sitE;
  hips.position.z -= body.thigh * 0.75 * sitE;
  hips.position.y += -0.018 * w * Math.abs(Math.cos(ph));
  for (const side of Object.keys(SIDES)) {
    const leg = bones[side + 'Leg'];
    const p = side === 'left' ? ph : ph + Math.PI;
    const swing = 0.42 * w * Math.sin(p);
    const knee = w * (0.08 + 0.6 * Math.max(0, Math.cos(p)));
    if (leg.upper) leg.upper.rotation.set(-1.5 * sitE - swing, 0, 0);
    if (leg.lower) leg.lower.rotation.set(1.5 * sitE + knee, 0, 0);
    if (leg.foot) leg.foot.rotation.set(-0.1 * w * Math.sin(p), 0, 0);
    // Arms swing opposite to the legs; the right arm also waves.
    const arm = arms[side];
    arm.swing = -0.32 * w * Math.sin(p);
  }
  if (motion.wave > 0.01) {
    const t = arms.right.target;
    const wv = motion.wave;
    t.down = THREE.MathUtils.lerp(t.down, 0.55, wv);
    t.fwd = THREE.MathUtils.lerp(t.fwd, 0.7, wv);
    t.twist = THREE.MathUtils.lerp(t.twist, 1.5, wv);
    t.elbow = THREE.MathUtils.lerp(t.elbow, 1.9, wv);
    t.fore = THREE.MathUtils.lerp(t.fore, 0, wv);
    t.wristSide = Math.sin(state.t * 9) * 0.45 * wv;
    t.curl = [0.1, 0.02, 0.02, 0.04, 0.06];
    t.spread = 0.3;
    arms.right.speed = 8;
  }
}

// Optional "life" when nobody is talking: sometimes sit, walk, smile or nod.
let autoLife = true;
let nextAuto = 8;
function updateAuto() {
  if (!autoLife || currentSource || motion.queue.length || motion.goal !== null || state.t < nextAuto) return;
  const r = Math.random();
  if (motion.sitTarget > 0.5) { if (r < 0.6) act('stand'); else pulseExpression('happy', 2); }
  else if (r < 0.3) act('sit');
  else if (r < 0.6) act('walk');
  else if (r < 0.75) pulseExpression('happy', 2.5);
  else state.nod = 1;
  nextAuto = state.t + rand(7, 14);
}

// ---------- loop ----------

const clock = new THREE.Clock();
function tick() {
  const dt = Math.min(clock.getDelta(), 0.05);
  if (vrm) {
    analyseVoice(dt);
    updateBehaviour(dt);
    updateAuto();
    updateMotion(dt);
    applyBody();
    applyArm('left', dt);
    applyArm('right', dt);
    applyFace(dt);
    vrm.update(dt);
  }
  renderer.render(scene, camera);
  requestAnimationFrame(tick);
}

// ---------- controls ----------

$('demoBtn').addEventListener('click', () => {
  getAudioCtx();
  speakSafely(async () => (await fetch('common/test.wav')).arrayBuffer());
});
$('stopBtn').addEventListener('click', stopSpeaking);
$('audioFile').addEventListener('change', (e) => {
  const f = e.target.files[0];
  e.target.value = '';
  if (!f) return;
  getAudioCtx();
  speakSafely(() => f.arrayBuffer());
});

let recorder = null;
let chunks = [];
let stream = null;
const recBtn = $('recBtn');
recBtn.addEventListener('click', async () => {
  getAudioCtx();
  if (recorder && recorder.state === 'recording') { recorder.stop(); return; }
  if (!navigator.mediaDevices || !window.MediaRecorder) { setStatus('เบราว์เซอร์นี้อัดเสียงไม่ได้ ลองใช้ “ไฟล์เสียง” แทน'); return; }
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
  } catch (e) {
    setStatus('ไม่ได้รับอนุญาตให้ใช้ไมโครโฟน');
    return;
  }
  stopSpeaking();
  chunks = [];
  recorder = new MediaRecorder(stream);
  recorder.ondataavailable = (ev) => { if (ev.data.size) chunks.push(ev.data); };
  recorder.onstop = () => {
    stream.getTracks().forEach((t) => t.stop());
    recBtn.classList.remove('recording');
    recBtn.querySelector('.lbl').textContent = 'อัดเสียง';
    const blob = new Blob(chunks, { type: recorder.mimeType });
    speakSafely(() => blob.arrayBuffer());
  };
  recorder.start();
  recBtn.classList.add('recording');
  recBtn.querySelector('.lbl').textContent = 'หยุดอัด → พูด';
  setStatus('กำลังอัด… กดอีกครั้งเพื่อให้ตัวละครพูดตาม');
});

let showBackdrop = true;
try { showBackdrop = localStorage.getItem('bg') !== '0'; } catch (e) { /* private mode */ }
function applyBackground() {
  $('bgBtn').textContent = 'พื้นหลัง: ' + (showBackdrop ? 'เปิด' : 'ปิด');
  document.body.classList.toggle('nobg', !showBackdrop);
  const v = $('background_video');
  v.style.display = showBackdrop ? 'block' : 'none';
  if (!showBackdrop) { v.pause(); return; }
  // Load as a blob: works offline and inside the APK's WebView asset loader.
  if (!v.src) {
    fetch('background/bg.mp4').then((r) => r.blob()).then((b) => {
      v.src = URL.createObjectURL(b);
      v.play().catch(() => {});
    }).catch(() => {});
  } else {
    v.play().catch(() => {});
  }
}
$('bgBtn').addEventListener('click', () => {
  showBackdrop = !showBackdrop;
  try { localStorage.setItem('bg', showBackdrop ? '1' : '0'); } catch (e) { /* private mode */ }
  applyBackground();
});
applyBackground();

// ---------- realistic human (Microsoft Rocketbox) ----------
// A plain glTF with a 3ds Max biped skeleton and ARKit-style blendshapes.
// It is wrapped so it looks like a VRM to the rest of this file: a normalized
// humanoid rig (three-vrm's VRMHumanoid) plus an expression manager that maps
// VRM expression names onto the model's blendshapes.

const BIPED = {
  hips: 'Pelvis', spine: 'Spine', chest: 'Spine1', upperChest: 'Spine2', neck: 'Neck', head: 'Head',
  leftEye: 'LEye', rightEye: 'REye',
};
for (const [side, S] of [['left', 'L'], ['right', 'R']]) {
  Object.assign(BIPED, {
    [side + 'Shoulder']: S + '_Clavicle', [side + 'UpperArm']: S + '_UpperArm',
    [side + 'LowerArm']: S + '_Forearm', [side + 'Hand']: S + '_Hand',
    [side + 'UpperLeg']: S + '_Thigh', [side + 'LowerLeg']: S + '_Calf',
    [side + 'Foot']: S + '_Foot', [side + 'Toes']: S + '_Toe0',
    [side + 'ThumbMetacarpal']: S + '_Finger0', [side + 'ThumbProximal']: S + '_Finger01', [side + 'ThumbDistal']: S + '_Finger02',
  });
  ['Index', 'Middle', 'Ring', 'Little'].forEach((f, i) => {
    BIPED[side + f + 'Proximal'] = S + '_Finger' + (i + 1);
    BIPED[side + f + 'Intermediate'] = S + '_Finger' + (i + 1) + '1';
    BIPED[side + f + 'Distal'] = S + '_Finger' + (i + 1) + '2';
  });
}

const FACE = {
  aa: { AA_VI_10_aa: 1, AK_25_JawOpen: 0.3 },
  ee: { AA_VI_11_E: 1 },
  ih: { AA_VI_12_I: 1 },
  oh: { AA_VI_13_O: 1, AK_25_JawOpen: 0.15 },
  ou: { AA_VI_14_U: 1 },
  blink: { AK_09_EyeBlinkLeft: 1, AK_10_EyeBlinkRight: 1 },
  happy: { AK_44_MouthSmileLeft: 0.75, AK_45_MouthSmileRight: 0.75, AK_07_CheekSquintLeft: 0.5, AK_08_CheekSquintRight: 0.5 },
  relaxed: { AK_44_MouthSmileLeft: 0.2, AK_45_MouthSmileRight: 0.2 },
  surprised: { AK_21_EyeWideLeft: 1, AK_22_EyeWideRight: 1, AK_03_BrowInnerUp: 0.9, AK_25_JawOpen: 0.25 },
  sad: { AK_30_MouthFrownLeft: 0.7, AK_31_MouthFrownRight: 0.7, AK_03_BrowInnerUp: 0.7 },
  angry: { AK_30_MouthFrownLeft: 0.3, AK_31_MouthFrownRight: 0.3 },
};

function wrapHuman(gltf) {
  const model = gltf.scene;
  const humanBones = {};
  for (const [name, b] of Object.entries(BIPED)) {
    const node = model.getObjectByName('Bip01_' + b);
    if (node) humanBones[name] = { node };
  }
  model.updateMatrixWorld(true);
  const humanoid = new VRMHumanoid(humanBones);
  const meshes = [];
  model.traverse((o) => { if (o.isMesh && o.morphTargetDictionary) meshes.push(o); });
  const values = {};
  return {
    scene: model,
    humanoid,
    lookAt: null,
    meta: null,
    expressionManager: {
      setValue(n, v) { values[n] = v; },
      getValue(n) { return values[n] || 0; },
    },
    update() {
      humanoid.update();
      for (const m of meshes) {
        const inf = m.morphTargetInfluences;
        inf.fill(0);
        for (const [expr, v] of Object.entries(values)) {
          const shapes = FACE[expr];
          if (!shapes || !v) continue;
          for (const [shape, w] of Object.entries(shapes)) {
            const i = m.morphTargetDictionary[shape];
            if (i != null) inf[i] = Math.min(1, inf[i] + v * w);
          }
        }
      }
    },
  };
}

const MODELS = {
  // restDown: how far the arms hang from the T-pose; halfW: framing width.
  human: { file: 'models/human.glb', label: 'คนจริง', restDown: 1.42, halfW: 0.45 },
  anime: { file: 'models/avatar.vrm', label: 'การ์ตูน', restDown: 1.3, halfW: 0.34 },
};
let modelKey = 'human';
try { if (MODELS[localStorage.getItem('model3d')]) modelKey = localStorage.getItem('model3d'); } catch (e) { /* private mode */ }
$('modelBtn').textContent = 'ตัวละคร: ' + MODELS[modelKey].label;
$('modelBtn').addEventListener('click', () => {
  const next = modelKey === 'human' ? 'anime' : 'human';
  try { localStorage.setItem('model3d', next); } catch (e) { /* private mode */ }
  location.reload();
});

// ---------- boot ----------

async function boot() {
  resize();
  requestAnimationFrame(tick);
  try {
    const loader = new GLTFLoader();
    loader.register((parser) => new VRMLoaderPlugin(parser));
    const gltf = await loader.loadAsync(MODELS[modelKey].file, (e) => {
      if (e.total) $('startMessage').textContent = 'กำลังโหลดโมเดล 3D… ' + Math.round((e.loaded / e.total) * 100) + '%';
    });
    if (gltf.userData.vrm) {
      vrm = gltf.userData.vrm;
      VRMUtils.removeUnnecessaryVertices(gltf.scene);
      VRMUtils.combineSkeletons(gltf.scene);
      if (vrm.meta && vrm.meta.metaVersion === '0') VRMUtils.rotateVRM0(vrm);
      root.add(vrm.scene);
    } else {
      root.add(gltf.scene);
      vrm = wrapHuman(gltf);
      root.add(vrm.humanoid.normalizedHumanBonesRoot);
    }
    vrm.scene.traverse((o) => { o.frustumCulled = false; });
    collectBones();
    const lookTarget = new THREE.Object3D();
    camera.add(lookTarget);
    scene.add(camera);
    if (vrm.lookAt) vrm.lookAt.target = lookTarget;

    vrm.update(0);
    const head = new THREE.Vector3();
    const hips = new THREE.Vector3();
    const knee = new THREE.Vector3();
    bones.head.getWorldPosition(head);
    bones.hips.getWorldPosition(hips);
    bones.leftLeg.lower.getWorldPosition(knee);
    body.hipsRest.copy(bones.hips.position);
    body.hipsY = hips.y;
    body.thigh = hips.y - knee.y;
    body.seatHipsY = knee.y + 0.06;
    frameUpper = { centerY: (head.y + 0.16 + hips.y - 0.08) / 2, height: head.y + 0.16 - (hips.y - 0.08), halfW: MODELS[modelKey].halfW };
    REST.down = MODELS[modelKey].restDown;
    setRest('left'); setRest('right');
    for (const a of Object.values(arms)) Object.assign(a.cur, structuredClone(a.target));
    frameFull = { centerY: (head.y + 0.2) / 2 - 0.02, height: head.y + 0.26, halfW: WALK_RANGE + 0.32 };
    buildProps();
    resize();

    $('startMessage').style.display = 'none';
    controls.forEach((el) => { el.disabled = false; });
    $('fileLbl').classList.remove('disabled');
    setStatus('พร้อมแล้ว — อัดเสียงหรือเลือกไฟล์เสียงให้ตัวละครพูด');
  } catch (e) {
    console.error(e);
    $('startMessage').textContent = 'โหลดโมเดลไม่สำเร็จ: ' + (e && e.message ? e.message : e);
  }
}

// Action buttons.
document.querySelectorAll('#actions [data-act]').forEach((btn) => {
  btn.addEventListener('click', () => {
    const a = btn.dataset.act;
    if (a === 'sitstand') act(motion.sitTarget > 0.5 ? 'stand' : 'sit');
    else if (a === 'smile') pulseExpression('happy', 3);
    else if (a === 'surprised') pulseExpression('surprised', 1.8);
    else if (a === 'nod') state.nod = 1.2;
    else act(a);
    nextAuto = state.t + 12;
  });
});
const autoBtn = $('autoBtn');
try { autoLife = localStorage.getItem('auto3d') !== '0'; } catch (e) { /* private mode */ }
function showAuto() { autoBtn.classList.toggle('accent', autoLife); autoBtn.textContent = 'อัตโนมัติ: ' + (autoLife ? 'เปิด' : 'ปิด'); }
autoBtn.addEventListener('click', () => {
  autoLife = !autoLife;
  try { localStorage.setItem('auto3d', autoLife ? '1' : '0'); } catch (e) { /* private mode */ }
  showAuto();
});
showAuto();

// Test hook: lets automated checks drive poses.
window.__dh3d = { arms, setGesture, setRest, voice, state, motion, act, pulseExpression, get vrm() { return vrm; } };

try { localStorage.setItem('mode', '3d'); } catch (e) { /* private mode */ }
$('videoModeBtn').addEventListener('click', () => {
  try { localStorage.setItem('mode', 'video'); } catch (e) { /* private mode */ }
});

$('fileLbl').classList.add('disabled');
boot();
