// คนดิจิทัล 3D — a VRM avatar whose arms, hands and every finger are animated
// procedurally from the speech audio, so gestures never repeat as a loop.
// Mouth shapes come from a live spectrum analysis of the same audio.

import * as THREE from 'three';
import { GLTFLoader } from './vendor/three/addons/loaders/GLTFLoader.js';
import { VRMLoaderPlugin, VRMUtils } from './vendor/three-vrm.module.min.js';

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

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(28, 1, 0.1, 20);
scene.add(new THREE.HemisphereLight(0xfff4e6, 0x334155, 1.6));
const key = new THREE.DirectionalLight(0xffffff, 1.8);
key.position.set(0.8, 1.6, 2.0);
scene.add(key);
const rim = new THREE.DirectionalLight(0xffc48a, 0.9);
rim.position.set(-1.2, 1.8, -1.5);
scene.add(rim);

let vrm = null;
let frame = { centerY: 1.2, height: 1.0 };

function resize() {
  const w = window.innerWidth;
  const h = window.innerHeight;
  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  // Fit the upper body (head to hips) and leave room for gesturing hands.
  const halfH = frame.height / 2;
  const halfW = 0.34;
  const t = Math.tan(THREE.MathUtils.degToRad(camera.fov / 2));
  const dist = Math.max(halfH / t, halfW / (t * camera.aspect));
  camera.position.set(0, frame.centerY + 0.04, dist);
  camera.lookAt(0, frame.centerY, 0);
  camera.updateProjectionMatrix();
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
  qb.setFromAxisAngle(X, -(c.fwd + a.beat * 0.15));
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

  if (currentSource && state.t > state.nextGesture && state.silentFor < 0.6) {
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
  em.setValue('blink', Math.sin(Math.min(1, state.blink) * Math.PI));
  em.setValue('relaxed', 0.25);
}

function applyBody() {
  const t = state.t;
  const breathe = Math.sin(t * 1.6) * 0.012;
  const sway = Math.sin(t * 0.37) * 0.02 + Math.sin(t * 0.83 + 1) * 0.01;
  const talk = voice.level;
  if (bones.spine) bones.spine.rotation.set(breathe + talk * 0.02, sway * 0.6, sway * 0.4);
  if (bones.chest) bones.chest.rotation.set(breathe, sway * 0.5, 0);
  if (bones.neck) bones.neck.rotation.set(0.04 + state.nod * 0.06, Math.sin(t * 0.5) * 0.05, Math.sin(t * 0.31) * 0.03);
  if (bones.head) bones.head.rotation.set(-0.02 + state.nod * 0.08 + talk * 0.03, Math.sin(t * 0.7 + 2) * 0.06, Math.sin(t * 0.45) * 0.04);
  if (bones.hips) bones.hips.rotation.set(0, Math.sin(t * 0.25) * 0.03, Math.sin(t * 0.2) * 0.015);
  for (const side of Object.keys(SIDES)) {
    const sh = bones[side].shoulder;
    if (sh) sh.rotation.set(0, 0, -SIDES[side] * (breathe * 0.5 + 0.02));
  }
}

// ---------- loop ----------

const clock = new THREE.Clock();
function tick() {
  const dt = Math.min(clock.getDelta(), 0.05);
  if (vrm) {
    analyseVoice(dt);
    updateBehaviour(dt);
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

// ---------- boot ----------

async function boot() {
  resize();
  requestAnimationFrame(tick);
  try {
    const loader = new GLTFLoader();
    loader.register((parser) => new VRMLoaderPlugin(parser));
    const gltf = await loader.loadAsync('models/avatar.vrm', (e) => {
      if (e.total) $('startMessage').textContent = 'กำลังโหลดโมเดล 3D… ' + Math.round((e.loaded / e.total) * 100) + '%';
    });
    vrm = gltf.userData.vrm;
    VRMUtils.removeUnnecessaryVertices(gltf.scene);
    VRMUtils.combineSkeletons(gltf.scene);
    if (vrm.meta && vrm.meta.metaVersion === '0') VRMUtils.rotateVRM0(vrm);
    vrm.scene.traverse((o) => { o.frustumCulled = false; });
    scene.add(vrm.scene);
    collectBones();
    const lookTarget = new THREE.Object3D();
    camera.add(lookTarget);
    scene.add(camera);
    if (vrm.lookAt) vrm.lookAt.target = lookTarget;

    vrm.update(0);
    const head = new THREE.Vector3();
    const hips = new THREE.Vector3();
    bones.head.getWorldPosition(head);
    bones.hips.getWorldPosition(hips);
    frame = { centerY: (head.y + 0.16 + hips.y - 0.05) / 2, height: head.y + 0.16 - (hips.y - 0.05) };
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

// Test hook: lets automated checks drive poses.
window.__dh3d = { arms, setGesture, setRest, voice, state, get vrm() { return vrm; } };

try { localStorage.setItem('mode', '3d'); } catch (e) { /* private mode */ }
$('videoModeBtn').addEventListener('click', () => {
  try { localStorage.setItem('mode', 'video'); } catch (e) { /* private mode */ }
});

$('fileLbl').classList.add('disabled');
boot();
