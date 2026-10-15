'use strict';

// --- Pequenos helpers --------------------------------------------------------
const $ = (id) => document.getElementById(id);
async function api(path, opts = {}) {
  const res = await fetch(path, {
    headers: { 'content-type': 'application/json' },
    credentials: 'same-origin',
    ...opts,
  });
  if (res.status === 401) { showLogin(); throw new Error('não autenticado'); }
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.ok === false) throw new Error(data.error || ('HTTP ' + res.status));
  return data;
}
function fmtTime(ms) {
  return new Date(ms).toLocaleString('pt-PT', { dateStyle: 'short', timeStyle: 'medium' });
}
function fmtClock(ms) {
  return new Date(ms).toLocaleTimeString('pt-PT', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}
function ago(ms) {
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (s < 60) return `há ${s}s`;
  const m = Math.round(s / 60);
  if (m < 60) return `há ${m} min`;
  const h = Math.floor(m / 60);
  return `há ${h}h ${m % 60}min`;
}

// --- Login -------------------------------------------------------------------
function showLogin() { $('login').style.display = 'grid'; $('app').style.display = 'none'; }
function showApp() { $('login').style.display = 'none'; $('app').style.display = 'grid'; setTimeout(() => map && map.invalidateSize(), 50); }

async function doLogin() {
  $('loginErr').textContent = '';
  try {
    await api('/api/login', { method: 'POST', body: JSON.stringify({ password: $('pw').value }) });
    $('pw').value = '';
    start();
  } catch (e) {
    $('loginErr').textContent = e.message === 'não autenticado' ? 'Palavra-passe errada.' : e.message;
  }
}
$('loginBtn').onclick = doLogin;
$('pw').addEventListener('keydown', (e) => { if (e.key === 'Enter') doLogin(); });
$('logoutBtn').onclick = async () => { await fetch('/api/logout', { method: 'POST', credentials: 'same-origin' }); showLogin(); };

// --- Mapa --------------------------------------------------------------------
let map, marker, accCircle, track, schoolMarker, schoolCircle;
let lastPoint = null;
let settings = null;

function initMap() {
  if (map) return;
  map = L.map('map', { zoomControl: true }).setView([38.736, -9.142], 13); // Lisboa por omissão
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19, attribution: '&copy; OpenStreetMap',
  }).addTo(map);
  map.on('click', (e) => {
    $('schoolLat').value = e.latlng.lat.toFixed(6);
    $('schoolLng').value = e.latlng.lng.toFixed(6);
    drawSchoolPreview();
  });
}

function setLastMarker(pt) {
  const ll = [pt.lat, pt.lng];
  if (!marker) {
    marker = L.circleMarker(ll, { radius: 9, color: '#4f8cff', fillColor: '#4f8cff', fillOpacity: 0.9, weight: 2 }).addTo(map);
    accCircle = L.circle(ll, { radius: pt.acc || 0, color: '#4f8cff', opacity: 0.25, fillOpacity: 0.08 }).addTo(map);
  } else {
    marker.setLatLng(ll);
    accCircle.setLatLng(ll).setRadius(pt.acc || 0);
  }
  marker.bindPopup(`${fmtTime(pt.t)}<br>precisão ±${Math.round(pt.acc || 0)} m`);
}

// --- Atualização "agora" -----------------------------------------------------
async function refreshLast() {
  try {
    const d = await api('/api/last');
    const pill = $('statusPill');
    lastPoint = d.point;
    if (!d.point) {
      pill.textContent = 'sem dados'; pill.className = 'pill warn';
      $('lastTime').textContent = '—'; $('lastInfo').textContent = 'Ainda não chegou nenhuma localização.';
    } else {
      const p = d.point;
      const freshMin = (Date.now() - p.t) / 60000;
      pill.textContent = ago(p.t);
      pill.className = 'pill ' + (freshMin < 5 ? 'ok' : freshMin < 20 ? 'warn' : 'bad');
      $('lastTime').textContent = fmtClock(p.t);
      $('lastInfo').innerHTML =
        `${fmtTime(p.t)}<br>` +
        `precisão ±${Math.round(p.acc || 0)} m` +
        (p.spd != null ? ` · ${(p.spd * 3.6).toFixed(0)} km/h` : '');
      setLastMarker(p);

      if (p.bat != null) {
        const bp = $('batPill'); bp.style.display = '';
        bp.textContent = `🔋 ${p.bat}%`;
        bp.className = 'pill ' + (p.bat > 30 ? 'ok' : p.bat > 15 ? 'warn' : 'bad');
      }
    }

    // Estado "nas aulas".
    const sp = $('schoolPill');
    const st = d.school;
    if (st && st.known) {
      sp.style.display = '';
      if (st.during && st.atSchool) { sp.textContent = '🏫 nas aulas'; sp.className = 'pill ok'; }
      else if (st.during && !st.atSchool) { sp.textContent = `⚠️ fora da escola (${st.distanceM} m)`; sp.className = 'pill bad'; }
      else if (!st.during && st.atSchool) { sp.textContent = '🏫 na escola'; sp.className = 'pill ok'; }
      else { sp.textContent = 'fora de horário'; sp.className = 'pill'; }
    } else { sp.style.display = 'none'; }

    // Estado do modo ao vivo.
    $('liveState').textContent = d.live ? `Ativo até ${fmtClock(d.live)}.` : '';
  } catch (e) { /* silencioso; o próximo ciclo tenta de novo */ }
}

