// คนดิจิทัล Lite — mobile shell around the DH_live mini web runtime.
// Everything runs on-device: audio is converted to 16 kHz mono WAV in the
// browser and handed to the WASM lip-sync engine via Module._setAudioBuffer.

const $ = (id) => document.getElementById(id);
const statusEl = $('status');
const recBtn = $('recBtn');
const demoBtn = $('demoBtn');
const stopBtn = $('stopBtn');
const audioFile = $('audioFile');
const avatarSelect = $('avatarSelect');
const bgBtn = $('bgBtn');

let audioCtx = null;
let currentSource = null;
let avatarUrl = null;
let busy = false;

function setStatus(text, sticky) {
  statusEl.textContent = text || '';
  statusEl.classList.toggle('show', !!text);
  clearTimeout(setStatus.t);
  if (text && !sticky) setStatus.t = setTimeout(() => setStatus(''), 3000);
}

function setReady(ready) {
  for (const el of [recBtn, demoBtn, stopBtn, audioFile, avatarSelect]) el.disabled = !ready;
  $('fileLbl').classList.toggle('disabled', !ready);
}

function getAudioCtx() {
  if (!audioCtx || audioCtx.state === 'closed') {
    audioCtx = new (window.AudioContext || window.webkitAudioContext)();
  }
  if (audioCtx.state === 'suspended') audioCtx.resume();
  return audioCtx;
}

// ---------- audio → 16 kHz mono 16-bit WAV ----------

function decode(ctx, arrayBuffer) {
  // Safari still needs the callback form.
  return new Promise((resolve, reject) => ctx.decodeAudioData(arrayBuffer, resolve, reject));
}

async function resampleTo16k(audioBuffer) {
  const rate = 16000;
  const length = Math.max(1, Math.ceil(audioBuffer.duration * rate));
  const Offline = window.OfflineAudioContext || window.webkitOfflineAudioContext;
  const off = new Offline(1, length, rate);
  const src = off.createBufferSource();
  src.buffer = audioBuffer;
  src.connect(off.destination);
  src.start(0);
  const rendered = await off.startRendering();
  return rendered.getChannelData(0);
}

function encodeWav(samples, rate) {
  const buf = new ArrayBuffer(44 + samples.length * 2);
  const v = new DataView(buf);
  const str = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
  str(0, 'RIFF'); v.setUint32(4, 36 + samples.length * 2, true); str(8, 'WAVE');
  str(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
  str(36, 'data'); v.setUint32(40, samples.length * 2, true);
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    v.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
  }
  return new Uint8Array(buf);
}

async function toWav16k(arrayBuffer) {
  const decoded = await decode(getAudioCtx(), arrayBuffer);
  return encodeWav(await resampleTo16k(decoded), 16000);
}

// ---------- speak: drive the lips + play the sound ----------

function feedEngine(wavBytes) {
  const ptr = Module._malloc(wavBytes.byteLength);
  Module.HEAPU8.set(wavBytes, ptr);
  Module._setAudioBuffer(ptr, wavBytes.byteLength);
  Module._free(ptr);
}

function stopSpeaking() {
  if (currentSource) {
    currentSource.onended = null;
    try { currentSource.stop(); } catch (e) { /* already stopped */ }
    currentSource = null;
  }
  // A short silent clip resets the mouth to closed.
  if (window.Module && Module._setAudioBuffer) feedEngine(encodeWav(new Float32Array(1600), 16000));
  document.body.classList.remove('speaking');
}

async function speak(arrayBuffer) {
  stopSpeaking();
  const ctx = getAudioCtx();
  const wav = await toWav16k(arrayBuffer);
  const playable = await decode(ctx, wav.slice().buffer);
  feedEngine(wav);
  const src = ctx.createBufferSource();
  src.buffer = playable;
  src.connect(ctx.destination);
  src.onended = () => { if (currentSource === src) { currentSource = null; document.body.classList.remove('speaking'); } };
  src.start(0);
  currentSource = src;
  document.body.classList.add('speaking');
  poseCutSoon();
}

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

// ---------- controls ----------

demoBtn.addEventListener('click', () => {
  getAudioCtx();
  speakSafely(async () => (await fetch('common/test.wav')).arrayBuffer());
});

stopBtn.addEventListener('click', stopSpeaking);

audioFile.addEventListener('change', () => {
  const f = audioFile.files[0];
  audioFile.value = '';
  if (!f) return;
  getAudioCtx();
  setStatus('กำลังเตรียมเสียง…', true);
  speakSafely(() => f.arrayBuffer()).then(() => setStatus(''));
});

let recorder = null;
let recChunks = [];
let recStream = null;

