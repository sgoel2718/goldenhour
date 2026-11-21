// GoldenHour — "any city in the world".
//
// Builds a dispatch-ready city entirely in the browser from live OpenStreetMap data:
//   1. Overpass API → drivable roads + hospitals within a radius of the chosen point
//   2. road graph: one-way rules, speed by road class, largest strongly connected component (iterative Kosaraju)
//   3. demand zones (≈800 m cells weighted by residential street length) × the hour-of-week profile learned from real 911 data
//   4. hospitals: up to 14 named ones, spread out, each within 150 m of a road; capabilities inferred from the name (assumed)
//   5. ambulance bases: MEXCLP lazy-greedy coverage (same objective as the Java planner) on 7-minute drive-time coverage
// The result has the same shape as the Java-exported city bundles, so the same dispatch engine runs on it
// (routing uses A* here, because contraction-hierarchy preprocessing is done offline in Java for the prebuilt cities).
'use strict';

(function () {
  // public Overpass mirrors, raced in parallel: the first good answer wins and the others are cancelled
  const OVERPASS = ['https://overpass-api.de/api/interpreter', 'https://overpass.kumi.systems/api/interpreter', 'https://overpass.private.coffee/api/interpreter'];
  const SPEED = { motorway: 90, trunk: 70, primary: 55, secondary: 45, tertiary: 40, unclassified: 30, residential: 25, living_street: 10,
    service: 15, motorway_link: 50, trunk_link: 40, primary_link: 40, secondary_link: 35, tertiary_link: 30, road: 30 };
  const HOMES = { residential: 1, living_street: 1, unclassified: 0.5, tertiary: 0.4, secondary: 0.2, service: 0.1 };
  // Ambulance numbers by ISO country code (official national numbers). 112 reaches emergency services from most mobile phones.
  const EMERGENCY = { in: '108', us: '911', ca: '911', mx: '911', ph: '911', gb: '999', ie: '112', au: '000', nz: '111', jp: '119', kr: '119',
    cn: '120', tw: '119', hk: '999', sg: '995', my: '999', bd: '999', pk: '1122', lk: '1990', np: '102', ae: '998', sa: '997', qa: '999',
    eg: '123', za: '10177', ng: '112', ke: '999', br: '192', ar: '107', cl: '131', co: '123', ru: '103', tr: '112', il: '101', th: '1669',
    id: '118', vn: '115' };
  const TZ_HALF = { in: 5.5, lk: 5.5, np: 5.75, ir: 3.5, af: 4.5, mm: 6.5 };
  const R = 6371000, rad = Math.PI / 180;
  const hav = (a1, o1, a2, o2) => { const x = Math.sin((a2 - a1) * rad / 2) ** 2 + Math.cos(a1 * rad) * Math.cos(a2 * rad) * Math.sin((o2 - o1) * rad / 2) ** 2; return 2 * R * Math.asin(Math.sqrt(x)); };

  async function overpass(query, progress, label = 'map data') {
    const t0 = performance.now(), ctrls = OVERPASS.map(() => new AbortController());
    let bytes = 0;
    const tick = setInterval(() => progress(`Downloading ${label} from OpenStreetMap… ${((performance.now() - t0) / 1000).toFixed(0)} s${bytes ? ` · ${(bytes / 1e6).toFixed(1)} MB` : ''}`), 500);
    const giveUp = setTimeout(() => ctrls.forEach(c => c.abort()), 120000);   // never wait forever on a stuck server
    const one = async (url, k) => {
      const r = await fetch(url, { method: 'POST', body: 'data=' + encodeURIComponent(query), signal: ctrls[k].signal,
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      ctrls.forEach((c, j) => { if (j !== k) c.abort(); });          // this mirror answered first: cancel the rest
      const reader = r.body.getReader(), parts = [];
      for (;;) { const { done, value } = await reader.read(); if (done) break; parts.push(value); bytes += value.length; }
      return JSON.parse(new TextDecoder().decode(await new Blob(parts).arrayBuffer()));
    };
    try {
      return await Promise.any(OVERPASS.map(one));
    } catch (e) {
      throw new Error('OpenStreetMap servers are busy right now. Please try again in a minute.');
    } finally { clearInterval(tick); clearTimeout(giveUp); }
  }

  function speedOf(tags, cls) {
    const m = String(tags.maxspeed || '').match(/^(\d+)\s*(mph)?/);
    if (m) return +m[1] * (m[2] ? 1.609 : 1);
    return SPEED[cls] || 30;
  }

  /** Largest strongly connected component (iterative Kosaraju on CSR arrays). */
  function largestSCC(n, from, to) {
    const csr = (a, b) => { const s = new Int32Array(n + 1); for (const u of a) s[u + 1]++; for (let i = 0; i < n; i++) s[i + 1] += s[i];
      const t = new Int32Array(a.length), f = s.slice(0, n); for (let e = 0; e < a.length; e++) t[f[a[e]]++] = b[e]; return [s, t]; };
    const [os, ot] = csr(from, to), [is, it] = csr(to, from);
    const seen = new Uint8Array(n), order = [];
    for (let r0 = 0; r0 < n; r0++) {
      if (seen[r0]) continue;
      const st = [r0], ix = [os[r0]]; seen[r0] = 1;
      while (st.length) {
        const v = st[st.length - 1];
        if (ix[ix.length - 1] < os[v + 1]) { const w = ot[ix[ix.length - 1]++]; if (!seen[w]) { seen[w] = 1; st.push(w); ix.push(os[w]); } }
        else { order.push(v); st.pop(); ix.pop(); }
      }
    }
    const comp = new Int32Array(n).fill(-1); let best = -1, bestSize = 0, c = 0;
    for (let k = order.length - 1; k >= 0; k--) {
      const r0 = order[k]; if (comp[r0] >= 0) continue;
      let size = 0; const st = [r0]; comp[r0] = c;
      while (st.length) { const v = st.pop(); size++; for (let e = is[v]; e < is[v + 1]; e++) { const w = it[e]; if (comp[w] < 0) { comp[w] = c; st.push(w); } } }
      if (size > bestSize) { bestSize = size; best = c; }
      c++;
    }
    return comp.map(x => x === best ? 1 : 0);
  }

  const MAJOR = /general|university|regional|district|central|teaching|medical (cent(er|re)|college)|government|govt|public|municipal|civil|city hospital|kaiser|mayo|cleveland|charit|mount sinai|presbyterian|memorial|royal|national/i;
  const SKIP = /eye|dental|dent|skin|cosmetic|ivf|fertility|vet|animal|psychiat|mental|rehab|dialysis|hospice|lab|diagnostic|imaging|clinic|ayur|homeo|siddha|unani/i;
  function capabilities(name, major) {
    const n = name.toLowerCase();
    if (/maternity|women|child|children|obstet|gyn|paediatric|pediatric|kinder|kasturba/.test(n)) return ['MATERNITY', 'GENERAL'];
    if (/heart|cardi/.test(n)) return ['CARDIAC', 'GENERAL'];
    if (/ortho|trauma|accident|bone/.test(n)) return ['TRAUMA', 'GENERAL'];
    if (/general|university|teaching|medical college|government|govt|regional|district|central|royal|national/.test(n)) return ['CARDIAC', 'STROKE', 'TRAUMA', 'BURNS', 'MATERNITY', 'GENERAL'];
    return major ? ['CARDIAC', 'STROKE', 'TRAUMA', 'GENERAL'] : ['GENERAL', 'TRAUMA'];
  }

  /**
   * Build a city bundle around (lat, lon). `radius` in metres (3–6 km keeps the download and the browser fast).
   * Returns the same structure as the Java ExportStatic bundles (minus the contraction hierarchy).
   */
  async function buildCity({ lat, lon, radius = 3000, name = 'City', cc = '' }, progress = () => {}, model) {
    const t0 = performance.now();
    // service roads (parking aisles, driveways) are skipped: they are most of the download and rarely matter for an ambulance route
    const hw = Object.keys(SPEED).filter(k => k !== 'service').join('|');
    const [roads, hosp] = await Promise.all([
      overpass(`[out:json][timeout:90];way["highway"~"^(${hw})$"]["access"!~"^(no|private)$"]["area"!="yes"](around:${radius},${lat},${lon})->.w;.w out body qt;node(w.w);out skel qt;`, progress, 'roads'),
      overpass(`[out:json][timeout:60];nwr["amenity"="hospital"](around:${radius + 1500},${lat},${lon});out center tags qt;`, () => {}, 'hospitals').catch(() => ({ elements: [] }))]);

    progress('Building the road graph…');
    const pos = new Map();
    for (const el of roads.elements) if (el.type === 'node') pos.set(el.id, [el.lat, el.lon]);
    const id = new Map(), lats = [], lons = [];
    const nid = osm => { let i = id.get(osm); if (i === undefined) { i = lats.length; id.set(osm, i); const p = pos.get(osm); lats.push(p[0]); lons.push(p[1]); } return i; };
    const F = [], T = [], W = [], C = [];
    for (const el of roads.elements) {
      if (el.type !== 'way' || !el.tags) continue;
      const cls = el.tags.highway, sp = speedOf(el.tags, cls) * 0.7;            // 0.7: urban congestion (assumption)
      let ow = el.tags.oneway || ((cls === 'motorway' || cls === 'motorway_link' || el.tags.junction === 'roundabout') ? 'yes' : 'no');
      for (let k = 1; k < el.nodes.length; k++) {
        const a = el.nodes[k - 1], b = el.nodes[k];
        if (a === b || !pos.has(a) || !pos.has(b)) continue;
        const u = nid(a), v = nid(b), len = hav(lats[u], lons[u], lats[v], lons[v]), w = len / (sp / 3.6);
        if (ow === 'yes' || ow === 'true' || ow === '1') { F.push(u); T.push(v); W.push(w); C.push(cls); }
        else if (ow === '-1') { F.push(v); T.push(u); W.push(w); C.push(cls); }
        else { F.push(u, v); T.push(v, u); W.push(w, w); C.push(cls, cls); }
      }
    }
    if (lats.length < 200) throw new Error('Too few roads found here. Pick a town or city centre.');
    const keep = largestSCC(lats.length, F, T);
    const remap = new Int32Array(lats.length).fill(-1), nl = [], no = [];
    for (let i = 0; i < lats.length; i++) if (keep[i]) { remap[i] = nl.length; nl.push(+lats[i].toFixed(6)); no.push(+lons[i].toFixed(6)); }
    const ef = [], et = [], ew = [], ec = [];
    for (let e = 0; e < F.length; e++) { const u = remap[F[e]], v = remap[T[e]]; if (u >= 0 && v >= 0) { ef.push(u); et.push(v); ew.push(+W[e].toFixed(2)); ec.push(C[e]); } }
    const n = nl.length;

    const G = new GH.Graph({ lat: nl, lon: no, edges: { from: ef, to: et, w: ew } });

    // ---- demand zones: ~800 m cells, weight = residential street length (population proxy)
    progress('Estimating where emergencies happen…');
    const cell = 0.0072, cells = new Map();
    for (let e = 0; e < ef.length; e++) {
      const h = HOMES[ec[e]] || 0; if (!h) continue;
      const u = ef[e], k = Math.floor(nl[u] / cell) + ':' + Math.floor(no[u] / cell);
      const c = cells.get(k) || { w: 0, sa: 0, so: 0, k: 0 };
      const len = hav(nl[u], no[u], nl[et[e]], no[et[e]]) / 2;
      c.w += h * len; c.sa += nl[u]; c.so += no[u]; c.k++; cells.set(k, c);
    }
    const total = [...cells.values()].reduce((s, c) => s + c.w, 0);
    const zoneCells = [...cells.entries()].filter(([, c]) => c.w > total * 0.003);
    const resKm = total / 1000;
    const population = Math.round(resKm * 900);                                  // ~900 residents per km of residential street (assumption)
    const weekly = population * model.weeklyPerCapita * 0.35;
    const zw = zoneCells.reduce((s, [, c]) => s + c.w, 0);
    const zones = zoneCells.map(([k, c]) => {
      const node = G.snap(c.sa / c.k, c.so / c.k), share = c.w / zw;
      return { id: k, node, rates: model.how.map(m => +(share * weekly / 168 * m).toFixed(6)) };
    });

    // ---- hospitals
    progress('Choosing hospitals…');
    const cands = [];
    for (const el of hosp.elements) {
      const t = el.tags || {}, nm = (t.name || t['name:en'] || '').trim();
      const a = el.lat ?? (el.center && el.center.lat), o = el.lon ?? (el.center && el.center.lon);
      if (!nm || a == null || SKIP.test(nm)) continue;
      const node = G.snap(a, o);
      if (hav(a, o, nl[node], no[node]) > 150) continue;
      const score = (MAJOR.test(nm) ? 2 : 1) + (t.emergency === 'yes' ? 2 : 0) + (t.beds ? 1 : 0);
      cands.push({ score, name: nm.slice(0, 60), lat: a, lon: o, node, phone: (t['contact:phone'] || t.phone || '').split(';')[0].trim(), emergency: t.emergency });
    }
    cands.sort((x, y) => y.score - x.score);
    const chosen = [];
    for (const gap of [1500, 900, 400]) for (const c of cands) {
      if (chosen.length >= 14) break;
      if (chosen.includes(c) || chosen.some(x => x.name.toLowerCase() === c.name.toLowerCase())) continue;
      if (chosen.every(x => hav(c.lat, c.lon, x.lat, x.lon) >= gap)) chosen.push(c);
    }
    if (!chosen.length) throw new Error('No named hospitals found within this area on OpenStreetMap. Try a larger city or a more central point.');
    const hospitals = chosen.map((c, i) => ({ id: i, name: c.name, node: c.node, lat: c.lat, lon: c.lon, specialties: capabilities(c.name, c.score >= 2), phone: c.phone, phoneSource: c.phone ? 'OpenStreetMap' : '' }));

    // ---- ambulance bases: MEXCLP lazy greedy over zone + hospital candidates, 7-min coverage
    progress('Placing ambulances (coverage optimisation)…');
    const ambulances = Math.max(6, Math.min(40, Math.round(population / 100000)));
    const candNodes = [...new Set([...zones.map(z => z.node), ...hospitals.map(h => h.node)])];
    const zoneIndex = new Map(); zones.forEach((z, i) => { if (!zoneIndex.has(z.node)) zoneIndex.set(z.node, []); zoneIndex.get(z.node).push(i); });
    const heap = new GH.Heap(n), dist = new Float64Array(n).fill(Infinity), touched = [];
    const covers = candNodes.map(s => {
      for (const v of touched) dist[v] = Infinity; touched.length = 0; heap.reset();
      dist[s] = 0; touched.push(s); heap.push(s, 0);
      const out = [];
      while (!heap.empty()) {
        const u = heap.pop(); if (dist[u] > 420) break;
        if (zoneIndex.has(u)) out.push(...zoneIndex.get(u));
        for (let e = G.out.start[u]; e < G.out.start[u + 1]; e++) { const x = G.out.tgt[e], d = dist[u] + G.out.wt[e]; if (d < dist[x]) { if (dist[x] === Infinity) touched.push(x); dist[x] = d; heap.push(x, d); } }
      }
      return out;
    });
    const w = zones.map(z => z.rates.reduce((s, r) => s + r, 0));
    const q = Math.min(0.9, weekly / 168 / ambulances), k = new Int32Array(zones.length), bases = [];
    const gain = c => covers[c].reduce((s, z) => s + w[z] * (1 - q) * q ** k[z], 0);
    for (let a = 0; a < ambulances; a++) {
      let best = 0, bg = -1;
      for (let c = 0; c < candNodes.length; c++) { const g = gain(c); if (g > bg) { bg = g; best = c; } }
      bases.push(candNodes[best]); for (const z of covers[best]) k[z]++;
    }

    const tz = TZ_HALF[cc] ?? Math.round(lon / 15);
    progress(`Ready: ${n.toLocaleString()} road junctions, ${hospitals.length} hospitals, ${ambulances} ambulances (${((performance.now() - t0) / 1000).toFixed(1)} s)`);
    return { lat: nl, lon: no, edges: { from: ef, to: et, w: ew }, hospitals, bases, zones, caseMix: model.caseMix, volume: 1,
      city: name, tzOffsetHours: tz, emergency: EMERGENCY[cc] || '112', country: cc, built: 'browser', radius, center: [lat, lon] };
  }

  // ---- tiny IndexedDB cache so a refresh does not download the city again
  function db() { return new Promise((ok, err) => { const r = indexedDB.open('goldenhour', 1); r.onupgradeneeded = () => r.result.createObjectStore('cities'); r.onsuccess = () => ok(r.result); r.onerror = () => err(r.error); }); }
  async function cacheGet(key) { try { const d = await db(); return await new Promise(ok => { const r = d.transaction('cities').objectStore('cities').get(key); r.onsuccess = () => ok(r.result || null); r.onerror = () => ok(null); }); } catch (e) { return null; } }
  async function cachePut(key, val) { try { const d = await db(); d.transaction('cities', 'readwrite').objectStore('cities').put(val, key); } catch (e) { /* private mode */ } }

  window.GHWorld = { buildCity, cacheGet, cachePut, EMERGENCY };
})();

// ======================================================================================================
// Quick mode: the city is usable immediately, before its road network has downloaded.
// Hospitals come from a fast OpenStreetMap place search; drive times and routes from the public OSRM server
// (router.project-osrm.org, OpenStreetMap data). It answers the same API as the in-browser engine, so the
// dashboard does not change. Once the full network is built in the background, the page switches to it.
// ======================================================================================================
(function () {
  const OSRM = 'https://router.project-osrm.org';
  const CONGESTION = 1 / 0.7;                   // OSRM gives free-flow times; urban traffic is slower (assumption, as for the engine)
  const R = 6371000, rad = Math.PI / 180;
  const hav = (a1, o1, a2, o2) => { const x = Math.sin((a2 - a1) * rad / 2) ** 2 + Math.cos(a1 * rad) * Math.cos(a2 * rad) * Math.sin((o2 - o1) * rad / 2) ** 2; return 2 * R * Math.asin(Math.sqrt(x)); };
  const SEV = { P1_CRITICAL: 0, P2_EMERGENT: 1, P3_URGENT: 2, P4_ROUTINE: 3 };
  const clock = s => { const d = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'], p = x => String(x).padStart(2, '0'); s = Math.floor(s); return `${d[Math.floor(s / 86400) % 7]} ${p(Math.floor(s / 3600) % 24)}:${p(Math.floor(s / 60) % 60)}:${p(s % 60)}`; };

  async function osrmRoute(a, b) {
    const r = await (await fetch(`${OSRM}/route/v1/driving/${a[1]},${a[0]};${b[1]},${b[0]}?overview=full&geometries=geojson`)).json();
    if (!r.routes || !r.routes.length) throw new Error('no route');
    const c = r.routes[0].geometry.coordinates.map(([o, l]) => [l, o]);
    return { path: c, seconds: r.routes[0].duration * CONGESTION };
  }
  async function osrmTable(sources, dest) {        // seconds from each source to dest, one request
    const pts = [dest, ...sources].map(p => `${p[1]},${p[0]}`).join(';');
    const r = await (await fetch(`${OSRM}/table/v1/driving/${pts}?sources=${sources.map((_, i) => i + 1).join(';')}&destinations=0`)).json();
    return (r.durations || []).map(row => row[0] == null ? Infinity : row[0] * CONGESTION);
  }

  async function quickHospitals(lat, lon, radius) {
    const d = radius / 111000, box = `${lon - d * 1.3},${lat + d},${lon + d * 1.3},${lat - d}`;
    try {
      const r = await (await fetch(`https://nominatim.openstreetmap.org/search?format=jsonv2&q=hospital&limit=40&bounded=1&extratags=1&viewbox=${box}`, { headers: { 'Accept-Language': 'en' } })).json();
      const out = [];
      for (const x of r) {
        const name = (x.name || x.display_name.split(',')[0]).trim();
        if (!name || /eye|dental|skin|ivf|vet|clinic|diagnostic|lab/i.test(name) || out.some(h => h.name === name)) continue;
        if (out.some(h => hav(h.lat, h.lon, +x.lat, +x.lon) < 600)) continue;
        const t = x.extratags || {};
        out.push({ id: out.length, name: name.slice(0, 60), lat: +x.lat, lon: +x.lon, specialties: ['CARDIAC', 'STROKE', 'TRAUMA', 'GENERAL', 'MATERNITY'], phone: (t.phone || t['contact:phone'] || '').split(';')[0] });
        if (out.length >= 12) break;
      }
      return out;
    } catch (e) { return []; }
  }

  async function startQuick({ lat, lon, radius, name, cc }) {
    const hospitals = await quickHospitals(lat, lon, radius);
    const tz = ({ in: 5.5, lk: 5.5, np: 5.75 })[cc] ?? Math.round(lon / 15);
    const u = new Date(Date.now() + tz * 3600e3);
    const W = {
      city: name, emergency: GHWorld.EMERGENCY[cc] || '112', speed: 1, simStart: (((u.getUTCDay() + 6) % 7) * 24 + u.getUTCHours()) * 3600 + u.getUTCMinutes() * 60 + u.getUTCSeconds(),
      wallStart: Date.now(), seq: 0, incidents: [], queue: [], events: [], resp: [], p1: [0, 0], lat: [],
      now() { return this.simStart + (Date.now() - this.wallStart) / 1000 * this.speed; },
      log(t) { this.events.unshift({ text: clock(this.now()) + '  ' + t }); if (this.events.length > 60) this.events.pop(); }
    };
    // 8 ambulances: at up to 4 hospitals, the rest spread on a ring around the centre
    W.amb = [];
    for (let i = 0; i < 8; i++) {
      const h = hospitals[i];
      const p = h && i < 4 ? [h.lat, h.lon] : [lat + Math.cos(i * 0.785) * radius * 0.5 / 111000, lon + Math.sin(i * 0.785) * radius * 0.5 / (111000 * Math.cos(lat * rad))];
      W.amb.push({ id: i, unit: i % 3 === 2 ? 'BLS' : 'ALS', pos: p, base: p, free: true, job: null });
    }
    const posAt = (j, t) => {
      if (!j.path || j.path.length < 2) return j.at || j.path[0];
      const f = Math.min(1, Math.max(0, (t - (j.end - j.dur)) / Math.max(1, j.dur)));
      const target = f * j.len; let acc = 0;
      for (let i = 1; i < j.path.length; i++) {
        const seg = hav(j.path[i - 1][0], j.path[i - 1][1], j.path[i][0], j.path[i][1]);
        if (acc + seg >= target) { const g = seg ? (target - acc) / seg : 0; return [j.path[i - 1][0] + g * (j.path[i][0] - j.path[i - 1][0]), j.path[i - 1][1] + g * (j.path[i][1] - j.path[i - 1][1])]; }
        acc += seg;
      }
      return j.path[j.path.length - 1];
    };
    const setPath = (j, path, dur, t) => { j.path = path; j.dur = dur; j.end = t + dur; j.len = path.reduce((s, p, i) => i ? s + hav(path[i - 1][0], path[i - 1][1], p[0], p[1]) : 0, 0); };
    const chooseHospital = inc => {
      let best = null, bd = Infinity;
      for (const h of hospitals) { const d = hav(inc.lat, inc.lon, h.lat, h.lon); if (d < bd) { bd = d; best = h; } }
      return best;
    };
    async function dispatch(inc) {
      const free = W.amb.filter(a => a.free).map(a => ({ a, d: hav(a.pos[0], a.pos[1], inc.lat, inc.lon) })).sort((x, y) => x.d - y.d).slice(0, 8);
      if (!free.length) return null;
      let etas; try { etas = await osrmTable(free.map(x => x.a.pos), [inc.lat, inc.lon]); } catch (e) { etas = free.map(x => x.d / 8.3); }   // ~30 km/h if the server is busy
      let best = -1, bs = Infinity;
      free.forEach((x, i) => { const sc = etas[i] + (inc.severity === 'P1_CRITICAL' && x.a.unit === 'BLS' ? 180 : 0); if (sc < bs) { bs = sc; best = i; } });
      const a = free[best].a, audit = free.map((x, i) => ({ ambulance: x.a.id, straightLineKm: x.d / 1000, roadEtaMin: etas[i] / 60 }));
      let r; try { r = await osrmRoute(a.pos, [inc.lat, inc.lon]); } catch (e) { r = { path: [a.pos, [inc.lat, inc.lon]], seconds: etas[best] }; }
      const h = chooseHospital(inc);
      inc.ambulance = a.id; inc.hospital = h; a.free = false;
      a.job = { phase: 'EN_ROUTE', inc }; setPath(a.job, r.path, 60 + r.seconds, W.now());
      return { a, r, h, audit };
    }
    async function advance(a, t) {
      const j = a.job, inc = j.inc;
      if (j.phase === 'EN_ROUTE') {
        const m = (t - inc.created) / 60; inc.responseMin = m; W.resp.push(m);
        if (inc.severity === 'P1_CRITICAL') { W.p1[1]++; if (m <= 8) W.p1[0]++; }
        W.log(`AMB-${a.id} on scene at ${inc.id} after ${m.toFixed(1)} min`);
        a.job = { phase: 'ON_SCENE', inc, at: [inc.lat, inc.lon], path: [[inc.lat, inc.lon]] }; a.job.end = t + 12 * 60 + Math.random() * 480; a.job.dur = 0;
      } else if (j.phase === 'ON_SCENE') {
        if (inc.hospital) {
          let r; try { r = await osrmRoute([inc.lat, inc.lon], [inc.hospital.lat, inc.hospital.lon]); } catch (e) { r = { path: [[inc.lat, inc.lon], [inc.hospital.lat, inc.hospital.lon]], seconds: 900 }; }
          a.job = { phase: 'TRANSPORTING', inc }; setPath(a.job, r.path, r.seconds, t);
        } else free(a, [inc.lat, inc.lon], t);
      } else if (j.phase === 'TRANSPORTING') {
        W.log(`AMB-${a.id} delivered ${inc.id} to ${inc.hospital.name}`);
        a.job = { phase: 'HANDOVER', inc, at: [inc.hospital.lat, inc.hospital.lon], path: [[inc.hospital.lat, inc.hospital.lon]] }; a.job.end = t + 900; a.job.dur = 0;
      } else if (j.phase === 'HANDOVER') free(a, a.job.at, t);
      else if (j.phase === 'RETURNING') { a.pos = a.base; a.job = null; }
    }
    async function free(a, at, t) {
      a.pos = at; a.free = true; a.job = null;
      if (W.queue.length) { W.queue.sort((x, y) => SEV[x.severity] - SEV[y.severity] || x.created - y.created); const inc = W.queue.shift(); await dispatch(inc); return; }
      let r; try { r = await osrmRoute(at, a.base); } catch (e) { r = { path: [at, a.base], seconds: 600 }; }
      a.job = { phase: 'RETURNING', inc: null }; setPath(a.job, r.path, r.seconds, t);
    }
    let busy = false;
    setInterval(async () => {
      if (busy) return; busy = true;
      try { const t = W.now(); for (const a of W.amb) { let k = 0; while (a.job && t >= a.job.end && k++ < 5) await advance(a, a.job.end); } } finally { busy = false; }
    }, 300);
    const incJson = i => ({ id: i.id, lat: i.lat, lon: i.lon, severity: i.severity, need: i.need, category: i.category, notes: i.notes, time: clock(i.created) });
    const ring = []; for (let k = 0; k < 48; k++) ring.push([lat + Math.cos(k * Math.PI / 24) * radius / 111000, lon + Math.sin(k * Math.PI / 24) * radius / (111000 * Math.cos(lat * rad))]);

    async function api(method, path, body) {
      const url = new URL(path, location.href), p = url.pathname.replace(/^.*\/api\/v1\//, '');
      if (p === 'info') return [200, { city: W.city, emergency: W.emergency, country: cc, routing: 'OSRM', quick: true, hull: ring, nodes: 0,
        bounds: [[lat - radius / 111000, lon - radius / 111000], [lat + radius / 111000, lon + radius / 111000]] }];
      if (p === 'snap') { const a = +url.searchParams.get('lat'), o = +url.searchParams.get('lon'), d = hav(a, o, lat, lon) - radius; return [200, { lat: a, lon: o, distanceM: Math.max(0, d), inside: d <= 0 }]; }
      if (p === 'incidents' && method === 'POST') {
        if (hav(body.lat, body.lon, lat, lon) > radius) return [422, { error: 'outside the area being set up' }];
        const tr = GH.triage(body.notes || ''), t1 = performance.now();
        const inc = { id: 'INC-' + (++W.seq), lat: body.lat, lon: body.lon, severity: tr.severity, need: tr.need, category: tr.category, notes: String(body.notes || '').slice(0, 1000), created: W.now() };
        W.incidents.push(inc);
        const res = await dispatch(inc);
        const ms = performance.now() - t1; W.lat.push(ms);
        if (!res) { W.queue.push(inc); W.log(`${inc.id} queued`); return [202, { incident: incJson(inc), triage: { ...tr, ms: 0 }, assignment: null, candidates: [], dispatchMs: ms, source: 'browser' }]; }
        W.log(`${inc.id} ${inc.severity} ${inc.category} → AMB-${res.a.id} ETA ${(res.r.seconds / 60).toFixed(1)} min`);
        return [201, { incident: incJson(inc), triage: { ...tr, ms: 0 }, candidates: res.audit, dispatchMs: ms, source: 'browser',
          assignment: { ambulance: res.a.id, unit: res.a.unit, etaMin: res.r.seconds / 60, hospital: res.h ? res.h.name : 'nearest hospital', hospitalPhone: res.h ? res.h.phone : '',
            transportMin: res.h ? hav(inc.lat, inc.lon, res.h.lat, res.h.lon) / 6.9 / 60 : null, reason: 'quick mode (public routing)', path: res.r.path } }];
      }
      if (p === 'sim' && method === 'POST') { if ([1, 10, 60].includes(body.speed)) { W.simStart = W.now(); W.wallStart = Date.now(); W.speed = body.speed; } return [200, { speed: W.speed, autoCalls: false }]; }
      if (p === 'state') {
        const t = W.now(), resp = [...W.resp].sort((x, y) => x - y);
        const ids = (url.searchParams.get('ids') || '').split(',').filter(Boolean);
        const st = {
          simTime: t, clock: clock(t), speed: W.speed, autoCalls: false, events: W.events,
          ambulances: W.amb.map(a => { const j = a.job, pos = j ? posAt(j, t) : a.pos;
            const m = { id: a.id, unit: a.unit, lat: pos[0], lon: pos[1], phase: j ? j.phase : 'AVAILABLE', incident: j && j.inc ? j.inc.id : null, base: a.base, secondsLeft: j ? Math.max(0, j.end - t) : 0 };
            if (j && ['EN_ROUTE', 'TRANSPORTING', 'RETURNING'].includes(j.phase) && j.path.length > 1) { const f = Math.min(1, Math.max(0, (t - (j.end - j.dur)) / Math.max(1, j.dur))); m.path = [pos, ...j.path.slice(Math.floor(f * (j.path.length - 1)) + 1)]; }
            return m; }),
          incidents: W.incidents.slice(-40).map(i => ({ ...incJson(i), ambulance: i.ambulance ?? null, responseMin: i.responseMin ?? null })),
          hospitals: hospitals.map(h => ({ id: h.id, name: h.name, lat: h.lat, lon: h.lon, specialties: h.specialties, phone: h.phone })),
          stats: { calls: W.seq, waiting: W.queue.length, busy: W.amb.filter(a => a.job && a.job.phase !== 'RETURNING').length,
            p1Within8: W.p1[1] ? W.p1[0] / W.p1[1] : null, medianResponseMin: resp.length ? resp[resp.length >> 1] : null, dispatchP99Ms: null } };
        st.mine = ids.map(id => W.incidents.find(i => i.id === id)).filter(Boolean).map(i => ({ ...incJson(i), ambulance: i.ambulance ?? null, responseMin: i.responseMin ?? null, hospital: i.hospital ? i.hospital.name : null }));
        return [200, st];
      }
      if (p === 'route') {
        const [fa, fo] = url.searchParams.get('from').split(',').map(Number), [ta, to] = url.searchParams.get('to').split(',').map(Number), t0 = performance.now();
        try { const r = await osrmRoute([fa, fo], [ta, to]); return [200, { algorithm: 'OSRM (public server)', found: true, minutes: r.seconds / 60, settledNodes: 0, queryMs: performance.now() - t0, path: r.path }]; }
        catch (e) { return [200, { algorithm: 'OSRM (public server)', found: false, minutes: NaN, settledNodes: 0, queryMs: 0, path: [] }]; }
      }
      if (p === 'demand') return [200, { zones: [] }];
      if (p === 'reports') {
        const get = async (f, json) => { try { const r = await fetch('data/' + f); return r.ok ? (json ? r.json() : r.text()) : null; } catch { return null; } };
        return [200, { simulation: await get('simulation.md'), benchmarks: await get('benchmarks.md'), forecast: await get('forecast_metrics.json', true) }];
      }
      return [404, { error: 'not found' }];
    }
    return { api, hospitals };
  }
  window.GHWorld.startQuick = startQuick;
})();