// --- Histórico ---------------------------------------------------------------
async function loadDay(day) {
  try {
    const d = await api('/api/track?day=' + encodeURIComponent(day));
    if (track) { map.removeLayer(track); track = null; }
    if (!d.points.length) { $('dayInfo').textContent = 'Sem registos nesse dia.'; return; }
    const latlngs = d.points.map((p) => [p.lat, p.lng]);
    track = L.polyline(latlngs, { color: '#ffb020', weight: 4, opacity: 0.8 }).addTo(map);
    // marcadores de início e fim
    L.circleMarker(latlngs[0], { radius: 6, color: '#3ecf8e', fillOpacity: 1 }).addTo(track);
    L.circleMarker(latlngs[latlngs.length - 1], { radius: 6, color: '#ff5c5c', fillOpacity: 1 }).addTo(track);
    map.fitBounds(track.getBounds(), { padding: [40, 40] });
    const first = d.points[0].t, last = d.points[d.points.length - 1].t;
    $('dayInfo').textContent = `${d.count} pontos · das ${fmtClock(first)} às ${fmtClock(last)}`;
  } catch (e) { $('dayInfo').textContent = e.message; }
}

// --- Definições --------------------------------------------------------------
function fillSettings(s) {
  settings = s;
  $('intervalSec').value = s.intervalSec;
  $('minDistanceM').value = s.minDistanceM;
  $('liveIntervalSec').value = s.liveIntervalSec;
  $('heartbeatMin').value = s.heartbeatMin;
  if (s.school) {
    $('schoolName').value = s.school.name || '';
    $('schoolLat').value = s.school.lat;
    $('schoolLng').value = s.school.lng;
    $('schoolRadius').value = s.school.radiusM;
    drawSchoolPreview();
  }
}

function drawSchoolPreview() {
  const lat = parseFloat($('schoolLat').value), lng = parseFloat($('schoolLng').value);
  const r = parseFloat($('schoolRadius').value) || 150;
  if (!isFinite(lat) || !isFinite(lng)) return;
  const ll = [lat, lng];
  if (!schoolMarker) {
    schoolMarker = L.marker(ll).addTo(map);
    schoolCircle = L.circle(ll, { radius: r, color: '#3ecf8e', fillOpacity: 0.08 }).addTo(map);
  } else {
    schoolMarker.setLatLng(ll);
    schoolCircle.setLatLng(ll).setRadius(r);
  }
}
$('schoolRadius').addEventListener('input', drawSchoolPreview);

async function saveSettings() {
  try {
    const body = {
      intervalSec: +$('intervalSec').value,
      minDistanceM: +$('minDistanceM').value,
      liveIntervalSec: +$('liveIntervalSec').value,
      heartbeatMin: +$('heartbeatMin').value,
    };
    const d = await api('/api/settings', { method: 'POST', body: JSON.stringify(body) });
    fillSettings(d.settings);
    $('settingsInfo').textContent = 'Guardado. O telemóvel ajusta-se no próximo envio.';
    setTimeout(() => $('settingsInfo').textContent = '', 4000);
  } catch (e) { $('settingsInfo').textContent = e.message; }
}
$('saveSettings').onclick = saveSettings;

async function saveSchool() {
  const lat = parseFloat($('schoolLat').value), lng = parseFloat($('schoolLng').value);
  if (!isFinite(lat) || !isFinite(lng)) { alert('Marca a escola no mapa primeiro.'); return; }
  const school = { name: $('schoolName').value || 'Escola', lat, lng, radiusM: +$('schoolRadius').value || 150 };
  const d = await api('/api/settings', { method: 'POST', body: JSON.stringify({ school }) });
  fillSettings(d.settings);
  refreshLast();
}
$('saveSchool').onclick = saveSchool;
$('clearSchool').onclick = async () => {
  await api('/api/settings', { method: 'POST', body: JSON.stringify({ school: null }) });
  if (schoolMarker) { map.removeLayer(schoolMarker); map.removeLayer(schoolCircle); schoolMarker = null; }
  $('schoolLat').value = $('schoolLng').value = '';
};
$('schoolHere').onclick = () => {
  if (!lastPoint) { alert('Ainda não há posição.'); return; }
  $('schoolLat').value = lastPoint.lat.toFixed(6);
  $('schoolLng').value = lastPoint.lng.toFixed(6);
  drawSchoolPreview();
};

