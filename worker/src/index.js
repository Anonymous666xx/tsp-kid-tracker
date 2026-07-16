export default {
  async fetch(request, env) {
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
    };

    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders });
    }

    const url = new URL(request.url);
    const path = url.pathname;

    try {
      if (path === '/api/update-location' && request.method === 'POST') {
        return handleUpdateLocation(request, env, corsHeaders);
      }
      if (path === '/api/get-location' && request.method === 'GET') {
        return handleGetLocation(request, env, corsHeaders);
      }
      if (path === '/api/get-history' && request.method === 'GET') {
        return handleGetHistory(request, env, corsHeaders);
      }
      if (path === '/') {
        return new Response(WEBSITE_HTML, {
          headers: { 'Content-Type': 'text/html;charset=UTF-8', ...corsHeaders },
        });
      }
      return new Response('Not Found', { status: 404, headers: corsHeaders });
    } catch (e) {
      return new Response(JSON.stringify({ error: e.message }), {
        status: 500,
        headers: { 'Content-Type': 'application/json', ...corsHeaders },
      });
    }
  },

  async scheduled(event, env) {
    const db = env.DB;
    await db.prepare("DELETE FROM locations WHERE timestamp < datetime('now', '-24 hours')").run();
  }
};

async function handleUpdateLocation(request, env, corsHeaders) {
  const { code, latitude, longitude, accuracy, battery } = await request.json();

  if (!code || code.length !== 6 || !/^\d{6}$/.test(code)) {
    return new Response(JSON.stringify({ error: 'Invalid 6-digit code' }), {
      status: 400,
      headers: { 'Content-Type': 'application/json', ...corsHeaders },
    });
  }

  if (latitude == null || longitude == null) {
    return new Response(JSON.stringify({ error: 'Missing coordinates' }), {
      status: 400,
      headers: { 'Content-Type': 'application/json', ...corsHeaders },
    });
  }

  const db = env.DB;
  const id = crypto.randomUUID();
  const timestamp = new Date().toISOString();

  await db.prepare(
    'INSERT INTO locations (id, code, latitude, longitude, accuracy, battery, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?)'
  ).bind(id, code, latitude, longitude, accuracy || null, battery || null, timestamp).run();

  await db.prepare(
    'DELETE FROM locations WHERE id NOT IN (SELECT id FROM locations WHERE code = ? ORDER BY timestamp DESC LIMIT 500)'
  ).bind(code).run();

  return new Response(JSON.stringify({ ok: true, timestamp }), {
    headers: { 'Content-Type': 'application/json', ...corsHeaders },
  });
}

async function handleGetLocation(request, env, corsHeaders) {
  const url = new URL(request.url);
  const code = url.searchParams.get('code');

  if (!code || code.length !== 6 || !/^\d{6}$/.test(code)) {
    return new Response(JSON.stringify({ error: 'Invalid code' }), {
      status: 400,
      headers: { 'Content-Type': 'application/json', ...corsHeaders },
    });
  }

  const db = env.DB;
  const result = await db.prepare(
    'SELECT * FROM locations WHERE code = ? ORDER BY timestamp DESC LIMIT 1'
  ).bind(code).first();

  if (!result) {
    return new Response(JSON.stringify({ error: 'No location found for this code' }), {
      status: 404,
      headers: { 'Content-Type': 'application/json', ...corsHeaders },
    });
  }

  return new Response(JSON.stringify(result), {
    headers: { 'Content-Type': 'application/json', ...corsHeaders },
  });
}

async function handleGetHistory(request, env, corsHeaders) {
  const url = new URL(request.url);
  const code = url.searchParams.get('code');
  const limit = parseInt(url.searchParams.get('limit') || '50');

  if (!code || code.length !== 6 || !/^\d{6}$/.test(code)) {
    return new Response(JSON.stringify({ error: 'Invalid code' }), {
      status: 400,
      headers: { 'Content-Type': 'application/json', ...corsHeaders },
    });
  }

  const db = env.DB;
  const results = await db.prepare(
    'SELECT * FROM locations WHERE code = ? ORDER BY timestamp DESC LIMIT ?'
  ).bind(code, Math.min(limit, 200)).all();

  return new Response(JSON.stringify(results.results), {
    headers: { 'Content-Type': 'application/json', ...corsHeaders },
  });
}

