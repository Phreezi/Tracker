// Servidor do Tracker: recebe localizações do telemóvel e serve o painel web.
// Sem dependências externas: usa apenas módulos do Node.js (>= 22.13).
'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { DatabaseSync } = require('node:sqlite');

const PORT = Number(process.env.PORT || 8080);
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD;
const DEVICE_TOKEN = process.env.DEVICE_TOKEN;
const TZ = process.env.TZ_SCHOOL || process.env.TZ || 'Europe/Lisbon';
const RETENTION_DAYS = Number(process.env.RETENTION_DAYS || 90);

if (!ADMIN_PASSWORD || !DEVICE_TOKEN) {
  console.error('Defina as variáveis ADMIN_PASSWORD e DEVICE_TOKEN antes de iniciar.');
  process.exit(1);
}
if (DEVICE_TOKEN.length < 16) {
  console.error('DEVICE_TOKEN deve ter pelo menos 16 caracteres.');
  process.exit(1);
}

fs.mkdirSync(DATA_DIR, { recursive: true });

// --- Base de dados -----------------------------------------------------------

const db = new DatabaseSync(path.join(DATA_DIR, 'tracker.db'));
db.exec(`
  PRAGMA journal_mode = WAL;
  CREATE TABLE IF NOT EXISTS points (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    t INTEGER NOT NULL,          -- hora do telemóvel (ms)
    lat REAL NOT NULL,
    lng REAL NOT NULL,
    acc REAL,                    -- precisão (m)
    spd REAL,                    -- velocidade (m/s)
    bat INTEGER,                 -- bateria (%)
    received INTEGER NOT NULL
  );
  CREATE INDEX IF NOT EXISTS points_t ON points(t);
  CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL);
`);

const DEFAULT_SETTINGS = {
  intervalSec: 60,      // de quanto em quanto tempo o telemóvel lê a localização
  minDistanceM: 15,     // só envia se se deslocou pelo menos isto
  heartbeatMin: 10,     // sinal de vida mesmo parada (bateria, "estou ligada")
  liveIntervalSec: 10,  // intervalo usado no "modo ao vivo"
  liveUntil: 0,
  school: null,         // { name, lat, lng, radiusM }
  // horário: dia da semana (0=domingo..6=sábado) -> lista de { start, end }
  schedule: {
    1: [{ start: '08:15', end: '13:30' }, { start: '14:30', end: '17:00' }],
    2: [{ start: '08:15', end: '13:30' }, { start: '14:30', end: '17:00' }],
    3: [{ start: '08:15', end: '13:30' }],
    4: [{ start: '08:15', end: '13:30' }, { start: '14:30', end: '17:00' }],
    5: [{ start: '08:15', end: '13:30' }],
  },
};

const kvGet = db.prepare('SELECT v FROM kv WHERE k = ?');
const kvSet = db.prepare('INSERT INTO kv (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v');

function getKv(k, fallback) {
  const row = kvGet.get(k);
  return row ? JSON.parse(row.v) : fallback;
}
function setKv(k, v) {
  kvSet.run(k, JSON.stringify(v));
}

function getSettings() {
  return { ...DEFAULT_SETTINGS, ...getKv('settings', {}) };
}

let sessionSecret = getKv('sessionSecret', null);
if (!sessionSecret) {
  sessionSecret = crypto.randomBytes(32).toString('hex');
  setKv('sessionSecret', sessionSecret);
}

const insertPoint = db.prepare(
  'INSERT INTO points (t, lat, lng, acc, spd, bat, received) VALUES (?, ?, ?, ?, ?, ?, ?)'
);
const selectRange = db.prepare(
  'SELECT t, lat, lng, acc, spd, bat FROM points WHERE t >= ? AND t < ? ORDER BY t'
);
const selectLast = db.prepare('SELECT t, lat, lng, acc, spd, bat FROM points ORDER BY t DESC LIMIT 1');
const pruneOld = db.prepare('DELETE FROM points WHERE t < ?');
// Limpa pontos mais antigos que a retenção, uma vez por dia.
function prune() {
  const cutoff = Date.now() - RETENTION_DAYS * 86400_000;
  try { pruneOld.run(cutoff); } catch (_) {}
}
prune();
setInterval(prune, 86400_000).unref();

// --- Horário / "está nas aulas?" --------------------------------------------

// Devolve { y, m, d, weekday, minutes } para um instante na timezone da escola.
function localParts(ms) {
  const fmt = new Intl.DateTimeFormat('en-GB', {
    timeZone: TZ, hour12: false,
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', weekday: 'short',
  });
  const p = {};
  for (const part of fmt.formatToParts(new Date(ms))) p[part.type] = part.value;
  const weekdayMap = { Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6 };
  return {
    y: +p.year, m: +p.month, d: +p.day,
    weekday: weekdayMap[p.weekday],
    minutes: (+p.hour) * 60 + (+p.minute),
  };
}