recBtn.addEventListener('click', async () => {
  getAudioCtx();
  if (recorder && recorder.state === 'recording') {
    recorder.stop();
    return;
  }
  if (!navigator.mediaDevices || !window.MediaRecorder) {
    setStatus('เบราว์เซอร์นี้อัดเสียงไม่ได้ ลองใช้ “ไฟล์เสียง” แทน');
    return;
  }
  try {
    recStream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
  } catch (e) {
    setStatus('ไม่ได้รับอนุญาตให้ใช้ไมโครโฟน');
    return;
  }
  stopSpeaking();
  recChunks = [];
  recorder = new MediaRecorder(recStream);
  recorder.ondataavailable = (ev) => { if (ev.data.size) recChunks.push(ev.data); };
  recorder.onstop = () => {
    recStream.getTracks().forEach((t) => t.stop());
    recBtn.classList.remove('recording');
    recBtn.querySelector('.lbl').textContent = 'อัดเสียง';
    const blob = new Blob(recChunks, { type: recorder.mimeType });
    speakSafely(() => blob.arrayBuffer());
  };
  recorder.start();
  recBtn.classList.add('recording');
  recBtn.querySelector('.lbl').textContent = 'หยุดอัด → พูด';
  setStatus('กำลังอัด… กดอีกครั้งเพื่อให้ตัวละครพูดตาม');
});

// ---------- avatars ----------

// Android's APK packager silently un-gzips assets (combined_data.json.gz is
// shipped as combined_data.json), so accept either form of the avatar data.
VideoProcessor.prototype.fetchVideoUtilData = async function (url) {
  // Inside the APK a missing asset is a network error rather than a 404.
  if (location.hostname === 'appassets.androidplatform.net' && /^[^:]+\.gz$/.test(url)) url = url.replace(/\.gz$/, '');
  let res = await fetch(url).catch(() => null);
  if ((!res || !res.ok) && /\.gz$/.test(url)) res = await fetch(url.replace(/\.gz$/, ''));
  if (!res.ok) throw new Error('โหลดข้อมูลตัวละครไม่ได้ (' + res.status + ')');
  const bytes = new Uint8Array(await res.arrayBuffer());
  const gzipped = bytes[0] === 0x1f && bytes[1] === 0x8b;
  const text = gzipped ? pako.inflate(bytes, { to: 'string' }) : new TextDecoder().decode(bytes);
  this.combinedData = JSON.parse(text);
};

async function blobUrl(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(res.status + ' ' + url);
  return URL.createObjectURL(await res.blob());
}

async function switchAvatar(videoUrl, dataUrl) {
  stopSpeaking();
  setReady(false);
  isPaused = true;
  $('startMessage').style.display = 'block';
  try {
    await videoProcessor.init(videoUrl, dataUrl);
    await loadCombinedData();
    await setupVertsBuffers();
    if (chromaKeyCanvas) {
      chromaKeyCanvas.width = canvas_video.width;
      chromaKeyCanvas.height = canvas_video.height;
    }
    isPaused = false;
    videoProcessor.play();
    processVideoFrames();
  } catch (e) {
    console.error(e);
    setStatus('โหลดตัวละครไม่สำเร็จ');
  } finally {
    $('startMessage').style.display = 'none';
    setReady(true);
  }
}

avatarSelect.addEventListener('change', async () => {
  const dir = avatarSelect.value;
  try { localStorage.setItem('avatar', dir); } catch (e) { /* private mode */ }
  const old = avatarUrl;
  avatarUrl = await blobUrl(dir + '/01.mp4');
  await switchAvatar(avatarUrl, dir + '/combined_data.json.gz');
  if (old) URL.revokeObjectURL(old);
});

$('customLoad').addEventListener('click', async () => {
  const v = $('customVideo').files[0];
  const d = $('customData').files[0];
  if (!v || !d) { setStatus('เลือกไฟล์ให้ครบทั้ง 2 ไฟล์'); return; }
  $('menu').close();
  await switchAvatar(URL.createObjectURL(v), URL.createObjectURL(d));
});

// The avatars are shot on green screen, so chroma keying always stays on;
// this toggle only swaps the scenic backdrop for a plain one.
let showBackdrop = true;

function applyBackground() {
  bgBtn.textContent = 'พื้นหลัง: ' + (showBackdrop ? 'เปิด' : 'ปิด');
  document.body.classList.toggle('nobg', !showBackdrop);
  if (showBackdrop) setupBackgroundVideo();
  else hideBackgroundVideo();
}

bgBtn.addEventListener('click', () => {
  showBackdrop = !showBackdrop;
  try { localStorage.setItem('bg', showBackdrop ? '1' : '0'); } catch (e) { /* private mode */ }
  applyBackground();
});

$('menuBtn').addEventListener('click', () => $('menu').showModal());

// ---------- install (PWA) ----------

let deferredPrompt = null;
window.addEventListener('beforeinstallprompt', (e) => {
  if (location.hostname === 'appassets.androidplatform.net') return;
  e.preventDefault();
  deferredPrompt = e;
  $('installBtn').hidden = false;
});
$('installBtn').addEventListener('click', async () => {
  if (!deferredPrompt) return;
  deferredPrompt.prompt();
  await deferredPrompt.userChoice;
  deferredPrompt = null;
  $('installBtn').hidden = true;
});