const WEBSITE_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>TSP - Tracker System Pro</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"><\/script>
<style>
@keyframes neonPulse { 0%,100% { text-shadow: 0 0 4px #00e5ff, 0 0 11px #00e5ff, 0 0 19px #00e5ff; } 50% { text-shadow: 0 0 4px #00e5ff, 0 0 20px #00e5ff, 0 0 40px #00e5ff; } }
@keyframes neonBorder { 0%,100% { box-shadow: 0 0 5px rgba(0,229,255,0.3), inset 0 0 5px rgba(0,229,255,0.1); } 50% { box-shadow: 0 0 15px rgba(0,229,255,0.5), inset 0 0 10px rgba(0,229,255,0.2); } }
@keyframes neonGlow { 0%,100% { box-shadow: 0 0 5px #00e5ff, 0 0 10px rgba(0,229,255,0.3); } 50% { box-shadow: 0 0 10px #00e5ff, 0 0 25px rgba(0,229,255,0.5); } }
@keyframes bgGlow { 0%,100% { opacity: 0.03; } 50% { opacity: 0.07; } }
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #060612; color: #e0e0e0; height: 100vh; display: flex; flex-direction: column; overflow: hidden; }
body::before { content: ''; position: fixed; top: -50%; left: -50%; width: 200%; height: 200%; background: radial-gradient(ellipse at 30% 20%, rgba(0,229,255,0.06) 0%, transparent 50%), radial-gradient(ellipse at 70% 80%, rgba(124,77,255,0.04) 0%, transparent 50%); animation: bgGlow 8s ease-in-out infinite; pointer-events: none; z-index: 0; }
.header { background: linear-gradient(135deg, #0a0e1a, #0d1b2a); padding: 12px 20px; display: flex; align-items: center; gap: 15px; border-bottom: 1px solid rgba(0,229,255,0.2); flex-shrink: 0; position: relative; z-index: 1; }
.header h1 { font-size: 18px; color: #00e5ff; font-weight: 800; letter-spacing: 2px; animation: neonPulse 3s ease-in-out infinite; }
.header .made-by { font-size: 9px; color: rgba(124,77,255,0.7); position: absolute; top: 4px; letter-spacing: 0.5px; }
.header .status { font-size: 12px; color: #556; margin-left: auto; margin-right: 60px; transition: color 0.3s; }
.header .status.online { color: #00ff88; text-shadow: 0 0 6px rgba(0,255,136,0.4); }
.login-screen { flex: 1; display: flex; align-items: center; justify-content: center; padding: 16px; position: relative; z-index: 1; }
.login-box { background: linear-gradient(145deg, #0d1117, #0a0e1a); padding: 40px; border-radius: 20px; text-align: center; box-shadow: 0 0 40px rgba(0,229,255,0.05), 0 8px 32px rgba(0,0,0,0.6); border: 1px solid rgba(0,229,255,0.15); width: 100%; max-width: 420px; animation: neonBorder 4s ease-in-out infinite; }
.login-box h2 { margin-bottom: 6px; font-size: 26px; color: #00e5ff; font-weight: 800; letter-spacing: 1px; text-shadow: 0 0 10px rgba(0,229,255,0.3); }
.login-box .subtitle { color: rgba(124,77,255,0.7); font-size: 11px; margin-bottom: 8px; letter-spacing: 2px; font-weight: 600; }
.login-box p { color: #667; margin-bottom: 24px; font-size: 14px; }
.code-input { display: flex; gap: 8px; justify-content: center; margin-bottom: 24px; }
.code-input input { width: 50px; height: 60px; text-align: center; font-size: 28px; font-weight: bold; background: #080c14; border: 1.5px solid rgba(0,229,255,0.2); border-radius: 12px; color: #00e5ff; outline: none; transition: all 0.3s; }
.code-input input:focus { border-color: #00e5ff; box-shadow: 0 0 15px rgba(0,229,255,0.4), inset 0 0 10px rgba(0,229,255,0.1); animation: neonGlow 2s ease-in-out infinite; }
.connect-btn { width: 100%; padding: 14px; background: linear-gradient(135deg, #00e5ff, #7c4dff); border: none; border-radius: 12px; color: #060612; font-size: 16px; font-weight: 700; cursor: pointer; transition: all 0.3s; letter-spacing: 1px; text-transform: uppercase; }
.connect-btn:hover { transform: translateY(-2px); box-shadow: 0 0 20px rgba(0,229,255,0.4), 0 4px 20px rgba(124,77,255,0.3); }
.connect-btn:disabled { opacity: 0.3; cursor: not-allowed; transform: none; box-shadow: none; }
.map-container { flex: 1; position: relative; display: none; z-index: 1; }
#map { width: 100%; height: 100%; }
.info-panel { position: absolute; bottom: 20px; left: 20px; right: 20px; background: rgba(6,6,18,0.92); backdrop-filter: blur(12px); padding: 14px 18px; border-radius: 14px; border: 1px solid rgba(0,229,255,0.15); z-index: 1000; display: none; }
.info-row { display: flex; justify-content: space-between; padding: 5px 0; font-size: 14px; gap: 8px; }
.info-row .label { color: #556; flex-shrink: 0; }
.info-row .value { font-weight: 600; text-align: end; word-break: break-all; color: #c0c0c0; }
.info-row .value.stale { color: #ff9100; text-shadow: 0 0 6px rgba(255,145,0,0.3); }
.info-row .value.old { color: #ff1744; text-shadow: 0 0 6px rgba(255,23,68,0.3); }
.top-bar { position: absolute; top: 10px; left: 10px; right: 10px; z-index: 1000; display: none; }
.top-bar .refresh-btn { background: rgba(6,6,18,0.92); border: 1px solid rgba(0,229,255,0.3); color: #00e5ff; padding: 10px 16px; border-radius: 10px; font-size: 14px; cursor: pointer; font-weight: 600; transition: all 0.3s; }
.top-bar .refresh-btn:hover { box-shadow: 0 0 12px rgba(0,229,255,0.3); }
.top-bar .disconnect-btn { position: absolute; right: 10px; background: rgba(6,6,18,0.92); border: 1px solid rgba(255,23,68,0.3); color: #ff5252; padding: 10px 16px; border-radius: 10px; font-size: 14px; cursor: pointer; transition: all 0.3s; }
.top-bar .disconnect-btn:hover { box-shadow: 0 0 12px rgba(255,23,68,0.3); }
@media (max-width: 600px) {
  .code-input input { width: 44px; height: 54px; font-size: 24px; }
  .login-box { padding: 24px; }
  .info-panel { bottom: 10px; left: 10px; right: 10px; }
}
</style>
</head>
<body>
<div class="header">
  <span class="made-by">MADE BY OMR</span>
  <h1>TSP</h1>
  <div class="status" id="status">Not connected</div>
</div>
<div class="login-screen" id="loginScreen">
  <div class="login-box">
    <h2>Tracker ID</h2>
    <div class="subtitle">TRACKER SYSTEM PRO</div>
    <p>Enter the 6-digit Tracker ID code</p>
    <div class="code-input" id="codeInput">
      <input type="text" maxlength="1" inputmode="numeric" autofocus>
      <input type="text" maxlength="1" inputmode="numeric">
      <input type="text" maxlength="1" inputmode="numeric">
      <input type="text" maxlength="1" inputmode="numeric">
      <input type="text" maxlength="1" inputmode="numeric">
      <input type="text" maxlength="1" inputmode="numeric">
    </div>
    <button class="connect-btn" id="connectBtn" disabled>Connect</button>
  </div>
</div>
<div class="map-container" id="mapContainer">
  <div class="top-bar" id="topBar">
    <button class="refresh-btn" id="refreshBtn">Refresh</button>
    <button class="disconnect-btn" id="disconnectBtn">Disconnect</button>
  </div>
  <div id="map"></div>
  <div class="info-panel" id="infoPanel">
    <div class="info-row"><span class="label">Last update:</span><span class="value" id="lastUpdate">-</span></div>
    <div class="info-row"><span class="label">Accuracy:</span><span class="value" id="accuracy">-</span></div>
    <div class="info-row"><span class="label">Battery:</span><span class="value" id="battery">-</span></div>
    <div class="info-row"><span class="label">Position:</span><span class="value" id="position">-</span></div>
  </div>
</div>
<script>
let map, marker, trail, currentCode, refreshInterval;
const inputs = document.querySelectorAll('.code-input input');
const connectBtn = document.getElementById('connectBtn');
const loginScreen = document.getElementById('loginScreen');
const mapContainer = document.getElementById('mapContainer');
const topBar = document.getElementById('topBar');
const infoPanel = document.getElementById('infoPanel');

inputs.forEach((input, i) => {
  input.addEventListener('input', (e) => {
    if (e.target.value && i < 5) inputs[i+1].focus();
    updateConnectBtn();
  });
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Backspace' && !e.target.value && i > 0) inputs[i-1].focus();
    if (e.key === 'Enter') connectBtn.click();
  });
  input.addEventListener('paste', (e) => {
    e.preventDefault();
    const paste = (e.clipboardData || window.clipboardData).getData('text').replace(/\\D/g, '').slice(0, 6);
    paste.split('').forEach((char, j) => { if (inputs[j]) inputs[j].value = char; });
    if (paste.length > 0) inputs[Math.min(paste.length, 5)].focus();
    updateConnectBtn();
  });
});

function updateConnectBtn() {
  const code = getCode();
  connectBtn.disabled = code.length !== 6;
}

function getCode() {
  return Array.from(inputs).map(i => i.value).join('');
}

connectBtn.addEventListener('click', () => {
  currentCode = getCode();
  if (currentCode.length !== 6) return;
  loginScreen.style.display = 'none';
  mapContainer.style.display = 'flex';
  topBar.style.display = 'block';
  initMap();
  fetchLocation();
  refreshInterval = setInterval(fetchLocation, 1000);
  document.getElementById('status').textContent = 'Connected: ' + currentCode;
  document.getElementById('status').className = 'status online';
});

document.getElementById('disconnectBtn').addEventListener('click', () => {
  clearInterval(refreshInterval);
  currentCode = null;
  loginScreen.style.display = 'flex';
  mapContainer.style.display = 'none';
  topBar.style.display = 'none';
  infoPanel.style.display = 'none';
  inputs.forEach(i => i.value = '');
  inputs[0].focus();
  document.getElementById('status').textContent = 'Not connected';
  document.getElementById('status').className = 'status';
  connectBtn.disabled = true;
});

document.getElementById('refreshBtn').addEventListener('click', fetchLocation);

function initMap() {
  if (map) return;
  map = L.map('map', { zoomControl: false }).setView([0, 0], 2);
  L.control.zoom({ position: 'topright' }).addTo(map);
  L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
    attribution: '&copy; OSM &copy; CartoDB',
    maxZoom: 19
  }).addTo(map);
  trail = L.polyline([], { color: '#00e5ff', weight: 3, opacity: 0.8 }).addTo(map);
}

async function fetchLocation() {
  if (!currentCode) return;
  try {
    const res = await fetch('/api/get-location?code=' + currentCode);
    if (!res.ok) return;
    const data = await res.json();
    updateMap(data);
    document.getElementById('status').textContent = 'Connected: ' + currentCode + ' | Live';
  } catch (e) {
    document.getElementById('status').textContent = 'Connection error';
  }
}

function updateMap(data) {
  const lat = data.latitude;
  const lng = data.longitude;
  const pos = [lat, lng];

  if (marker) {
    marker.setLatLng(pos);
  } else {
    marker = L.circleMarker(pos, { radius: 10, fillColor: '#00e5ff', color: '#fff', weight: 2, fillOpacity: 0.9 }).addTo(map);
  }

  trail.addLatLng(pos);
  map.setView(pos, 16);

  infoPanel.style.display = 'block';
  const ts = new Date(data.timestamp);
  const now = new Date();
  const ageSec = Math.floor((now - ts) / 1000);
  let ageText = ageSec < 5 ? 'Just now' : ageSec < 60 ? ageSec + 's ago' : Math.floor(ageSec/60) + 'm ' + (ageSec%60) + 's ago';
  document.getElementById('lastUpdate').textContent = ageText;
  document.getElementById('lastUpdate').className = 'value' + (ageSec > 30 ? ' stale' : ageSec > 120 ? ' old' : '');
  document.getElementById('accuracy').textContent = data.accuracy ? data.accuracy.toFixed(0) + 'm' : 'N/A';
  document.getElementById('battery').textContent = data.battery != null ? data.battery + '%' : 'N/A';
  document.getElementById('position').textContent = lat.toFixed(5) + ', ' + lng.toFixed(5);
}
<\/script>
</body>
</html>`;
