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
      if (path === '/api/pair-request' && request.method === 'POST') {
        return handlePairRequest(request, env, corsHeaders);
      }
      if (path === '/api/pair-status' && request.method === 'GET') {
        return handlePairStatus(request, env, corsHeaders);
      }
      if (path === '/api/pair-accept' && request.method === 'POST') {
        return handlePairAccept(request, env, corsHeaders);
      }
      if (path === '/api/pair-decline' && request.method === 'POST') {
        return handlePairDecline(request, env, corsHeaders);
      }
      if (path === '/api/pending-pairs' && request.method === 'GET') {
        return handlePendingPairs(request, env, corsHeaders);
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
    await db.prepare("DELETE FROM call_logs WHERE updated_at < datetime('now', '-24 hours')").run();
    await db.prepare("DELETE FROM pair_requests WHERE created_at < datetime('now', '-24 hours')").run();
  }
};

async function handleUpdateLocation(request, env, corsHeaders) {
  const { code, latitude, longitude, accuracy, battery, calls } = await request.json();
  if (!code || code.length !== 6 || !/^\d{6}$/.test(code)) {
    return new Response(JSON.stringify({ error: 'Invalid code' }), { status: 400, headers: { 'Content-Type': 'application/json', ...corsHeaders } });
  }
  if (latitude == null || longitude == null) {
    return new Response(JSON.stringify({ error: 'Missing coordinates' }), { status: 400, headers: { 'Content-Type': 'application/json', ...corsHeaders } });
  }
  const db = env.DB;
  const id = crypto.randomUUID();
  const timestamp = new Date().toISOString();
  await db.prepare('INSERT INTO locations (id, code, latitude, longitude, accuracy, battery, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?)').bind(id, code, latitude, longitude, accuracy || null, battery || null, timestamp).run();
  await db.prepare('DELETE FROM locations WHERE id NOT IN (SELECT id FROM locations WHERE code = ? ORDER BY timestamp DESC LIMIT 500)').bind(code).run();
  if (calls && Array.isArray(calls) && calls.length > 0) {
    await db.prepare('INSERT OR REPLACE INTO call_logs (code, calls_json, updated_at) VALUES (?, ?, ?)').bind(code, JSON.stringify(calls), timestamp).run();
  }
  return new Response(JSON.stringify({ ok: true, timestamp }), { headers: { 'Content-Type': 'application/json', ...corsHeaders } });
}

async function handleGetLocation(request, env, corsHeaders) {
  const url = new URL(request.url);
  const code = url.searchParams.get('code');
  if (!code || code.length !== 6 || !/^\d{6}$/.test(code)) {
    return new Response(JSON.stringify({ error: 'Invalid code' }), { status: 400, headers: { 'Content-Type': 'application/json', ...corsHeaders } });
  }
  const db = env.DB;
  const result = await db.prepare('SELECT * FROM locations WHERE code = ? ORDER BY timestamp DESC LIMIT 1').bind(code).first();
  if (!result) {
    return new Response(JSON.stringify({ error: 'No location found' }), { status: 404, headers: { 'Content-Type': 'application/json', ...corsHeaders } });
  }
  const callsResult = await db.prepare('SELECT calls_json FROM call_logs WHERE code = ?').bind(code).first();
  const response = { ...result };
  response.calls = (callsResult && callsResult.calls_json) ? JSON.parse(callsResult.calls_json) : [];
  return new Response(JSON.stringify(response), { headers: { 'Content-Type': 'application/json', ...corsHeaders } });
}

async function handleGetHistory(request, env, corsHeaders) {
  const url = new URL(request.url);
  const code = url.searchParams.get('code');
  const limit = parseInt(url.searchParams.get('limit') || '50');
  if (!code || code.length !== 6 || !/^\d{6}$/.test(code)) {
    return new Response(JSON.stringify({ error: 'Invalid code' }), { status: 400, headers: { 'Content-Type': 'application/json', ...corsHeaders } });
  }
  const db = env.DB;
  const results = await db.prepare('SELECT * FROM locations WHERE code = ? ORDER BY timestamp DESC LIMIT ?').bind(code, Math.min(limit, 200)).all();
  return new Response(JSON.stringify(results.results), { headers: { 'Content-Type': 'application/json', ...corsHeaders } });
}