function toMinutes(hhmm) {
  const [h, m] = hhmm.split(':').map(Number);
  return h * 60 + m;
}

// Está dentro de uma janela de aulas nesse instante?
function inSchoolHours(ms, settings) {
  const { weekday, minutes } = localParts(ms);
  const windows = settings.schedule?.[weekday] || [];
  return windows.some((w) => minutes >= toMinutes(w.start) && minutes < toMinutes(w.end));
}

function haversineM(aLat, aLng, bLat, bLng) {
  const R = 6371000;
  const toRad = (x) => (x * Math.PI) / 180;
  const dLat = toRad(bLat - aLat);
  const dLng = toRad(bLng - aLng);
  const s =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(aLat)) * Math.cos(toRad(bLat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}

// É um dia de escola (tem janelas definidas)?
function isSchoolDay(ms, settings) {
  const { weekday } = localParts(ms);
  return (settings.schedule?.[weekday] || []).length > 0;
}

// Avalia a presença na escola: no horário, está perto da escola?
function schoolStatus(point, settings) {
  if (!point) return { known: false, reason: 'sem_dados' };
  const school = settings.school;
  const during = inSchoolHours(point.t, settings);
  if (!school) {
    return { known: false, during, reason: 'escola_nao_definida' };
  }
  const dist = haversineM(point.lat, point.lng, school.lat, school.lng);
  const atSchool = dist <= (school.radiusM || 150);
  return { known: true, during, atSchool, distanceM: Math.round(dist) };
}

// --- Sessões (cookie assinado) ----------------------------------------------

function sign(value) {
  const h = crypto.createHmac('sha256', sessionSecret).update(value).digest('base64url');
  return `${value}.${h}`;
}
function verify(signed) {
  if (!signed) return null;
  const i = signed.lastIndexOf('.');
  if (i < 0) return null;
  const value = signed.slice(0, i);
  const expected = sign(value);
  if (expected.length !== signed.length) return null;
  if (!crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(signed))) return null;
  const exp = Number(value.split('|')[1]);
  if (!Number.isFinite(exp) || Date.now() > exp) return null;
  return value;
}
function makeSession() {
  // válida 30 dias
  return sign(`ok|${Date.now() + 30 * 86400_000}`);
}
function parseCookies(req) {
  const out = {};
  const raw = req.headers.cookie;
  if (!raw) return out;
  for (const part of raw.split(';')) {
    const i = part.indexOf('=');
    if (i > 0) out[part.slice(0, i).trim()] = decodeURIComponent(part.slice(i + 1).trim());
  }
  return out;
}
function isAuthed(req) {
  return verify(parseCookies(req).sid) !== null;
}

// Comparação de palavra-passe resistente a timing.
function passwordOk(given) {
  const a = Buffer.from(String(given));
  const b = Buffer.from(ADMIN_PASSWORD);
  if (a.length !== b.length) {
    // ainda assim compara algo do mesmo tamanho para não vazar o tamanho
    crypto.timingSafeEqual(b, b);
    return false;
  }
  return crypto.timingSafeEqual(a, b);
}
function tokenOk(given) {
  const a = Buffer.from(String(given || ''));
  const b = Buffer.from(DEVICE_TOKEN);
  if (a.length !== b.length) { crypto.timingSafeEqual(b, b); return false; }
  return crypto.timingSafeEqual(a, b);
}

// --- Rate limiting simples (por IP) para o login -----------------------------

const loginHits = new Map();
function loginThrottled(ip) {
  const now = Date.now();
  const rec = loginHits.get(ip) || { n: 0, until: 0 };
  if (now < rec.until) return true;
  return false;
}
function loginFail(ip) {
  const now = Date.now();
  const rec = loginHits.get(ip) || { n: 0, until: 0 };
  rec.n += 1;
  if (rec.n >= 5) { rec.until = now + 60_000; rec.n = 0; }
  loginHits.set(ip, rec);
}

// --- Helpers HTTP ------------------------------------------------------------

function clientIp(req) {
  const xff = req.headers['x-forwarded-for'];
  if (xff) return xff.split(',')[0].trim();
  return req.socket.remoteAddress || 'unknown';
}

function sendJson(res, code, obj, extraHeaders = {}) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    ...extraHeaders,
  });
  res.end(body);
}

