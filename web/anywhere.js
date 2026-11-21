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