// --- Pessoal: a minha própria localização (via navegador) --------------------
let meMarker, meAccCircle, meWatchId = null, meLastSent = 0;

function setMeMarker(pt) {
  const ll = [pt.lat, pt.lng];
  if (!meMarker) {
    meMarker = L.circleMarker(ll, { radius: 9, color: '#3ecf8e', fillColor: '#3ecf8e', fillOpacity: 0.9, weight: 2 }).addTo(map);
    meAccCircle = L.circle(ll, { radius: pt.acc || 0, color: '#3ecf8e', opacity: 0.25, fillOpacity: 0.08 }).addTo(map);
  } else {
    meMarker.setLatLng(ll);
    meAccCircle.setLatLng(ll).setRadius(pt.acc || 0);
  }
  meMarker.bindPopup(`Eu · ${fmtTime(pt.t)}<br>precisão ±${Math.round(pt.acc || 0)} m`);
}

async function refreshMe() {
  try {
    const d = await api('/api/me/last');
    if (d.point) setMeMarker(d.point);
  } catch (e) { /* silencioso */ }
}

async function sendMePosition(pos) {
  const c = pos.coords;
  const body = {
    lat: c.latitude, lng: c.longitude,
    acc: c.accuracy ?? null,
    spd: c.speed ?? null,
    t: pos.timestamp || Date.now(),
  };
  try {
    await api('/api/me/ingest', { method: 'POST', body: JSON.stringify(body) });
    meLastSent = Date.now();
    setMeMarker({ lat: body.lat, lng: body.lng, acc: body.acc, t: body.t });
    $('meState').textContent = `A partilhar · último envio ${fmtClock(meLastSent)} (±${Math.round(body.acc || 0)} m).`;
  } catch (e) {
    $('meState').textContent = 'Erro a enviar: ' + e.message;
  }
}

function startMeShare() {
  if (!('geolocation' in navigator)) { $('meState').textContent = 'Este navegador não suporta localização.'; return; }
  if (meWatchId !== null) return;
  $('meState').textContent = 'A pedir permissão de localização…';
  meWatchId = navigator.geolocation.watchPosition(
    sendMePosition,
    (err) => { $('meState').textContent = 'Localização indisponível: ' + err.message; },
    { enableHighAccuracy: true, maximumAge: 15000, timeout: 30000 }
  );
}

function stopMeShare() {
  if (meWatchId !== null) { navigator.geolocation.clearWatch(meWatchId); meWatchId = null; }
  $('meState').textContent = 'Parado.';
}
$('meStart').onclick = startMeShare;
$('meStop').onclick = stopMeShare;

// --- Modo ao vivo ------------------------------------------------------------
async function setLive(minutes) {
  await api('/api/live', { method: 'POST', body: JSON.stringify({ minutes }) });
  refreshLast();
}
$('live10').onclick = () => setLive(10);
$('live30').onclick = () => setLive(30);
$('liveOff').onclick = () => setLive(0);

// --- Botões diversos ---------------------------------------------------------
$('centerBtn').onclick = () => { if (lastPoint) map.setView([lastPoint.lat, lastPoint.lng], 16); };
$('gmapsBtn').onclick = () => {
  if (lastPoint) window.open(`https://www.google.com/maps?q=${lastPoint.lat},${lastPoint.lng}`, '_blank');
};
$('loadDay').onclick = () => loadDay($('day').value);
$('todayBtn').onclick = () => { $('day').value = todayStr(); loadDay($('day').value); };
function todayStr() { return new Date().toLocaleDateString('en-CA'); } // YYYY-MM-DD local

// --- Arranque ----------------------------------------------------------------
let timer = null;
async function start() {
  showApp();
  initMap();
  try {
    const s = await api('/api/settings');
    fillSettings(s.settings);
  } catch (e) { if (e.message === 'não autenticado') return; }
  $('day').value = todayStr();
  await refreshLast();
  await refreshMe();
  if (timer) clearInterval(timer);
  timer = setInterval(() => { refreshLast(); refreshMe(); }, 10000);
}

// Tenta entrar diretamente (cookie já válido?).
(async () => {
  try { await api('/api/settings'); start(); }
  catch { showLogin(); }
})();