// Skipped inside the Android APK: files are already bundled there, and
// service-worker fetches would bypass the WebView asset loader.
const inApk = location.hostname === 'appassets.androidplatform.net';
if ('serviceWorker' in navigator && location.protocol !== 'file:' && !inApk) {
  navigator.serviceWorker.register('sw.js').catch((e) => console.warn('SW', e));
}

// ---------- pose shuffling ----------
// The body motion comes from the avatar's source video. Instead of looping it
// in order, jump to random points (with a short crossfade) so the sequence of
// poses/gestures never repeats the same way. Cuts come faster while speaking.
// The longer and more varied the source video, the better this works.

const fadeCanvas = $('canvas_fade');
const fadeCtx = fadeCanvas.getContext('2d');
let poseShuffle = true;
let poseTimer = null;
const recentStarts = [];

function rand(a, b) { return a + Math.random() * (b - a); }

function schedulePoseCut(delaySec) {
  clearTimeout(poseTimer);
  if (!poseShuffle) return;
  const speaking = document.body.classList.contains('speaking');
  const hold = delaySec != null ? delaySec : (speaking ? rand(1.6, 3.2) : rand(4, 8));
  poseTimer = setTimeout(poseCut, hold * 1000);
}

function poseCutSoon() { schedulePoseCut(rand(0.15, 0.5)); }

function crossfade() {
  if (!canvas_video.width) return;
  fadeCanvas.width = canvas_video.width;
  fadeCanvas.height = canvas_video.height;
  fadeCtx.clearRect(0, 0, fadeCanvas.width, fadeCanvas.height);
  fadeCtx.drawImage(canvas_video, 0, 0);
  fadeCanvas.style.transition = 'none';
  fadeCanvas.style.opacity = '1';
  void fadeCanvas.offsetWidth; // restart the transition
  fadeCanvas.style.transition = 'opacity 280ms ease-out';
  fadeCanvas.style.opacity = '0';
}

function poseCut() {
  const v = videoProcessor.video;
  if (!v || isPaused || !isFinite(v.duration) || v.duration < 3) { schedulePoseCut(); return; }
  const span = v.duration - 0.6;
  // Prefer a point far from where we are and from the last few jumps.
  let t = rand(0, span);
  for (let i = 0; i < 12; i++) {
    const far = Math.abs(t - v.currentTime) > Math.min(2, span / 3);
    const fresh = recentStarts.every((r) => Math.abs(r - t) > Math.min(1.2, span / 6));
    if (far && fresh) break;
    t = rand(0, span);
  }
  recentStarts.push(t);
  if (recentStarts.length > 4) recentStarts.shift();
  crossfade();
  v.currentTime = t;
  schedulePoseCut();
}

$('poseToggle').addEventListener('change', (e) => {
  poseShuffle = e.target.checked;
  try { localStorage.setItem('pose', poseShuffle ? '1' : '0'); } catch (err) { /* private mode */ }
  schedulePoseCut();
});

// ---------- boot ----------

// Tighter key + stronger spill removal than the DH_live defaults (less green halo).
CONFIG.chromaKey.similarity = 0.42;
CONFIG.chromaKey.smoothness = 0.12;
CONFIG.chromaKey.spill = 0.85;

async function boot() {
  setReady(false);
  let dir = 'assets';
  try {
    dir = localStorage.getItem('avatar') || dir;
    const bg = localStorage.getItem('bg');
    if (bg !== null) showBackdrop = bg === '1';
    if (localStorage.getItem('pose') === '0') poseShuffle = false;
  } catch (e) { /* private mode */ }
  if (![...avatarSelect.options].some((o) => o.value === dir)) dir = 'assets';
  avatarSelect.value = dir;

  try {
    await qtLoad({
      qt: {
        entryFunction: window.createQtAppInstance,
        containerElements: [$('screen')],
      },
    });
    avatarUrl = await blobUrl(dir + '/01.mp4');
    CONFIG.backgroundVideoSrc = await blobUrl('background/bg.mp4');
    CONFIG.videoSrc = avatarUrl;
    CONFIG.dataSrc = dir + '/combined_data.json.gz';
    await newVideoTask();
    applyBackground();
    $('poseToggle').checked = poseShuffle;
    schedulePoseCut();
    setReady(true);
    setStatus('พร้อมแล้ว — อัดเสียงหรือเลือกไฟล์เสียงให้ตัวละครพูด');
  } catch (e) {
    console.error(e);
    const msg = $('startMessage');
    msg.textContent = 'เริ่มไม่สำเร็จ: ' + (e && e.message ? e.message : 'โหลดวิดีโอ/ WebGL2 ไม่ได้');
    msg.style.display = 'block';
  }
}

boot();