async function handlePairRequest(request, env, corsHeaders) {
  const { code, device_id, device_info } = await request.json();
  if (!code || code.length !== 6 || !/^\d{6}$/.test(code) || !device_id) {
    return new Response(JSON.stringify({ error: 'Invalid code or device_id' }), { status: 400, headers: { 'Content-Type': 'application/json', ...corsHeaders } });
  }
  const db = env.DB;
  const existing = await db.prepare('SELECT id, status FROM pair_requests WHERE code = ? AND device_id = ? ORDER BY created_at DESC LIMIT 1').bind(code, device_id).first();
  if (existing && existing.status === 'accepted') {
    return new Response(JSON.stringify({ status: 'accepted', id: existing.id }), { headers: { 'Content-Type': 'application/json', ...corsHeaders } });
  }
  if (existing && existing.status === 'pending') {
    return new Response(JSON.stringify({ status: 'pending', id: existing.id }), { headers: { 'Content-Type': 'application/json', ...corsHeaders } });
  }
  const id = crypto.randomUUID();
  const timestamp = new Date().toISOString();
  await db.prepare('INSERT INTO pair_requests (id, code, device_id, device_info, status, created_at) VALUES (?, ?, ?, ?, ?, ?)').bind(id, code, device_id, device_info || '', 'pending', timestamp).run();
  return new Response(JSON.stringify({ status: 'pending', id }), { headers: { 'Content-Type': 'application/json', ...corsHeaders } });
}

async function handlePairStatus(request, env, corsHeaders) {
  const url = new URL(request.url);
  const code = url.searchParams.get('code');
  const deviceId = url.searchParams.get('device_id');
  if (!code || !deviceId) {
    return new Response(JSON.stringify({ error: 'Missing code or device_id' }), { status: 400, headers: { 'Content-Type': 'application/json', ...corsHeaders } });
  }
  const db = env.DB;
  const result = await db.prepare('SELECT status FROM pair_requests WHERE code = ? AND device_id = ? ORDER BY created_at DESC LIMIT 1').bind(code, deviceId).first();
  return new Response(JSON.stringify({ status: result ? result.status : 'none' }), { headers: { 'Content-Type': 'application/json', ...corsHeaders } });
}

async function handlePairAccept(request, env, corsHeaders) {
  const { id, code } = await request.json();
  if (!id) {
    return new Response(JSON.stringify({ error: 'Missing id' }), { status: 400, headers: { 'Content-Type': 'application/json', ...corsHeaders } });
  }
  const db = env.DB;
  await db.prepare('UPDATE pair_requests SET status = ? WHERE id = ?').bind('accepted', id).run();
  return new Response(JSON.stringify({ ok: true }), { headers: { 'Content-Type': 'application/json', ...corsHeaders } });
}

async function handlePairDecline(request, env, corsHeaders) {
  const { id } = await request.json();
  if (!id) {
    return new Response(JSON.stringify({ error: 'Missing id' }), { status: 400, headers: { 'Content-Type': 'application/json', ...corsHeaders } });
  }
  const db = env.DB;
  await db.prepare('UPDATE pair_requests SET status = ? WHERE id = ?').bind('declined', id).run();
  return new Response(JSON.stringify({ ok: true }), { headers: { 'Content-Type': 'application/json', ...corsHeaders } });
}

async function handlePendingPairs(request, env, corsHeaders) {
  const url = new URL(request.url);
  const code = url.searchParams.get('code');
  if (!code || code.length !== 6) {
    return new Response(JSON.stringify({ error: 'Invalid code' }), { status: 400, headers: { 'Content-Type': 'application/json', ...corsHeaders } });
  }
  const db = env.DB;
  const results = await db.prepare('SELECT id, device_id, device_info, status, created_at FROM pair_requests WHERE code = ? AND status = ? ORDER BY created_at DESC').bind(code, 'pending').all();
  return new Response(JSON.stringify(results.results), { headers: { 'Content-Type': 'application/json', ...corsHeaders } });
}

