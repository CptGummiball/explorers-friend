/* The Explorer's Friend — map viewer (vanilla JS, no dependencies) */
"use strict";

(() => {
  const TILE = 512;
  const canvas = document.getElementById("map");
  const ctx = canvas.getContext("2d");
  const worldSelect = document.getElementById("world");
  const coordsEl = document.getElementById("coords");
  const statusEl = document.getElementById("status");
  const titleEl = document.getElementById("title");

  const state = {
    worlds: [],
    world: null,          // {id, slug, zoomLevels, spawnX, spawnZ}
    centerX: 0,
    centerZ: 0,
    scale: 1,             // pixels per block
    players: [],
    playersInterval: 2,
    cursorX: 0,
    cursorZ: 0,
    dpr: Math.max(1, window.devicePixelRatio || 1),
  };

  // --- tile cache ---------------------------------------------------------
  const tiles = new Map(); // url -> {img, ok, seen}
  const MAX_TILES = 400;

  function tileFor(url) {
    let entry = tiles.get(url);
    if (!entry) {
      if (tiles.size > MAX_TILES) {
        // drop least-recently seen entries
        const sorted = [...tiles.entries()].sort((a, b) => a[1].seen - b[1].seen);
        for (let i = 0; i < sorted.length / 4; i++) tiles.delete(sorted[i][0]);
      }
      const img = new Image();
      entry = { img, ok: false, failed: false, seen: 0 };
      img.onload = () => { entry.ok = true; scheduleDraw(); };
      img.onerror = () => { entry.failed = true; };
      img.src = url;
      tiles.set(url, entry);
    }
    entry.seen = performance.now();
    return entry;
  }

  // --- overlay layers (claims, markers, players) --------------------------
  const layerPrefs = (() => {
    try { return JSON.parse(localStorage.getItem("ef.layers") || "{}"); }
    catch (e) { return {}; }
  })();
  const layers = {
    claims: layerPrefs.claims,
    markers: layerPrefs.markers,
    banners: layerPrefs.banners,
    waystones: layerPrefs.waystones,
    players: layerPrefs.players,
    names: layerPrefs.names,
  };
  function saveLayerPrefs() {
    localStorage.setItem("ef.layers", JSON.stringify(layers));
  }

  const overlays = {
    claims: { items: [], etag: null, world: null },
    markers: { items: [], etag: null, world: null },
    waystones: { items: [], etag: null, world: null },
    claimCfg: { fillOpacity: 0.3, borderWidth: 2 },
  };
  const iconImages = new Map();   // icon id / banner hash -> Image
  const headImages = new Map();   // uuid -> Image

  function imageFor(map, key, url) {
    let img = map.get(key);
    if (!img) {
      img = new Image();
      img.src = url;
      img.onload = scheduleDraw;
      map.set(key, img);
    }
    return img;
  }

  async function fetchOverlay(kind) {
    if (!state.world) return;
    const store = overlays[kind];
    const enabled = kind === "claims" ? layers.claims
        : kind === "waystones" ? layers.waystones
        : (layers.markers || layers.banners);
    if (!enabled) return; // disabled layers cause no requests
    try {
      const headers = {};
      if (store.etag && store.world === state.world.slug) headers["If-None-Match"] = store.etag;
      const response = await fetch(`/api/v1/${kind}?world=${encodeURIComponent(state.world.slug)}`, { headers });
      if (response.status === 304) return;
      if (!response.ok) return;
      const payload = await response.json();
      store.items = payload.items || [];
      store.etag = response.headers.get("ETag");
      store.world = state.world.slug;
      scheduleDraw();
    } catch (e) { /* server briefly unavailable */ }
  }

  function refreshOverlays() {
    fetchOverlay("claims");
    fetchOverlay("markers");
    fetchOverlay("waystones");
  }

  function argbToRgba(hex) {
    // "#aarrggbb" or "#rrggbb"
    const raw = hex.replace("#", "");
    if (raw.length === 8) {
      const a = parseInt(raw.slice(0, 2), 16) / 255;
      return `rgba(${parseInt(raw.slice(2, 4), 16)},${parseInt(raw.slice(4, 6), 16)},${parseInt(raw.slice(6, 8), 16)},${a.toFixed(3)})`;
    }
    return `#${raw}`;
  }

  function drawClaims(scale, minBlockX, minBlockZ) {
    if (!layers.claims) return;
    const borderWidth = Math.max(1, overlays.claimCfg.borderWidth * Math.min(1, state.scale)) * state.dpr;
    for (const claim of overlays.claims.items) {
      ctx.fillStyle = argbToRgba(claim.fill);
      ctx.strokeStyle = argbToRgba(claim.border);
      ctx.lineWidth = borderWidth;
      for (const rect of claim.rects) {
        const x = (rect[0] - minBlockX) * scale;
        const z = (rect[1] - minBlockZ) * scale;
        const w = (rect[2] - rect[0] + 1) * scale;
        const h = (rect[3] - rect[1] + 1) * scale;
        if (x + w < 0 || z + h < 0 || x > canvas.width || z > canvas.height) continue;
        ctx.fillRect(x, z, w, h);
        ctx.strokeRect(x, z, w, h);
      }
    }
  }

  function visibleMarkers() {
    const base = overlays.markers.items.filter(m =>
        m.source === "banner" ? layers.banners : layers.markers);
    return layers.waystones ? base.concat(overlays.waystones.items) : base;
  }

  function drawMarkers(scale, minBlockX, minBlockZ) {
    if (!layers.markers && !layers.banners && !layers.waystones) return;
    const markers = visibleMarkers();
    const size = 24 * state.dpr;
    // cluster on low zoom: group markers per 48px screen cell
    if (state.scale < 0.25 && markers.length > 30) {
      const cell = 48 * state.dpr;
      const clusters = new Map();
      for (const m of markers) {
        const px = (m.x - minBlockX) * scale;
        const pz = (m.z - minBlockZ) * scale;
        if (px < -cell || pz < -cell || px > canvas.width + cell || pz > canvas.height + cell) continue;
        const key = `${Math.floor(px / cell)}:${Math.floor(pz / cell)}`;
        const cluster = clusters.get(key) || { x: 0, z: 0, n: 0 };
        cluster.x += px; cluster.z += pz; cluster.n++;
        clusters.set(key, cluster);
      }
      ctx.font = `${11 * state.dpr}px system-ui, sans-serif`;
      ctx.textAlign = "center";
      for (const cluster of clusters.values()) {
        const cx = cluster.x / cluster.n, cz = cluster.z / cluster.n;
        ctx.fillStyle = "#1b2130";
        ctx.strokeStyle = "#58a6ff";
        ctx.lineWidth = 1.5 * state.dpr;
        ctx.beginPath();
        ctx.arc(cx, cz, 12 * state.dpr, 0, Math.PI * 2);
        ctx.fill(); ctx.stroke();
        ctx.fillStyle = "#dbe2ef";
        ctx.fillText(String(cluster.n), cx, cz + 4 * state.dpr);
      }
      return;
    }
    for (const m of markers) {
      const px = (m.x - minBlockX) * scale;
      const pz = (m.z - minBlockZ) * scale;
      if (px < -size || pz < -size || px > canvas.width + size || pz > canvas.height + size) continue;
      let img;
      if (m.source === "banner" && m.bannerIcon) {
        img = imageFor(iconImages, "b:" + m.bannerIcon, `/api/v1/banner-icons/${m.bannerIcon}.png`);
        if (img.complete && img.naturalWidth > 0) {
          ctx.imageSmoothingEnabled = false;
          ctx.drawImage(img, px - 8 * state.dpr, pz - 30 * state.dpr, 16 * state.dpr, 32 * state.dpr);
        }
      } else {
        img = imageFor(iconImages, m.icon, m.icon && m.icon.startsWith('custom:')
            ? `/icons/c/${m.icon.slice(7)}.png` : `/icons/${m.icon}.svg`);
        if (img.complete && img.naturalWidth > 0) {
          ctx.drawImage(img, px - size / 2, pz - size / 2, size, size);
        }
      }
    }
  }

  // --- hover / tap picking -------------------------------------------------
  const tooltip = document.getElementById("tooltip");
  function pick(clientX, clientY) {
    if (!state.world) return null;
    const rect = canvas.getBoundingClientRect();
    const px = (clientX - rect.left) * state.dpr;
    const pz = (clientY - rect.top) * state.dpr;
    const scale = state.scale * state.dpr;
    const blockX = state.centerX + (px - canvas.width / 2) / scale;
    const blockZ = state.centerZ + (pz - canvas.height / 2) / scale;
    const radiusBlocks = 14 * state.dpr / scale;

    const result = { claims: [], markers: [], players: [] };
    if (layers.players) {
      for (const p of state.players) {
        if (p.world !== state.world.slug) continue;
        if (Math.abs(p.x - blockX) <= radiusBlocks && Math.abs(p.z - blockZ) <= radiusBlocks) {
          result.players.push(p);
        }
      }
    }
    for (const m of visibleMarkers()) {
      if (Math.abs(m.x - blockX) <= radiusBlocks && Math.abs(m.z - blockZ) <= radiusBlocks) {
        result.markers.push(m);
      }
    }
    if (layers.claims) {
      for (const claim of overlays.claims.items) {
        for (const r of claim.rects) {
          if (blockX >= r[0] && blockX <= r[2] && blockZ >= r[1] && blockZ <= r[3]) {
            result.claims.push(claim);
            break;
          }
        }
      }
    }
    return result;
  }

  function escapeHtml(text) {
    return String(text).replace(/[&<>"']/g, c => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  function showTooltip(clientX, clientY, picked) {
    if (!picked) {
      tooltip.hidden = true;
      return;
    }
    const parts = [];
    for (const p of picked.players) {
      parts.push(`<div class="head">${escapeHtml(p.name || "Player")}</div>` +
          `<div class="muted">${p.y !== undefined ? `${p.x}, ${p.y}, ${p.z}` : `${p.x}, ${p.z}`}</div>`);
    }
    for (const m of picked.markers) {
      let block = `<div class="head">${escapeHtml(m.name)}</div>`;
      block += `<div class="muted">${m.source === "banner" ? "Banner marker" : escapeHtml(m.icon)}` +
          (m.y !== undefined ? ` · ${m.x}, ${m.y}, ${m.z}` : "") + `</div>`;
      if (m.description) block += `<div>${escapeHtml(m.description)}</div>`;
      if (m.creator) block += `<div class="muted">by ${escapeHtml(m.creator)}</div>`;
      parts.push(block);
    }
    for (const c of picked.claims) {
      let block = `<div class="head">${escapeHtml(c.name || "Claim")}</div>`;
      if (c.owner) block += `<div>Owner: ${escapeHtml(c.owner)}</div>`;
      if (c.team) block += `<div>Team: ${escapeHtml(c.team)}</div>`;
      block += `<div class="muted">Source: ${escapeHtml(providerLabel(c.provider))}</div>`;
      parts.push(block);
    }
    if (parts.length === 0) {
      tooltip.hidden = true;
      return;
    }
    tooltip.innerHTML = parts.join("<hr>");
    tooltip.hidden = false;
    const pad = 14;
    tooltip.style.left = Math.min(window.innerWidth - tooltip.offsetWidth - pad, clientX + pad) + "px";
    tooltip.style.top = Math.min(window.innerHeight - tooltip.offsetHeight - pad, clientY + pad) + "px";
  }

  function providerLabel(id) {
    return { ftbchunks: "FTB Chunks", openpartiesandclaims: "Open Parties and Claims",
             jsonimport: "JSON import" }[id] || id;
  }

  function refreshTiles() {
    // Recreate images for currently visible tiles; ETag revalidation makes this cheap.
    for (const [url, entry] of tiles) {
      if (entry.failed || performance.now() - entry.seen > 5000) { tiles.delete(url); }
    }
    scheduleDraw();
  }

  // --- drawing ------------------------------------------------------------
  let drawQueued = false;
  function scheduleDraw() {
    if (drawQueued) return;
    drawQueued = true;
    const run = () => { if (drawQueued) draw(); };
    requestAnimationFrame(run);
    // rAF is suspended in hidden/background tabs — keep the map current anyway
    setTimeout(run, 120);
  }

  function draw() {
    drawQueued = false;
    if (!state.world) return;
    const w = canvas.width, h = canvas.height;
    ctx.fillStyle = "#10131a";
    ctx.fillRect(0, 0, w, h);

    const scale = state.scale * state.dpr;
    const maxZoom = state.world.zoomLevels;
    let level = 0;
    if (state.scale < 1) {
      level = Math.min(maxZoom, Math.max(0, Math.floor(-Math.log2(state.scale))));
    }
    const blocksPerTile = TILE * Math.pow(2, level);
    const drawSize = blocksPerTile * scale;

    const blocksAcross = w / scale;
    const blocksDown = h / scale;
    const minBlockX = state.centerX - blocksAcross / 2;
    const minBlockZ = state.centerZ - blocksDown / 2;
    const minTileX = Math.floor(minBlockX / blocksPerTile);
    const minTileZ = Math.floor(minBlockZ / blocksPerTile);
    const maxTileX = Math.floor((minBlockX + blocksAcross) / blocksPerTile);
    const maxTileZ = Math.floor((minBlockZ + blocksDown) / blocksPerTile);

    ctx.imageSmoothingEnabled = state.scale < 1;
    for (let tz = minTileZ; tz <= maxTileZ; tz++) {
      for (let tx = minTileX; tx <= maxTileX; tx++) {
        const url = `/tiles/${state.world.slug}/${level}/${tx}_${tz}.png`;
        const entry = tileFor(url);
        if (!entry.ok) continue;
        const px = (tx * blocksPerTile - minBlockX) * scale;
        const pz = (tz * blocksPerTile - minBlockZ) * scale;
        ctx.drawImage(entry.img, px, pz, drawSize, drawSize);
      }
    }
    drawClaims(scale, minBlockX, minBlockZ);
    drawMarkers(scale, minBlockX, minBlockZ);
    if (layers.players) {
      drawPlayers(scale, minBlockX, minBlockZ);
    }
  }

  function drawPlayers(scale, minBlockX, minBlockZ) {
    const mine = state.players.filter(p => p.world === state.world.slug);
    const head = 20 * state.dpr;
    ctx.font = `${12 * state.dpr}px system-ui, sans-serif`;
    ctx.textAlign = "center";
    for (const p of mine) {
      const x = (p.x - minBlockX) * scale;
      const z = (p.z - minBlockZ) * scale;
      // direction wedge behind the head
      ctx.save();
      ctx.translate(x, z);
      ctx.rotate(((p.yaw + 180) * Math.PI) / 180);
      ctx.fillStyle = "#e3b341";
      ctx.beginPath();
      ctx.moveTo(0, -head * 0.85);
      ctx.lineTo(head * 0.3, -head * 0.45);
      ctx.lineTo(-head * 0.3, -head * 0.45);
      ctx.closePath();
      ctx.fill();
      ctx.restore();
      const img = imageFor(headImages, p.uuid, `/api/v1/heads/${p.uuid}.png`);
      if (img.complete && img.naturalWidth > 0) {
        ctx.imageSmoothingEnabled = false;
        ctx.drawImage(img, x - head / 2, z - head / 2, head, head);
        ctx.strokeStyle = "#10131a";
        ctx.lineWidth = 1.5 * state.dpr;
        ctx.strokeRect(x - head / 2, z - head / 2, head, head);
      }
      if (layers.names && p.name) {
        ctx.fillStyle = "#10131a";
        ctx.fillText(p.name, x + 1, z - head / 2 - 4 * state.dpr + 1);
        ctx.fillStyle = "#dbe2ef";
        ctx.fillText(p.name, x, z - head / 2 - 4 * state.dpr);
      }
    }
  }

  // --- input --------------------------------------------------------------
  let dragging = false;
  let lastX = 0, lastZ = 0;
  let pinchDistance = 0;

  canvas.addEventListener("pointerdown", (e) => {
    dragging = true;
    canvas.classList.add("dragging");
    canvas.setPointerCapture(e.pointerId);
    lastX = e.clientX;
    lastZ = e.clientY;
  });
  canvas.addEventListener("pointerup", (e) => {
    dragging = false;
    canvas.classList.remove("dragging");
    updateHash();
  });
  canvas.addEventListener("pointermove", (e) => {
    updateCursor(e);
    if (!dragging) return;
    state.centerX -= (e.clientX - lastX) / state.scale;
    state.centerZ -= (e.clientY - lastZ) / state.scale;
    lastX = e.clientX;
    lastZ = e.clientY;
    scheduleDraw();
  });
  canvas.addEventListener("wheel", (e) => {
    e.preventDefault();
    zoomAt(e.clientX, e.clientY, e.deltaY < 0 ? 1.25 : 0.8);
  }, { passive: false });

  canvas.addEventListener("touchmove", (e) => {
    if (e.touches.length === 2) {
      e.preventDefault();
      const dx = e.touches[0].clientX - e.touches[1].clientX;
      const dz = e.touches[0].clientY - e.touches[1].clientY;
      const distance = Math.hypot(dx, dz);
      if (pinchDistance > 0) {
        const cx = (e.touches[0].clientX + e.touches[1].clientX) / 2;
        const cz = (e.touches[0].clientY + e.touches[1].clientY) / 2;
        zoomAt(cx, cz, distance / pinchDistance);
      }
      pinchDistance = distance;
    }
  }, { passive: false });
  canvas.addEventListener("touchend", () => { pinchDistance = 0; });

  function zoomAt(clientX, clientY, factor) {
    const maxOut = state.world ? Math.pow(2, state.world.zoomLevels + 1) : 16;
    const next = Math.min(8, Math.max(1 / maxOut, state.scale * factor));
    const rect = canvas.getBoundingClientRect();
    const px = (clientX - rect.left) * state.dpr;
    const pz = (clientY - rect.top) * state.dpr;
    const worldX = state.centerX + (px - canvas.width / 2) / (state.scale * state.dpr);
    const worldZ = state.centerZ + (pz - canvas.height / 2) / (state.scale * state.dpr);
    state.scale = next;
    state.centerX = worldX - (px - canvas.width / 2) / (state.scale * state.dpr);
    state.centerZ = worldZ - (pz - canvas.height / 2) / (state.scale * state.dpr);
    updateHash();
    scheduleDraw();
  }

  function updateCursor(e) {
    const rect = canvas.getBoundingClientRect();
    const px = (e.clientX - rect.left) * state.dpr;
    const pz = (e.clientY - rect.top) * state.dpr;
    state.cursorX = Math.floor(state.centerX + (px - canvas.width / 2) / (state.scale * state.dpr));
    state.cursorZ = Math.floor(state.centerZ + (pz - canvas.height / 2) / (state.scale * state.dpr));
    coordsEl.textContent = `${state.cursorX}, ${state.cursorZ}`;
    if (!dragging) {
      showTooltip(e.clientX, e.clientY, pick(e.clientX, e.clientY));
    } else {
      tooltip.hidden = true;
    }
  }
  canvas.addEventListener("pointerleave", () => { tooltip.hidden = true; });

  // --- hash deep links ----------------------------------------------------
  let hashTimer = null;
  function updateHash() {
    clearTimeout(hashTimer);
    hashTimer = setTimeout(() => {
      if (!state.world) return;
      const hash = `#${state.world.slug}/${Math.round(state.centerX)}/${Math.round(state.centerZ)}/${state.scale.toFixed(3)}`;
      history.replaceState(null, "", hash);
    }, 300);
  }

  function applyHash() {
    const parts = location.hash.replace(/^#/, "").split("/");
    if (parts.length === 4) {
      const world = state.worlds.find(w => w.slug === parts[0]);
      if (world) {
        state.world = world;
        worldSelect.value = world.slug;
        state.centerX = parseInt(parts[1], 10) || 0;
        state.centerZ = parseInt(parts[2], 10) || 0;
        state.scale = Math.min(8, Math.max(1 / 64, parseFloat(parts[3]) || 1));
        return true;
      }
    }
    return false;
  }

  document.getElementById("linkBtn").addEventListener("click", () => {
    navigator.clipboard?.writeText(location.href).then(() => {
      statusEl.textContent = "Link copied";
      setTimeout(() => (statusEl.textContent = ""), 1500);
    });
  });

  // --- data loading -------------------------------------------------------
  async function loadWorlds() {
    const response = await fetch("/api/worlds");
    if (!response.ok) throw new Error(`worlds: HTTP ${response.status}`);
    const payload = await response.json();
    state.worlds = payload.worlds || [];
    state.playersInterval = payload.playersIntervalSeconds || 2;
    if (payload.title) {
      titleEl.textContent = payload.title;
      document.title = payload.title;
    }
    worldSelect.innerHTML = "";
    for (const world of state.worlds) {
      const option = document.createElement("option");
      option.value = world.slug;
      option.textContent = world.id;
      worldSelect.appendChild(option);
    }
    if (!applyHash() && state.worlds.length > 0) {
      state.world = state.worlds[0];
      state.centerX = state.world.spawnX;
      state.centerZ = state.world.spawnZ;
    }
    if (state.world) worldSelect.value = state.world.slug;
  }

  worldSelect.addEventListener("change", () => {
    const world = state.worlds.find(w => w.slug === worldSelect.value);
    if (world) {
      state.world = world;
      state.centerX = world.spawnX;
      state.centerZ = world.spawnZ;
      tiles.clear();
      overlays.claims = { items: [], etag: null, world: null };
      overlays.markers = { items: [], etag: null, world: null };
      refreshOverlays();
      updateHash();
      scheduleDraw();
    }
  });

  async function pollPlayers() {
    try {
      if (layers.players && state.world) {
        const response = await fetch(`/api/v1/players?world=${encodeURIComponent(state.world.slug)}`);
        if (response.ok) {
          const payload = await response.json();
          state.players = payload.players || [];
          if (payload.interval) state.playersInterval = payload.interval;
          scheduleDraw();
        }
      }
    } catch (e) { /* server briefly unavailable */ }
    setTimeout(pollPlayers, Math.max(1, state.playersInterval) * 1000);
  }

  async function loadOverlayConfig() {
    try {
      const response = await fetch("/api/v1/overlays");
      if (!response.ok) return;
      const payload = await response.json();
      for (const layer of payload.layers || []) {
        if (layer.id === "claims") {
          if (layers.claims === undefined) layers.claims = layer.defaultVisible;
          overlays.claimCfg.fillOpacity = layer.fillOpacity ?? 0.3;
          overlays.claimCfg.borderWidth = layer.borderWidth ?? 2;
        }
        if (layer.id === "markers" && layers.markers === undefined) layers.markers = layer.defaultVisible;
        if (layer.id === "banner-markers" && layers.banners === undefined) layers.banners = layer.defaultVisible;
        if (layer.id === "waystones" && layers.waystones === undefined) layers.waystones = layer.defaultVisible;
        if (layer.id === "players") {
          if (layers.players === undefined) layers.players = layer.defaultVisible;
          if (layers.names === undefined) layers.names = layer.showNames !== false;
        }
      }
    } catch (e) { /* defaults stay */ }
    for (const key of ["claims", "markers", "banners", "waystones", "players", "names"]) {
      if (layers[key] === undefined) layers[key] = true;
    }
    syncLayerPanel();
  }

  function syncLayerPanel() {
    const bind = (id, key, onChange) => {
      const box = document.getElementById(id);
      if (!box) return;
      box.checked = !!layers[key];
      box.onchange = () => {
        layers[key] = box.checked;
        saveLayerPrefs();
        if (onChange) onChange();
        scheduleDraw();
      };
    };
    bind("layer-claims", "claims", () => fetchOverlay("claims"));
    bind("layer-markers", "markers", () => fetchOverlay("markers"));
    bind("layer-banners", "banners", () => fetchOverlay("markers"));
    bind("layer-waystones", "waystones", () => fetchOverlay("waystones"));
    bind("layer-players", "players");
    bind("layer-names", "names");
  }
  document.getElementById("layersBtn").addEventListener("click", () => {
    const panel = document.getElementById("layersPanel");
    panel.hidden = !panel.hidden;
  });

  // --- boot ---------------------------------------------------------------
  function resize() {
    state.dpr = Math.max(1, window.devicePixelRatio || 1);
    const w = canvas.clientWidth;
    const h = canvas.clientHeight;
    if (w === 0 || h === 0) {
      // stylesheet/layout not ready yet — retry shortly (not rAF: hidden tabs suspend it)
      setTimeout(resize, 100);
      return;
    }
    canvas.width = Math.floor(w * state.dpr);
    canvas.height = Math.floor(h * state.dpr);
    scheduleDraw();
  }
  window.addEventListener("resize", resize);
  window.addEventListener("load", resize);
  window.addEventListener("hashchange", () => { if (applyHash()) scheduleDraw(); });

  resize();
  loadWorlds()
    .then(() => loadOverlayConfig())
    .then(() => {
      scheduleDraw();
      pollPlayers();
      refreshOverlays();
      setInterval(refreshTiles, 30000);
      setInterval(refreshOverlays, 15000);
    })
    .catch((e) => {
      statusEl.textContent = "Map data unavailable — is the server still starting?";
      console.error(e);
    });
})();