function readBody(req, limit = 64 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) { reject(new Error('payload demasiado grande')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.json': 'application/json; charset=utf-8',
};

function num(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

// --- Servidor ----------------------------------------------------------------

const PUBLIC_DIR = path.join(__dirname, 'public');

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const p = url.pathname;
  const method = req.method;

  try {
    // Ingestão de localização pelo telemóvel (autenticada por token).
    if (p === '/api/ingest' && method === 'POST') {
      if (!tokenOk(req.headers['x-device-token'])) {
        return sendJson(res, 401, { ok: false, error: 'token inválido' });
      }
      let data;
      try { data = JSON.parse(await readBody(req)); }
      catch { return sendJson(res, 400, { ok: false, error: 'json inválido' }); }

      // Aceita um ponto ou um lote de pontos (para envios acumulados offline).
      const items = Array.isArray(data) ? data : (Array.isArray(data.points) ? data.points : [data]);
      const now = Date.now();
      let saved = 0;
      for (const it of items) {
        const lat = num(it.lat), lng = num(it.lng);
        if (lat === null || lng === null || lat < -90 || lat > 90 || lng < -180 || lng > 180) continue;
        let t = num(it.t);
        if (t === null || t <= 0 || t > now + 300_000) t = now; // ignora relógios no futuro
        insertPoint.run(
          Math.round(t), lat, lng,
          num(it.acc), num(it.spd), num(it.bat) === null ? null : Math.round(num(it.bat)),
          now
        );
        saved += 1;
      }
      // Responde já com as definições atuais, para o telemóvel se ajustar.
      const s = getSettings();
      const live = s.liveUntil && now < s.liveUntil;
      return sendJson(res, 200, {
        ok: true, saved,
        config: {
          intervalSec: live ? s.liveIntervalSec : s.intervalSec,
          minDistanceM: s.minDistanceM,
          heartbeatMin: s.heartbeatMin,
          liveUntil: s.liveUntil || 0,
          serverTime: now,
        },
      });
    }

    // Login do painel.
    if (p === '/api/login' && method === 'POST') {
      const ip = clientIp(req);
      if (loginThrottled(ip)) return sendJson(res, 429, { ok: false, error: 'muitas tentativas, aguarde' });
      let data;
      try { data = JSON.parse(await readBody(req)); } catch { data = {}; }
      if (!passwordOk(data.password)) {
        loginFail(ip);
        return sendJson(res, 401, { ok: false, error: 'palavra-passe errada' });
      }
      const cookie = `sid=${encodeURIComponent(makeSession())}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${30 * 86400}${isSecureReq(req) ? '; Secure' : ''}`;
      return sendJson(res, 200, { ok: true }, { 'set-cookie': cookie });
    }

    if (p === '/api/logout' && method === 'POST') {
      return sendJson(res, 200, { ok: true }, { 'set-cookie': 'sid=; HttpOnly; Path=/; Max-Age=0' });
    }

    // A partir daqui, tudo /api/ exige sessão do painel.
    if (p.startsWith('/api/')) {
      if (!isAuthed(req)) return sendJson(res, 401, { ok: false, error: 'não autenticado' });

      if (p === '/api/last' && method === 'GET') {
        const last = selectLast.get();
        const s = getSettings();
        return sendJson(res, 200, {
          ok: true,
          point: last || null,
          serverTime: Date.now(),
          school: schoolStatus(last, s),
          live: s.liveUntil && Date.now() < s.liveUntil ? s.liveUntil : 0,
        });
      }

      // Pontos de um dia (YYYY-MM-DD na timezone da escola) ou intervalo from/to (ms).
      if (p === '/api/track' && method === 'GET') {
        let from = num(url.searchParams.get('from'));
        let to = num(url.searchParams.get('to'));
        const day = url.searchParams.get('day');
        if (day && /^\d{4}-\d{2}-\d{2}$/.test(day)) {
          // meia-noite local -> ms. Aproximação: usa offset da timezone nesse dia.
          const bounds = dayBoundsMs(day);
          from = bounds.from; to = bounds.to;
        }
        if (from === null) from = Date.now() - 24 * 3600_000;
        if (to === null) to = Date.now() + 1;
        const rows = selectRange.all(Math.round(from), Math.round(to));
        return sendJson(res, 200, { ok: true, points: rows, count: rows.length });
      }

      if (p === '/api/settings' && method === 'GET') {
        return sendJson(res, 200, { ok: true, settings: getSettings() });
      }

      if (p === '/api/settings' && method === 'POST') {
        let data;
        try { data = JSON.parse(await readBody(req)); } catch { return sendJson(res, 400, { ok: false }); }
        const cur = getSettings();
        const next = { ...cur };
        if (num(data.intervalSec) !== null) next.intervalSec = clamp(num(data.intervalSec), 5, 3600);
        if (num(data.minDistanceM) !== null) next.minDistanceM = clamp(num(data.minDistanceM), 0, 5000);
        if (num(data.heartbeatMin) !== null) next.heartbeatMin = clamp(num(data.heartbeatMin), 1, 240);
        if (num(data.liveIntervalSec) !== null) next.liveIntervalSec = clamp(num(data.liveIntervalSec), 5, 600);
        if (data.school === null) next.school = null;
        else if (data.school && num(data.school.lat) !== null && num(data.school.lng) !== null) {
          next.school = {
            name: String(data.school.name || 'Escola').slice(0, 80),
            lat: num(data.school.lat), lng: num(data.school.lng),
            radiusM: clamp(num(data.school.radiusM) ?? 150, 30, 2000),
          };
        }
        if (data.schedule && typeof data.schedule === 'object') next.schedule = sanitizeSchedule(data.schedule);
        setKv('settings', next);
        return sendJson(res, 200, { ok: true, settings: next });
      }

      // Ativa/desliga o "modo ao vivo" (intervalo curto) por N minutos.
      if (p === '/api/live' && method === 'POST') {
        let data; try { data = JSON.parse(await readBody(req)); } catch { data = {}; }
        const cur = getSettings();
        const minutes = clamp(num(data.minutes) ?? 10, 0, 120);
        cur.liveUntil = minutes > 0 ? Date.now() + minutes * 60_000 : 0;
        setKv('settings', cur);
        return sendJson(res, 200, { ok: true, liveUntil: cur.liveUntil });
      }

      return sendJson(res, 404, { ok: false, error: 'rota não encontrada' });
    }

    // Ficheiros estáticos do painel.
    if (method === 'GET' || method === 'HEAD') {
      let file = p === '/' ? '/index.html' : p;
      file = path.normalize(file).replace(/^(\.\.[/\\])+/, '');
      const full = path.join(PUBLIC_DIR, file);
      if (!full.startsWith(PUBLIC_DIR)) { res.writeHead(403); return res.end('proibido'); }
      fs.readFile(full, (err, buf) => {
        if (err) { res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' }); return res.end('não encontrado'); }
        const ext = path.extname(full).toLowerCase();
        res.writeHead(200, { 'content-type': MIME[ext] || 'application/octet-stream' });
        res.end(method === 'HEAD' ? undefined : buf);
      });
      return;
    }

    res.writeHead(405); res.end('método não permitido');
  } catch (e) {
    sendJson(res, 500, { ok: false, error: 'erro interno' });
    console.error('erro:', e.message);
  }
});

function isSecureReq(req) {
  return req.headers['x-forwarded-proto'] === 'https' || req.socket.encrypted === true;
}

function clamp(n, lo, hi) { return Math.max(lo, Math.min(hi, n)); }

function sanitizeSchedule(sch) {
  const out = {};
  for (let d = 0; d <= 6; d++) {
    const list = sch[d] || sch[String(d)];
    if (!Array.isArray(list)) continue;
    const windows = [];
    for (const w of list) {
      if (w && /^\d{1,2}:\d{2}$/.test(w.start || '') && /^\d{1,2}:\d{2}$/.test(w.end || '')) {
        windows.push({ start: w.start, end: w.end });
      }
    }
    if (windows.length) out[d] = windows;
  }
  return out;
}

// Limites de um dia YYYY-MM-DD na timezone da escola, convertidos para ms UTC.
function dayBoundsMs(day) {
  const [y, m, d] = day.split('-').map(Number);
  // Estima o offset da timezone às 12:00 desse dia e usa-o para os limites.
  const noonUtc = Date.UTC(y, m - 1, d, 12, 0, 0);
  const offsetMin = tzOffsetMinutes(noonUtc);
  const from = Date.UTC(y, m - 1, d, 0, 0, 0) - offsetMin * 60_000;
  const to = from + 86400_000;
  return { from, to };
}

// Offset (minutos) da timezone da escola num dado instante.
function tzOffsetMinutes(ms) {
  const dtf = new Intl.DateTimeFormat('en-US', {
    timeZone: TZ, hour12: false,
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
  const parts = {};
  for (const x of dtf.formatToParts(new Date(ms))) parts[x.type] = x.value;
  const asUtc = Date.UTC(+parts.year, +parts.month - 1, +parts.day, +parts.hour, +parts.minute, +parts.second);
  return Math.round((asUtc - ms) / 60_000);
}

server.listen(PORT, () => {
  console.log(`Tracker a correr em http://0.0.0.0:${PORT}  (timezone: ${TZ})`);
});