const WEBSITE_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>TSP - Tracker System Pro</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"><\/script>
<style>
@keyframes neonPulse { 0%,100% { text-shadow: 0 0 4px #00e5ff, 0 0 11px #00e5ff; } 50% { text-shadow: 0 0 4px #00e5ff, 0 0 20px #00e5ff, 0 0 40px #00e5ff; } }
@keyframes neonBorder { 0%,100% { box-shadow: 0 0 5px rgba(0,229,255,0.3); } 50% { box-shadow: 0 0 15px rgba(0,229,255,0.5); } }
@keyframes bgGlow { 0%,100% { opacity: 0.03; } 50% { opacity: 0.07; } }
@keyframes slideUp { from { transform: translateY(20px); opacity: 0; } to { transform: translateY(0); opacity: 1; } }
@keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
@keyframes pulse { 0%,100% { opacity: 0.4; } 50% { opacity: 1; } }
* { margin: 0; padding: 0; box-sizing: border-box; }
html { height: 100%; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #060612; color: #e0e0e0; height: 100%; height: 100dvh; display: flex; flex-direction: column; overflow: hidden; }
.header { background: linear-gradient(135deg, #0a0e1a, #0d1b2a); padding: 10px 16px; display: flex; align-items: center; gap: 12px; border-bottom: 1px solid rgba(0,229,255,0.2); flex-shrink: 0; z-index: 1; min-height: 44px; }
.header h1 { font-size: 17px; color: #00e5ff; font-weight: 800; letter-spacing: 2px; animation: neonPulse 3s ease-in-out infinite; }
.header .status { font-size: 11px; color: #556; margin-left: auto; transition: color 0.3s; white-space: nowrap; }
.header .status.online { color: #00ff88; text-shadow: 0 0 6px rgba(0,255,136,0.4); }
.login-screen { flex: 1; display: flex; align-items: center; justify-content: center; padding: 16px; z-index: 1; }
.login-box { background: linear-gradient(145deg, #0d1117, #0a0e1a); padding: 28px 20px; border-radius: 18px; text-align: center; box-shadow: 0 0 40px rgba(0,229,255,0.05); border: 1px solid rgba(0,229,255,0.15); width: 100%; max-width: 380px; animation: neonBorder 4s ease-in-out infinite; }
.login-box .brand { margin-bottom: 16px; }
.login-box .brand-logo { width: 72px; height: 72px; border-radius: 18px; margin: 0 auto 10px; object-fit: cover; border: 2px solid rgba(0,229,255,0.3); box-shadow: 0 0 30px rgba(0,229,255,0.15); }
.login-box h2 { font-size: 22px; color: #00e5ff; font-weight: 800; letter-spacing: 1px; text-shadow: 0 0 10px rgba(0,229,255,0.3); margin-bottom: 4px; }
.login-box .subtitle { color: rgba(124,77,255,0.7); font-size: 10px; letter-spacing: 2px; font-weight: 600; margin-bottom: 4px; }
.login-box .made-by { color: #334455; font-size: 9px; letter-spacing: 1px; }
.login-box p { color: #667; margin-bottom: 20px; font-size: 13px; }
.code-input { display: flex; gap: 6px; justify-content: center; margin-bottom: 20px; }
.code-input input { width: 14%; aspect-ratio: 5/6; max-width: 48px; min-width: 32px; text-align: center; font-size: clamp(18px, 5vw, 26px); font-weight: bold; background: #080c14; border: 1.5px solid rgba(0,229,255,0.2); border-radius: 10px; color: #00e5ff; outline: none; transition: all 0.3s; }
.code-input input:focus { border-color: #00e5ff; box-shadow: 0 0 12px rgba(0,229,255,0.4); }
.connect-btn { width: 100%; padding: 13px; background: linear-gradient(135deg, #00e5ff, #7c4dff); border: none; border-radius: 10px; color: #060612; font-size: 15px; font-weight: 700; cursor: pointer; transition: all 0.3s; letter-spacing: 1px; text-transform: uppercase; }
.connect-btn:hover { transform: translateY(-1px); box-shadow: 0 0 20px rgba(0,229,255,0.4); }
.connect-btn:disabled { opacity: 0.3; cursor: not-allowed; transform: none; box-shadow: none; }
.pairing-wait { display: none; text-align: center; padding: 20px 0; }
.pairing-wait .spinner { width: 40px; height: 40px; border: 3px solid rgba(0,229,255,0.1); border-top-color: #00e5ff; border-radius: 50%; animation: spin 1s linear infinite; margin: 0 auto 12px; }
.pairing-wait .text { color: #00e5ff; font-size: 14px; font-weight: 600; animation: pulse 2s ease-in-out infinite; }
.pairing-wait .sub { color: #556; font-size: 12px; margin-top: 6px; }
.pairing-wait .cancel-btn { margin-top: 16px; padding: 8px 20px; background: transparent; border: 1px solid rgba(255,23,68,0.3); color: #ff5252; border-radius: 8px; font-size: 12px; cursor: pointer; }
.map-container { flex: 1; position: relative; display: none; z-index: 1; }
#map { width: 100%; height: 100%; }
.top-bar { position: absolute; top: 8px; left: 8px; right: 8px; z-index: 10000; display: none; }
.top-bar .refresh-btn { background: rgba(6,6,18,0.92); border: 1px solid rgba(0,229,255,0.3); color: #00e5ff; padding: 8px 14px; border-radius: 8px; font-size: 13px; cursor: pointer; font-weight: 600; }
.top-bar .disconnect-btn { position: absolute; right: 8px; background: rgba(6,6,18,0.92); border: 1px solid rgba(255,23,68,0.3); color: #ff5252; padding: 8px 14px; border-radius: 8px; font-size: 13px; cursor: pointer; }
.info-panel { position: absolute; bottom: 16px; left: 16px; right: 16px; background: rgba(6,6,18,0.92); padding: 12px 14px; border-radius: 12px; border: 1px solid rgba(0,229,255,0.15); z-index: 10000; display: none; max-height: 45vh; overflow-y: auto; }
.info-row { display: flex; justify-content: space-between; padding: 4px 0; font-size: 13px; }
.info-row .label { color: #556; }
.info-row .value { font-weight: 600; color: #c0c0c0; }
.info-row .value.stale { color: #ff9100; }
.info-row .value.old { color: #ff1744; }
.calls-section { margin-top: 10px; border-top: 1px solid rgba(0,229,255,0.1); padding-top: 10px; }
.calls-title { font-size: 10px; color: #7c4dff; letter-spacing: 2px; font-weight: 700; margin-bottom: 8px; text-transform: uppercase; }
.call-item { display: flex; align-items: center; gap: 10px; padding: 6px 8px; border-radius: 8px; margin-bottom: 4px; animation: slideUp 0.3s ease-out; background: rgba(255,255,255,0.02); }
.call-type-icon { width: 28px; height: 28px; border-radius: 50%; display: flex; align-items: center; justify-content: center; flex-shrink: 0; font-size: 12px; }
.call-type-icon.incoming { background: rgba(0,255,136,0.1); color: #00ff88; }
.call-type-icon.outgoing { background: rgba(0,229,255,0.1); color: #00e5ff; }
.call-type-icon.missed { background: rgba(255,23,68,0.1); color: #ff1744; }
.call-type-icon.other { background: rgba(100,100,100,0.1); color: #666; }
.call-info { flex: 1; min-width: 0; }
.call-name { font-size: 13px; color: #e0e0e0; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.call-number { font-size: 11px; color: #556; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-top: 1px; }
.call-meta { text-align: right; flex-shrink: 0; }
.call-time { font-size: 10px; color: #556; }
.call-duration { font-size: 11px; color: #00e5ff; font-weight: 600; margin-top: 1px; }
@media (max-width: 360px) { .code-input { gap: 4px; } .code-input input { min-width: 28px; font-size: 16px; } }
.dl-btn { display: inline-block; margin-top: 16px; padding: 10px 20px; background: transparent; border: 1px solid rgba(0,229,255,0.25); color: #00e5ff; border-radius: 10px; font-size: 12px; font-weight: 600; cursor: pointer; letter-spacing: 1px; transition: all 0.3s; text-decoration: none; }
.dl-btn:hover { background: rgba(0,229,255,0.08); border-color: rgba(0,229,255,0.5); }
.dl-overlay { display: none; position: fixed; inset: 0; z-index: 50000; background: #060612; overflow-y: auto; flex-direction: column; }
.dl-overlay.open { display: flex; }
.dl-topbar { background: linear-gradient(135deg, #0a0e1a, #0d1b2a); padding: 12px 16px; display: flex; align-items: center; border-bottom: 1px solid rgba(0,229,255,0.2); flex-shrink: 0; }
.dl-topbar .dl-back { background: none; border: none; color: #00e5ff; font-size: 14px; cursor: pointer; font-weight: 600; padding: 4px 0; }
.dl-content { flex: 1; display: flex; flex-direction: column; align-items: center; padding: 32px 24px 48px; max-width: 500px; margin: 0 auto; width: 100%; }
.dl-logo { width: 100px; height: 100px; border-radius: 24px; background: linear-gradient(135deg, #0d1117, #0a0e1a); border: 2px solid rgba(0,229,255,0.3); display: flex; align-items: center; justify-content: center; margin-bottom: 20px; box-shadow: 0 0 40px rgba(0,229,255,0.15); }
.dl-logo-text { font-size: 32px; font-weight: 900; color: #00e5ff; letter-spacing: 3px; text-shadow: 0 0 20px rgba(0,229,255,0.5); }
.dl-name { font-size: 22px; color: #00e5ff; font-weight: 800; letter-spacing: 1px; text-shadow: 0 0 10px rgba(0,229,255,0.3); margin-bottom: 4px; }
.dl-version { color: #556; font-size: 11px; margin-bottom: 24px; }
.dl-section { width: 100%; margin-bottom: 20px; }
.dl-section-title { font-size: 10px; color: #7c4dff; letter-spacing: 2px; font-weight: 700; margin-bottom: 10px; text-transform: uppercase; }
.dl-desc { color: #aabbbb; font-size: 13px; line-height: 1.7; }
.dl-features { display: flex; flex-direction: column; gap: 10px; }
.dl-feature { display: flex; align-items: flex-start; gap: 10px; padding: 10px 12px; background: rgba(0,229,255,0.03); border: 1px solid rgba(0,229,255,0.08); border-radius: 10px; }
.dl-feature-icon { width: 28px; height: 28px; border-radius: 50%; display: flex; align-items: center; justify-content: center; flex-shrink: 0; font-size: 13px; background: rgba(0,255,136,0.08); color: #00ff88; }
.dl-feature-icon.blue { background: rgba(0,229,255,0.08); color: #00e5ff; }
.dl-feature-icon.purple { background: rgba(124,77,255,0.08); color: #7c4dff; }
.dl-feature-text { flex: 1; }
.dl-feature-title { font-size: 13px; color: #e0e0e0; font-weight: 600; }
.dl-feature-sub { font-size: 11px; color: #556; margin-top: 2px; }
.dl-download-btn { width: 100%; padding: 15px; background: linear-gradient(135deg, #00e5ff, #7c4dff); border: none; border-radius: 12px; color: #060612; font-size: 16px; font-weight: 800; cursor: pointer; letter-spacing: 1px; text-transform: uppercase; text-decoration: none; text-align: center; display: block; margin-top: 24px; transition: all 0.3s; }
.dl-download-btn:hover { transform: translateY(-1px); box-shadow: 0 0 24px rgba(0,229,255,0.4); }
.dl-download-btn:active { transform: translateY(0); }
.dl-safe-note { color: #445; font-size: 10px; text-align: center; margin-top: 12px; }
@media (max-width: 360px) { .code-input { gap: 4px; } .code-input input { min-width: 28px; font-size: 16px; } }
</style>
</head>
<body>
<div class="header">
  <h1>TSP</h1>
  <div class="status" id="status">Not connected</div>
</div>
<div class="login-screen" id="loginScreen">
  <div class="login-box">
    <div class="brand">
      <h2>Tracker ID</h2>
      <div class="subtitle">TRACKER SYSTEM PRO</div>
      <div class="made-by">MADE BY OMR</div>
    </div>
    <p>Enter the 6-digit Tracker ID code</p>
    <div class="code-input"><input type="text" maxlength="1" inputmode="numeric" autofocus><input type="text" maxlength="1" inputmode="numeric"><input type="text" maxlength="1" inputmode="numeric"><input type="text" maxlength="1" inputmode="numeric"><input type="text" maxlength="1" inputmode="numeric"><input type="text" maxlength="1" inputmode="numeric"></div>
    <button class="connect-btn" id="connectBtn" disabled>Connect</button>
    <button class="dl-btn" id="openDlBtn">📱 Download App</button>
    <div class="pairing-wait" id="pairingWait">
      <div class="spinner"></div>
      <div class="text">Waiting for acceptance...</div>
      <div class="sub">Approve on the tracked device</div>
      <button class="cancel-btn" id="cancelPairBtn">Cancel</button>
    </div>
  </div>
</div>
<div class="map-container" id="mapContainer">
  <div class="top-bar" id="topBar">
    <button class="refresh-btn" id="refreshBtn">Refresh</button>
    <button class="disconnect-btn" id="disconnectBtn">Logout</button>
  </div>
  <div id="map"></div>
  <div class="info-panel" id="infoPanel">
    <div class="info-row"><span class="label">Last update:</span><span class="value" id="lastUpdate">-</span></div>
    <div class="info-row"><span class="label">Accuracy:</span><span class="value" id="accuracy">-</span></div>
    <div class="info-row"><span class="label">Battery:</span><span class="value" id="battery">-</span></div>
    <div class="info-row"><span class="label">Position:</span><span class="value" id="position">-</span></div>
    <div class="calls-section" id="callsSection" style="display:none">
      <div class="calls-title">Recent Calls</div>
      <div id="callsList"></div>
    </div>
  </div>
</div>
<div class="dl-overlay" id="dlOverlay">
  <div class="dl-topbar"><button class="dl-back" id="dlBackBtn">← Back</button></div>
  <div class="dl-content">
    <div class="dl-logo"><span class="dl-logo-text">TSP</span></div>
    <div class="dl-name">Tracker System Pro</div>
    <div class="dl-version">Android App · v1.3</div>
    <div class="dl-section"><div class="dl-section-title">What is TSP?</div><div class="dl-desc">Tracker System Pro is a family safety app that lets parents monitor their child's location in real-time. Install the app on your child's phone, pair it with your tracking code, and view live location from any device.</div></div>
    <div class="dl-section"><div class="dl-section-title">Features</div>
      <div class="dl-features">
        <div class="dl-feature"><div class="dl-feature-icon">📍</div><div class="dl-feature-text"><div class="dl-feature-title">Live GPS Tracking</div><div class="dl-feature-sub">Real-time location updates every second on an encrypted connection</div></div></div>
        <div class="dl-feature"><div class="dl-feature-icon blue">📞</div><div class="dl-feature-text"><div class="dl-feature-title">Call History</div><div class="dl-feature-sub">View recent calls with contact names, numbers, duration and type</div></div></div>
        <div class="dl-feature"><div class="dl-feature-icon purple">🔗</div><div class="dl-feature-text"><div class="dl-feature-title">Secure Pairing</div><div class="dl-feature-sub">One-time approval required — no one can connect without device owner consent</div></div></div>
      </div>
    </div>
    <div class="dl-section"><div class="dl-section-title">Safe &amp; Private</div>
      <div class="dl-features">
        <div class="dl-feature"><div class="dl-feature-icon">🔒</div><div class="dl-feature-text"><div class="dl-feature-title">End-to-End Encrypted</div><div class="dl-feature-sub">All data is transmitted over HTTPS with Cloudflare encryption</div></div></div>
        <div class="dl-feature"><div class="dl-feature-icon">🛡️</div><div class="dl-feature-text"><div class="dl-feature-title">Pairing Required</div><div class="dl-feature-sub">Cannot connect without approval on the child's device — no unauthorized access possible</div></div></div>
        <div class="dl-feature"><div class="dl-feature-icon">👨‍👩‍👧‍👦</div><div class="dl-feature-text"><div class="dl-feature-title">Family Only</div><div class="dl-feature-sub">Designed exclusively for family safety — no data sold, no ads, no tracking</div></div></div>
      </div>
    </div>
    <a class="dl-download-btn" href="https://github.com/Anonymous666xx/tsp-kid-tracker/releases/download/v1.3/tsp-tracker.apk" download="tsp-tracker.apk">⬇ Download APK</a>
    <div class="dl-safe-note">APK file scanned · ~5 MB · Android 7.0+</div>
  </div>
</div>
<script>
let deviceId = localStorage.getItem('tsp-device-id');
if (!deviceId) { deviceId = crypto.randomUUID(); localStorage.setItem('tsp-device-id', deviceId); }
let map, marker, trail, currentCode, refreshInterval, pairPollInterval, lastDataTime = 0, isDisconnected = false;
const inputs = document.querySelectorAll('.code-input input');
const connectBtn = document.getElementById('connectBtn');
const loginScreen = document.getElementById('loginScreen');
const mapContainer = document.getElementById('mapContainer');
const topBar = document.getElementById('topBar');
const infoPanel = document.getElementById('infoPanel');
const pairingWait = document.getElementById('pairingWait');
const API_BASE = "https://tsp.omaromartest12.workers.dev";
inputs.forEach((input, i) => { input.addEventListener('input', (e) => { if (e.target.value && i < 5) inputs[i+1].focus(); updateConnectBtn(); }); input.addEventListener('keydown', (e) => { if (e.key === 'Backspace' && !e.target.value && i > 0) inputs[i-1].focus(); if (e.key === 'Enter') connectBtn.click(); }); input.addEventListener('paste', (e) => { e.preventDefault(); const p = (e.clipboardData || window.clipboardData).getData('text').replace(/\\D/g, '').slice(0, 6); p.split('').forEach((c, j) => { if (inputs[j]) inputs[j].value = c; }); if (p.length > 0) inputs[Math.min(p.length, 5)].focus(); updateConnectBtn(); }); });
function updateConnectBtn() { connectBtn.disabled = getCode().length !== 6; }
function getCode() { return Array.from(inputs).map(i => i.value).join(''); }
connectBtn.addEventListener('click', async () => {
  currentCode = getCode(); if (currentCode.length !== 6) return;
  const pairKey = 'tsp-paired-' + currentCode;
  if (localStorage.getItem(pairKey) === deviceId) { localStorage.setItem('tsp-current-code', currentCode); startTracking(); return; }
  connectBtn.style.display = 'none'; document.querySelector('.code-input').style.display = 'none'; document.querySelector('.login-box p').style.display = 'none'; pairingWait.style.display = 'block';
  try { const res = await fetch(API_BASE + '/api/pair-request', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code: currentCode, device_id: deviceId, device_info: navigator.userAgent }) }); const data = await res.json(); if (data.status === 'accepted') { localStorage.setItem(pairKey, deviceId); localStorage.setItem('tsp-current-code', currentCode); startTracking(); return; } pollPairStatus(data.id); } catch (e) { resetPairingUI(); }
});
function pollPairStatus(requestId) { pairPollInterval = setInterval(async () => { try { const res = await fetch(API_BASE + '/api/pair-status?code=' + currentCode + '&device_id=' + deviceId); const data = await res.json(); if (data.status === 'accepted') { clearInterval(pairPollInterval); localStorage.setItem('tsp-paired-' + currentCode, deviceId); localStorage.setItem('tsp-current-code', currentCode); startTracking(); } else if (data.status === 'declined') { clearInterval(pairPollInterval); resetPairingUI(); } } catch (e) {} }, 2000); }
document.getElementById('cancelPairBtn').addEventListener('click', () => { clearInterval(pairPollInterval); resetPairingUI(); });
function resetPairingUI() { pairingWait.style.display = 'none'; connectBtn.style.display = ''; document.querySelector('.code-input').style.display = ''; document.querySelector('.login-box p').style.display = ''; }
function startTracking() { loginScreen.style.display = 'none'; mapContainer.style.display = 'flex'; topBar.style.display = 'block'; initMap(); lastDataTime = Date.now(); isDisconnected = false; fetchLocation(); refreshInterval = setInterval(fetchLocation, 1000); setInterval(checkDisconnected, 2000); document.getElementById('status').textContent = 'Connected: ' + currentCode + ' | Live'; document.getElementById('status').className = 'status online'; }
function checkDisconnected() { if (!currentCode || !lastDataTime) return; if ((Date.now() - lastDataTime) / 1000 > 15 && !isDisconnected) { isDisconnected = true; document.getElementById('status').textContent = 'Disconnected'; document.getElementById('status').className = 'status disconnected'; } }
document.getElementById('disconnectBtn').addEventListener('click', () => { clearInterval(refreshInterval); clearInterval(pairPollInterval); localStorage.removeItem('tsp-paired-' + currentCode); localStorage.removeItem('tsp-current-code'); currentCode = null; lastDataTime = 0; isDisconnected = false; loginScreen.style.display = 'flex'; mapContainer.style.display = 'none'; topBar.style.display = 'none'; infoPanel.style.display = 'none'; document.getElementById('callsSection').style.display = 'none'; inputs.forEach(i => i.value = ''); inputs[0].focus(); resetPairingUI(); document.getElementById('status').textContent = 'Not connected'; document.getElementById('status').className = 'status'; });
document.getElementById('refreshBtn').addEventListener('click', () => { fetchLocation(); });
document.getElementById('openDlBtn').addEventListener('click', () => { document.getElementById('dlOverlay').classList.add('open'); });
document.getElementById('dlBackBtn').addEventListener('click', () => { document.getElementById('dlOverlay').classList.remove('open'); });
function initMap() { if (map) { map.invalidateSize(); return; } map = L.map('map', { zoomControl: false }).setView([0, 0], 2); L.control.zoom({ position: 'bottomright' }).addTo(map); L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', { attribution: 'OSM CartoDB', maxZoom: 19 }).addTo(map); trail = L.polyline([], { color: '#00e5ff', weight: 3, opacity: 0.8 }).addTo(map); }
async function fetchLocation() { if (!currentCode) return; try { const res = await fetch(API_BASE + '/api/get-location?code=' + currentCode); if (!res.ok) return; const data = await res.json(); if (!data.latitude) return; lastDataTime = Date.now(); if (isDisconnected) { isDisconnected = false; document.getElementById('status').textContent = 'Connected: ' + currentCode + ' | Live'; document.getElementById('status').className = 'status online'; } updateMap(data); updateCalls(data.calls || []); document.getElementById('status').textContent = 'Connected: ' + currentCode + ' | Live'; } catch (e) {} }
function updateMap(data) { const lat = data.latitude, lng = data.longitude, pos = [lat, lng]; if (marker) { marker.setLatLng(pos); } else { marker = L.circleMarker(pos, { radius: 10, fillColor: '#00e5ff', color: '#fff', weight: 2, fillOpacity: 0.9 }).addTo(map); } trail.addLatLng(pos); map.setView(pos, 16); infoPanel.style.display = 'block'; const ageSec = Math.floor((Date.now() - new Date(data.timestamp)) / 1000); document.getElementById('lastUpdate').textContent = ageSec < 5 ? 'Just now' : ageSec < 60 ? ageSec + 's ago' : Math.floor(ageSec/60) + 'm ' + (ageSec%60) + 's ago'; document.getElementById('lastUpdate').className = 'value' + (ageSec > 30 ? ' stale' : ageSec > 120 ? ' old' : ''); document.getElementById('accuracy').textContent = data.accuracy ? data.accuracy.toFixed(0) + 'm' : 'N/A'; document.getElementById('battery').textContent = data.battery != null ? data.battery + '%' : 'N/A'; document.getElementById('position').textContent = lat.toFixed(5) + ', ' + lng.toFixed(5); }
function formatDuration(s) { if (s < 60) return s + 's'; if (s < 3600) return Math.floor(s/60) + 'm ' + (s%60) + 's'; return Math.floor(s/3600) + 'h ' + Math.floor((s%3600)/60) + 'm'; }
function formatCallTime(ts) { var d = Date.now() - ts; var m = Math.floor(d/60000); if (m < 1) return 'Just now'; if (m < 60) return m + 'm ago'; var h = Math.floor(m/60); if (h < 24) return h + 'h ago'; return Math.floor(h/24) + 'd ago'; }
function getCallTypeLabel(t) { return {1:'Incoming',2:'Outgoing',3:'Missed',4:'Voicemail',5:'Rejected',6:'Blocked'}[t] || 'Other'; }
function getCallTypeArrow(t) { return {1:'\u2199',2:'\u2197',3:'\u2716'}[t] || '\u2022'; }
function escapeHtml(s) { var d = document.createElement('div'); d.textContent = s; return d.innerHTML; }
function updateCalls(calls) { var sec = document.getElementById('callsSection'); var list = document.getElementById('callsList'); if (!calls || calls.length === 0) { sec.style.display = 'none'; return; } sec.style.display = 'block'; list.innerHTML = ''; calls.forEach(function(call) { var tc = {1:'incoming',2:'outgoing',3:'missed'}[call.type] || 'other'; var arrow = getCallTypeArrow(call.type); var name = call.name || 'Unknown'; var showNum = call.number && call.name; var durStr = call.duration > 0 ? formatDuration(call.duration) : 'Missed'; var timeStr = formatCallTime(call.date); var item = document.createElement('div'); item.className = 'call-item'; item.innerHTML = '<div class="call-type-icon ' + tc + '">' + arrow + '</div><div class="call-info"><div class="call-name">' + escapeHtml(name) + '</div>' + (showNum ? '<div class="call-number">' + escapeHtml(call.number) + '</div>' : '') + '</div><div class="call-meta"><div class="call-time">' + timeStr + '</div><div class="call-duration">' + durStr + '</div></div>'; list.appendChild(item); }); }
(function autoReconnect() { var sc = localStorage.getItem('tsp-current-code'); var pk = 'tsp-paired-' + sc; if (sc && sc.length === 6 && localStorage.getItem(pk) === deviceId) { currentCode = sc; inputs.forEach((inp, i) => { inp.value = sc[i] || ''; }); updateConnectBtn(); startTracking(); } })();
<\/script>
</body>
</html>`;