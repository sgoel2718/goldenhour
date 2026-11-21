// GoldenHour — browser edition of the dispatch core.
//
// The contraction hierarchy is preprocessed offline by the Java core (ExportStatic) and shipped as plain
// arrays; this file runs the queries, the dispatch decisions and the live fleet simulation entirely in the
// browser, so the demo needs no server at all. It mirrors com.goldenhour.{routing,dispatch,api.LiveFleet}.
// It answers the same API calls as the Java server (see `GH.api`), so the dashboard code is shared.
'use strict';

(function () {
  // ------------------------------------------------------------------ indexed binary min-heap (decrease-key)
  class Heap {
    constructor(n) { this.heap = new Int32Array(n); this.pos = new Int32Array(n).fill(-1); this.key = new Float64Array(n); this.size = 0; this.touched = []; }
    reset() { for (const v of this.touched) this.pos[v] = -1; this.touched = []; this.size = 0; }
    empty() { return this.size === 0; }
    peekKey() { return this.key[this.heap[0]]; }
    push(v, k) {
      if (this.pos[v] === -2) return;                       // already settled this round
      if (this.pos[v] === -1) { this.touched.push(v); this.heap[this.size] = v; this.pos[v] = this.size++; this.key[v] = k; this.up(this.pos[v]); }
      else if (k < this.key[v]) { this.key[v] = k; this.up(this.pos[v]); }
    }
    pop() {
      const top = this.heap[0], last = this.heap[--this.size];
      this.pos[top] = -2;
      if (this.size > 0) { this.heap[0] = last; this.pos[last] = 0; this.down(0); }
      return top;
    }
    up(i) { const h = this.heap, k = this.key, p = this.pos, v = h[i]; while (i > 0) { const pa = (i - 1) >> 1; if (k[h[pa]] <= k[v]) break; h[i] = h[pa]; p[h[i]] = i; i = pa; } h[i] = v; p[v] = i; }
    down(i) {
      const h = this.heap, k = this.key, p = this.pos, v = h[i], n = this.size;
      for (;;) { let c = 2 * i + 1; if (c >= n) break; if (c + 1 < n && k[h[c + 1]] < k[h[c]]) c++; if (k[h[c]] >= k[v]) break; h[i] = h[c]; p[h[i]] = i; i = c; }
      h[i] = v; p[v] = i;
    }
  }

  const R = 6371000, rad = Math.PI / 180;
  function haversine(a1, o1, a2, o2) {
    const dp = (a2 - a1) * rad, dl = (o2 - o1) * rad;
    const x = Math.sin(dp / 2) ** 2 + Math.cos(a1 * rad) * Math.cos(a2 * rad) * Math.sin(dl / 2) ** 2;
    return 2 * R * Math.asin(Math.sqrt(x));
  }

  // ------------------------------------------------------------------ graph (CSR, both directions)
  function csr(n, from, to, w) {
    const start = new Int32Array(n + 1);
    for (let e = 0; e < from.length; e++) start[from[e] + 1]++;
    for (let i = 0; i < n; i++) start[i + 1] += start[i];
    const tgt = new Int32Array(from.length), wt = new Float64Array(from.length), fill = start.slice(0, n);
    for (let e = 0; e < from.length; e++) { const i = fill[from[e]]++; tgt[i] = to[e]; wt[i] = w[e]; }
    return { start, tgt, wt };
  }

  class Graph {
    constructor(d) {
      this.n = d.lat.length; this.lat = Float64Array.from(d.lat); this.lon = Float64Array.from(d.lon);
      this.out = csr(this.n, d.edges.from, d.edges.to, d.edges.w);
      this.inn = csr(this.n, d.edges.to, d.edges.from, d.edges.w);
      let vmax = 0;
      for (let e = 0; e < d.edges.from.length; e++) {
        const u = d.edges.from[e], v = d.edges.to[e];
        vmax = Math.max(vmax, haversine(this.lat[u], this.lon[u], this.lat[v], this.lon[v]) / Math.max(d.edges.w[e], 1e-6));
      }
      this.maxSpeed = vmax;
      this.edgeCount = d.edges.from.length;
      // uniform grid over nodes for snapping a click to the nearest road node
      this.cell = 0.004; this.grid = new Map();
      for (let v = 0; v < this.n; v++) { const k = this.key(this.lat[v], this.lon[v]); (this.grid.get(k) || this.grid.set(k, []).get(k)).push(v); }
    }
    key(a, o) { return Math.floor(a / this.cell) + ':' + Math.floor(o / this.cell); }
    snap(a, o) {
      const ca = Math.floor(a / this.cell), co = Math.floor(o / this.cell);
      let best = -1, bd = Infinity;
      for (let r = 0; r < 60 && (best < 0 || r < 2); r++)
        for (let i = ca - r; i <= ca + r; i++) for (let j = co - r; j <= co + r; j++) {
          if (Math.max(Math.abs(i - ca), Math.abs(j - co)) !== r) continue;
          for (const v of this.grid.get(i + ':' + j) || []) { const d = haversine(a, o, this.lat[v], this.lon[v]); if (d < bd) { bd = d; best = v; } }
        }
      return best;
    }
    edgeWeight(u, v) { const o = this.out; let w = Infinity; for (let i = o.start[u]; i < o.start[u + 1]; i++) if (o.tgt[i] === v) w = Math.min(w, o.wt[i]); return w; }
  }

  // ------------------------------------------------------------------ routers (same algorithms as the Java core)
  class Dijkstra {
    constructor(g, name = 'dijkstra') { this.g = g; this.name = name; this.h = new Heap(g.n); this.d = new Float64Array(g.n).fill(Infinity); this.p = new Int32Array(g.n); this.t = []; }
    potential() { return 0; }
    clear() { for (const v of this.t) this.d[v] = Infinity; this.t = []; this.h.reset(); }
    set(v, d, p, k) { if (this.d[v] === Infinity) this.t.push(v); this.d[v] = d; this.p[v] = p; this.h.push(v, k); }
    route(s, t) {
      this.clear();
      this.set(s, 0, -1, this.potential(s, t));
      let settled = 0;
      const o = this.g.out;
      while (!this.h.empty()) {
        const u = this.h.pop(); settled++;
        if (u === t) break;
        for (let i = o.start[u]; i < o.start[u + 1]; i++) {
          const x = o.tgt[i], nd = this.d[u] + o.wt[i];
          if (nd < this.d[x]) this.set(x, nd, u, nd + this.potential(x, t));
        }
      }
      if (this.d[t] === Infinity) return { nodes: [], seconds: Infinity, settled };
      const path = []; for (let v = t; v !== -1; v = this.p[v]) path.push(v);
      return { nodes: path.reverse(), seconds: this.d[t], settled };
    }
    /** one-to-all on the reverse graph: travel time from every node TO t (used for hospital trees) */
    allTo(t) {
      const dist = new Float64Array(this.g.n).fill(Infinity), h = new Heap(this.g.n), I = this.g.inn;
      dist[t] = 0; h.push(t, 0);
      while (!h.empty()) {
        const u = h.pop();
        for (let i = I.start[u]; i < I.start[u + 1]; i++) { const x = I.tgt[i], nd = dist[u] + I.wt[i]; if (nd < dist[x]) { dist[x] = nd; h.push(x, nd); } }
      }
      return dist;
    }
  }

  class AStar extends Dijkstra {
    constructor(g) { super(g, 'a-star'); }
    potential(v, t) { return haversine(this.g.lat[v], this.g.lon[v], this.g.lat[t], this.g.lon[t]) / this.g.maxSpeed; }
  }

  class Bidirectional {
    constructor(g) { this.g = g; this.name = 'bidirectional-dijkstra'; }
    route(s, t) {
      const g = this.g, n = g.n;
      const df = new Map([[s, 0]]), db = new Map([[t, 0]]), pf = new Map([[s, -1]]), pb = new Map([[t, -1]]);
      const hf = new Heap(n), hb = new Heap(n); hf.push(s, 0); hb.push(t, 0);
      let best = Infinity, meet = -1, settled = 0;
      while (!hf.empty() && !hb.empty() && hf.peekKey() + hb.peekKey() < best) {
        const fwd = hf.peekKey() <= hb.peekKey(), h = fwd ? hf : hb, d = fwd ? df : db, other = fwd ? db : df, par = fwd ? pf : pb, G = fwd ? g.out : g.inn;
        const u = h.pop(); settled++;
        for (let i = G.start[u]; i < G.start[u + 1]; i++) {
          const x = G.tgt[i], nd = d.get(u) + G.wt[i];
          if (nd < (d.has(x) ? d.get(x) : Infinity)) { d.set(x, nd); par.set(x, u); h.push(x, nd); }
          if (other.has(x) && nd + other.get(x) < best) { best = nd + other.get(x); meet = x; }
        }
      }
      if (s === t) return { nodes: [s], seconds: 0, settled: 1 };
      if (meet < 0) return { nodes: [], seconds: Infinity, settled };
      const a = []; for (let v = meet; v !== -1; v = pf.get(v)) a.push(v);
      a.reverse(); for (let v = pb.get(meet); v !== -1 && v !== undefined; v = pb.get(v)) a.push(v);
      return { nodes: a, seconds: best, settled };
    }
  }

  /** Contraction-hierarchy QUERY over the hierarchy preprocessed by the Java core. */
  class CH {
    constructor(g, ch) {
      this.g = g; this.name = 'contraction-hierarchies';
      const n = g.n, m = ch.from.length;
      this.from = Int32Array.from(ch.from); this.to = Int32Array.from(ch.to); this.w = Float64Array.from(ch.w);
      this.a = Int32Array.from(ch.a); this.b = Int32Array.from(ch.b); const rank = ch.rank;
      this.upOut = new Int32Array(n + 1); this.upIn = new Int32Array(n + 1);
      for (let e = 0; e < m; e++) rank[this.from[e]] < rank[this.to[e]] ? this.upOut[this.from[e] + 1]++ : this.upIn[this.to[e] + 1]++;
      for (let i = 0; i < n; i++) { this.upOut[i + 1] += this.upOut[i]; this.upIn[i + 1] += this.upIn[i]; }
      this.outE = new Int32Array(this.upOut[n]); this.inE = new Int32Array(this.upIn[n]);
      const po = this.upOut.slice(0, n), pi = this.upIn.slice(0, n);
      for (let e = 0; e < m; e++) rank[this.from[e]] < rank[this.to[e]] ? (this.outE[po[this.from[e]]++] = e) : (this.inE[pi[this.to[e]]++] = e);
      this.hf = new Heap(n); this.hb = new Heap(n);
      this.df = new Float64Array(n).fill(Infinity); this.db = new Float64Array(n).fill(Infinity);
      this.pf = new Int32Array(n); this.pb = new Int32Array(n); this.tf = []; this.tb = [];
    }
    route(s, t) {
      for (const v of this.tf) this.df[v] = Infinity; for (const v of this.tb) this.db[v] = Infinity;
      this.tf = []; this.tb = []; this.hf.reset(); this.hb.reset();
      if (s === t) return { nodes: [s], seconds: 0, settled: 1 };
      const setF = (v, d, e) => { if (this.df[v] === Infinity) this.tf.push(v); this.df[v] = d; this.pf[v] = e; this.hf.push(v, d); };
      const setB = (v, d, e) => { if (this.db[v] === Infinity) this.tb.push(v); this.db[v] = d; this.pb[v] = e; this.hb.push(v, d); };
      setF(s, 0, -1); setB(t, 0, -1);
      let best = Infinity, meet = -1, settled = 0;
      for (;;) {
        const fOk = !this.hf.empty() && this.hf.peekKey() < best, bOk = !this.hb.empty() && this.hb.peekKey() < best;
        if (!fOk && !bOk) break;
        const fwd = fOk && (!bOk || this.hf.peekKey() <= this.hb.peekKey());
        settled++;
        if (fwd) {
          const u = this.hf.pop();
          if (this.df[u] + this.db[u] < best) { best = this.df[u] + this.db[u]; meet = u; }
          for (let i = this.upOut[u]; i < this.upOut[u + 1]; i++) { const e = this.outE[i], x = this.to[e], d = this.df[u] + this.w[e]; if (d < this.df[x]) setF(x, d, e); }
        } else {
          const u = this.hb.pop();
          if (this.df[u] + this.db[u] < best) { best = this.df[u] + this.db[u]; meet = u; }
          for (let i = this.upIn[u]; i < this.upIn[u + 1]; i++) { const e = this.inE[i], x = this.from[e], d = this.db[u] + this.w[e]; if (d < this.db[x]) setB(x, d, e); }
        }
      }
      if (meet < 0) return { nodes: [], seconds: Infinity, settled };
      const edges = [];
      for (let v = meet; this.pf[v] !== -1; v = this.from[this.pf[v]]) edges.push(this.pf[v]);
      edges.reverse();
      for (let v = meet; this.pb[v] !== -1; v = this.to[this.pb[v]]) edges.push(this.pb[v]);
      const path = [s];
      const unpack = e => { if (this.a[e] < 0) path.push(this.to[e]); else { unpack(this.a[e]); unpack(this.b[e]); } };
      for (const e of edges) unpack(e);
      return { nodes: path, seconds: best, settled };
    }
  }

  // ------------------------------------------------------------------ triage rules (mirror of Triage.java)
  const RULES = [
    [['not breathing', 'unconscious', 'unresponsive', 'cardiac arrest', 'no pulse', 'choking'], 'P1_CRITICAL', 'CARDIAC', 'CARDIAC/RESPIRATORY ARREST'],
    [['chest pain', 'heart attack', 'cardiac'], 'P1_CRITICAL', 'CARDIAC', 'CARDIAC EMERGENCY'],
    [['stroke', 'face drooping', 'slurred speech', "can't move arm", 'cva'], 'P1_CRITICAL', 'STROKE', 'STROKE'],
    [['severe bleeding', 'hemorrhag', 'haemorrhag', 'gunshot', 'stab'], 'P1_CRITICAL', 'TRAUMA', 'SEVERE TRAUMA'],
    [['difficulty breathing', 'shortness of breath', 'asthma', 'respiratory'], 'P2_EMERGENT', 'GENERAL', 'RESPIRATORY EMERGENCY'],
    [['burn', 'fire', 'smoke'], 'P2_EMERGENT', 'BURNS', 'BURNS'],
    [['seizure', 'convuls', 'overdose', 'poison', 'diabetic', 'allergic', 'anaphyla'], 'P2_EMERGENT', 'GENERAL', 'MEDICAL EMERGENCY'],
    [['labour', 'labor', 'pregnan', 'contractions'], 'P2_EMERGENT', 'MATERNITY', 'MATERNITY'],
    [['accident', 'collision', 'crash', 'vehicle', 'fall', 'fracture', 'injur'], 'P3_URGENT', 'TRAUMA', 'INJURY'],
    [['fever', 'vomit', 'abdominal', 'headache', 'dizz', 'weak', 'sick'], 'P3_URGENT', 'GENERAL', 'ILLNESS']];
  function triage(notes) {
    const t = (notes || '').toLowerCase();
    for (const [words, severity, need, category] of RULES) if (words.some(w => t.includes(w))) return { severity, need, category, source: 'rules' };
    return { severity: 'P3_URGENT', need: 'GENERAL', category: 'UNSPECIFIED', source: 'rules' };
  }
  const SEV_ORDER = { P1_CRITICAL: 0, P2_EMERGENT: 1, P3_URGENT: 2, P4_ROUTINE: 3 };
  const NOTES = {
    'CARDIAC EMERGENCY': '58 y/o male, crushing chest pain radiating to left arm, sweating', 'CARDIAC ARREST': 'man collapsed at the bakery, not breathing, bystander doing CPR',
    'CVA/STROKE': "wife says husband's face drooping and slurred speech since 20 minutes", 'RESPIRATORY EMERGENCY': 'elderly woman with severe difficulty breathing, lips bluish',
    'FALL VICTIM': '82 y/o fell down the stairs, hip pain, cannot stand', 'VEHICLE ACCIDENT': 'two-car collision on the main road, one driver injured',
    'SEIZURES': 'teenager having a seizure, shaking for 3 minutes', 'UNCONSCIOUS SUBJECT': 'person found unconscious in a parked car',
    'UNRESPONSIVE SUBJECT': "father unresponsive in bed, breathing slowly", 'HEMORRHAGING': 'deep cut with severe bleeding from forearm',
    'MATERNITY': 'pregnant, 39 weeks, contractions every 3 minutes', 'OVERDOSE': 'suspected overdose, drowsy and vomiting',
    'DIABETIC EMERGENCY': 'diabetic, confused and shaky, low sugar reading', 'ALLERGIC REACTION': 'swollen lips after eating nuts, allergic reaction',
    'ABDOMINAL PAINS': 'severe abdominal pain and vomiting since morning', 'HEAD INJURY': 'scooter rider hit by a car, head injury, conscious',
    'BURN VICTIM': 'kitchen fire, burns to both hands', 'SUBJECT IN PAIN': 'back pain, cannot get out of bed', 'FEVER': 'child with high fever and headache' };

  // ------------------------------------------------------------------ dispatch engine + live fleet
  class World {
    constructor(d, speed = 1) {
      const t0 = performance.now();
      this.g = new Graph(d);
      this.ch = new CH(this.g, d.ch);
      this.routers = { ch: this.ch, dijkstra: new Dijkstra(this.g), astar: new AStar(this.g), bidi: new Bidirectional(this.g) };
      this.hospitals = d.hospitals;
      const dj = new Dijkstra(this.g);
      this.toHospital = this.hospitals.map(h => dj.allTo(h.node));       // reverse trees, as in DispatchEngine
      this.city = d.city || 'City'; this.zones = d.zones; this.caseMix = d.caseMix; this.volume = d.volume; this.shortcuts = d.shortcuts;
      this.amb = d.bases.map((b, i) => ({ id: i, unit: i % 3 === 2 ? 'BLS' : 'ALS', node: b, base: b, free: true, job: null }));
      this.speed = speed; this.autoCalls = true; this.seq = 0;
      this.incidents = []; this.queue = []; this.events = []; this.resp = []; this.p1 = [0, 0]; this.lat = [];
      const now = new Date(new Date().toLocaleString('en-US', { timeZone: d.tz || 'UTC' }));
      this.simStart = (((now.getDay() + 6) % 7) * 24 + now.getHours()) * 3600 + now.getMinutes() * 60 + now.getSeconds();
      this.wallStart = performance.now();
      this.cache = new Map();
      this.loadMs = performance.now() - t0;
      this.rnd = mulberry(42);
    }
    now() { return this.simStart + (performance.now() - this.wallStart) / 1000 * this.speed; }
    setSpeed(x) { this.simStart = this.now(); this.wallStart = performance.now(); this.speed = x; }   // re-anchor: no jump
    route(a, b) {
      const k = a * this.g.n + b;
      let r = this.cache.get(k);
      if (!r) { r = this.ch.route(a, b); this.cache.set(k, r); if (this.cache.size > 20000) this.cache.delete(this.cache.keys().next().value); }
      return r;
    }
    chooseHospital(inc) {
      let best = -1, bt = Infinity, fb = -1, ft = Infinity;
      this.hospitals.forEach((h, i) => {
        const t = this.toHospital[i][inc.node];
        if (t < ft) { ft = t; fb = i; }
        if (h.specialties.includes(inc.need) && t < bt) { bt = t; best = i; }
      });
      return best >= 0 ? best : fb;
    }
    candidates(inc, k = 8) {
      const g = this.g;
      return this.amb.filter(a => a.free).map(a => ({ a, m: haversine(g.lat[a.node], g.lon[a.node], inc.lat, inc.lon) })).sort((x, y) => x.m - y.m).slice(0, k);
    }
    dispatchTo(inc) {
      const cands = this.candidates(inc), audit = [];
      let best = null, br = null, bs = Infinity;
      for (const { a, m } of cands) {
        const r = this.route(a.node, inc.node);
        audit.push({ ambulance: a.id, straightLineKm: m / 1000, roadEtaMin: r.seconds / 60 });
        if (!r.nodes.length) continue;
        const score = r.seconds + (inc.severity === 'P1_CRITICAL' && a.unit === 'BLS' ? 180 : 0);   // ALS preferred for P1
        if (score < bs) { bs = score; best = a; br = r; }
      }
      return { best, route: br, audit, n: cands.length };
    }
    report(lat, lon, notes, source) {
      const t0 = performance.now();
      const tr = triage(notes), trMs = performance.now() - t0;
      const node = this.g.snap(lat, lon);
      const inc = { id: 'INC-' + (++this.seq), lat, lon, node, severity: tr.severity, need: tr.need, category: tr.category, notes, created: this.now() };
      this.incidents.push(inc); if (this.incidents.length > 300) this.incidents.shift();
      const t1 = performance.now();
      const d = this.dispatchTo(inc);
      const ms = performance.now() - t1; this.lat.push(ms); if (this.lat.length > 2000) this.lat.shift();
      let as = null;
      if (d.best) { as = this.assign(d.best, inc, d.route, `${d.n} candidates; NEAREST_ROAD_ETA`); this.log(`${inc.id} ${inc.severity} ${inc.category} → AMB-${d.best.id} ETA ${(d.route.seconds / 60).toFixed(1)} min, hospital ${as.hospital}`); }
      else { this.queue.push(inc); this.log(`${inc.id} ${inc.severity} queued — no unit free (${this.queue.length} waiting)`); }
      return { incident: incJson(inc), triage: { ...tr, ms: trMs }, assignment: as, candidates: d.audit, dispatchMs: ms, source };
    }
    assign(a, inc, r, reason) {
      const h = this.chooseHospital(inc);
      inc.ambulance = a.id; inc.hospital = h;
      a.free = false;
      this.startJob(a, { phase: 'EN_ROUTE', inc }, r.nodes, this.now(), 60 + r.seconds);
      const g = this.g, step = Math.max(1, Math.floor(r.nodes.length / 200)), path = [];
      for (let i = 0; i < r.nodes.length; i += step) path.push([g.lat[r.nodes[i]], g.lon[r.nodes[i]]]);
      const last = r.nodes[r.nodes.length - 1]; path.push([g.lat[last], g.lon[last]]);
      return { ambulance: a.id, unit: a.unit, etaMin: r.seconds / 60, hospital: h >= 0 ? this.hospitals[h].name : 'none',
        transportMin: h >= 0 ? this.toHospital[h][inc.node] / 60 : null, hospitalPhone: h >= 0 ? this.hospitals[h].phone || '' : '', reason, path };
    }
    startJob(a, job, nodes, t, duration) {
      job.route = nodes; job.cum = new Float64Array(nodes.length);
      for (let i = 1; i < nodes.length; i++) job.cum[i] = job.cum[i - 1] + this.g.edgeWeight(nodes[i - 1], nodes[i]);
      job.start = t; job.end = t + duration; a.job = job;
    }
    tick(dt) {
      const t = this.now();
      if (this.autoCalls) this.spawn(dt);
      for (const a of this.amb) {
        while (a.job && t >= a.job.end) this.advance(a, a.job.end);
        if (a.job && a.job.phase === 'RETURNING') a.node = this.nodeAt(a.job, t);          // keep the moving unit dispatchable where it is
      }
    }
    advance(a, t) {
      const j = a.job, inc = j.inc;
      if (j.phase === 'EN_ROUTE') {
        const r = (t - inc.created) / 60;
        inc.responseMin = r; this.resp.push(r);
        if (inc.severity === 'P1_CRITICAL') { this.p1[1]++; if (r <= 8) this.p1[0]++; }
        this.log(`AMB-${a.id} on scene at ${inc.id} after ${r.toFixed(1)} min`);
        this.startJob(a, { phase: 'ON_SCENE', inc }, [inc.node], t, 12 * 60 + this.rnd() * 8 * 60);
      } else if (j.phase === 'ON_SCENE') {
        const p = { P1_CRITICAL: 1, P2_EMERGENT: 1, P3_URGENT: 0.7, P4_ROUTINE: 0.3 }[inc.severity];
        if (inc.hospital >= 0 && this.rnd() < p) { const r = this.route(inc.node, this.hospitals[inc.hospital].node); this.startJob(a, { phase: 'TRANSPORTING', inc }, r.nodes, t, r.seconds); }
        else this.free(a, inc.node, t);
      } else if (j.phase === 'TRANSPORTING') {
        this.log(`AMB-${a.id} delivered ${inc.id} to ${this.hospitals[inc.hospital].name}`);
        this.startJob(a, { phase: 'HANDOVER', inc }, [j.route[j.route.length - 1]], t, 15 * 60);
      } else if (j.phase === 'HANDOVER') this.free(a, j.route[0], t);
      else if (j.phase === 'RETURNING') { a.node = a.base; a.job = null; }
    }
    free(a, node, t) {
      a.node = node; a.free = true; a.job = null;
      if (this.queue.length) {                                           // severity first, then oldest
        this.queue.sort((x, y) => SEV_ORDER[x.severity] - SEV_ORDER[y.severity] || x.created - y.created);
        const inc = this.queue.shift(), r = this.route(node, inc.node);
        this.assign(a, inc, r, 'from queue (waited)');
        this.log(`${inc.id} (queued) → AMB-${a.id} ETA ${(r.seconds / 60).toFixed(1)} min`);
        return;
      }
      const back = this.route(node, a.base);
      this.startJob(a, { phase: 'RETURNING', inc: null }, back.nodes.length ? back.nodes : [node], t, back.nodes.length ? back.seconds : 0);
    }
    spawn(dt) {
      const how = Math.floor(this.now() / 3600) % 168;
      for (const z of this.zones) {
        if (this.rnd() < z.rates[how] * this.volume * dt / 3600) {
          let u = this.rnd(), c = 0, ct = this.caseMix[this.caseMix.length - 1];
          for (const x of this.caseMix) { c += x.share; if (u <= c) { ct = x; break; } }
          const notes = NOTES[ct.category] || ct.category.toLowerCase().replace('/', ' ');
          this.report(this.g.lat[z.node] + (this.rnd() - 0.5) * 0.004, this.g.lon[z.node] + (this.rnd() - 0.5) * 0.004, notes, 'simulated');
        }
      }
    }
    along(j, t) { return Math.max(0, t - (j.end - j.cum[j.cum.length - 1])); }
    nodeAt(j, t) { const al = this.along(j, t); let i = 0; while (i + 1 < j.cum.length && j.cum[i + 1] <= al) i++; return j.route[i]; }
    pos(j, t) {
      const g = this.g;
      if (j.route.length <= 1) return [g.lat[j.route[0]], g.lon[j.route[0]]];
      const al = this.along(j, t); let i = 0;
      while (i + 1 < j.cum.length && j.cum[i + 1] <= al) i++;
      if (i + 1 >= j.cum.length) { const n = j.route[j.route.length - 1]; return [g.lat[n], g.lon[n]]; }
      const seg = j.cum[i + 1] - j.cum[i], f = seg <= 0 ? 0 : (al - j.cum[i]) / seg, a = j.route[i], b = j.route[i + 1];
      return [g.lat[a] + f * (g.lat[b] - g.lat[a]), g.lon[a] + f * (g.lon[b] - g.lon[a])];
    }
    state() {
      const t = this.now(), g = this.g;
      const ambs = this.amb.map(a => {
        const j = a.job, p = j ? this.pos(j, t) : [g.lat[a.node], g.lon[a.node]];
        const m = { id: a.id, unit: a.unit, lat: p[0], lon: p[1], phase: j ? j.phase : 'AVAILABLE', incident: j && j.inc ? j.inc.id : null,
          base: [g.lat[a.base], g.lon[a.base]], secondsLeft: j ? Math.max(0, j.end - t) : 0 };
        if (j && ['EN_ROUTE', 'TRANSPORTING', 'RETURNING'].includes(j.phase)) {
          const al = this.along(j, t), step = Math.max(1, Math.floor(j.route.length / 150)), path = [p];
          for (let i = 0; i < j.route.length; i += step) if (j.cum[i] > al) path.push([g.lat[j.route[i]], g.lon[j.route[i]]]);
          const l = j.route[j.route.length - 1]; path.push([g.lat[l], g.lon[l]]); m.path = path;
        }
        return m;
      });
      const sorted = [...this.resp].sort((x, y) => x - y), ls = [...this.lat].sort((x, y) => x - y);
      return {
        simTime: t, clock: clock(t), speed: this.speed, autoCalls: this.autoCalls, ambulances: ambs,
        incidents: this.incidents.slice(-40).map(i => ({ ...incJson(i), ambulance: i.ambulance ?? null, responseMin: i.responseMin ?? null })),
        hospitals: this.hospitals.map(h => ({ id: h.id, name: h.name, lat: g.lat[h.node], lon: g.lon[h.node], specialties: h.specialties, phone: h.phone || '' })),
        stats: { calls: this.seq, waiting: this.queue.length, busy: this.amb.filter(a => a.job && a.job.phase !== 'RETURNING').length,
          p1Within8: this.p1[1] ? this.p1[0] / this.p1[1] : null, medianResponseMin: sorted.length ? sorted[sorted.length >> 1] : null,
          dispatchP99Ms: ls.length ? +ls[Math.floor(ls.length * 0.99)].toFixed(2) : null },
        events: this.events };
    }
    log(text) { this.events.unshift({ text: clock(this.now()) + '  ' + text }); if (this.events.length > 60) this.events.pop(); }
  }

  function incJson(i) { return { id: i.id, lat: i.lat, lon: i.lon, severity: i.severity, need: i.need, category: i.category, notes: i.notes, time: clock(i.created) }; }
  function clock(s) { const d = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'], p = x => String(x).padStart(2, '0'); s = Math.floor(s); return `${d[Math.floor(s / 86400) % 7]} ${p(Math.floor(s / 3600) % 24)}:${p(Math.floor(s / 60) % 60)}:${p(s % 60)}`; }
  function mulberry(a) { return () => { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }

  // ------------------------------------------------------------------ API facade (same paths/payloads as the Java server)
  let world = null;
  async function init(base = 'data/') {
    const d = await (await fetch(base + 'city.json')).json();
    world = new World(d);
    setInterval(() => world.tick(0.2 * world.speed), 200);
    return world;
  }
  async function api(method, path, body) {
    const w = world, g = w.g, url = new URL(path, location.href), p = url.pathname.replace(/^.*\/api\/v1\//, '');
    if (p === 'info') {
      let a0 = 90, a1 = -90, o0 = 180, o1 = -180;
      for (let v = 0; v < g.n; v++) { a0 = Math.min(a0, g.lat[v]); a1 = Math.max(a1, g.lat[v]); o0 = Math.min(o0, g.lon[v]); o1 = Math.max(o1, g.lon[v]); }
      return [200, { city: w.city, nodes: g.n, edges: g.edgeCount, bounds: [[a0, o0], [a1, o1]], mode: 'browser', loadMs: w.loadMs }];
    }
    if (p === 'state') return [200, w.state()];
    if (p === 'incidents' && method === 'POST') {
      if (typeof body.lat !== 'number' || typeof body.lon !== 'number') return [400, { error: 'lat/lon must be numbers' }];
      const r = w.report(body.lat, body.lon, String(body.notes || '').slice(0, 1000), 'browser');
      return [r.assignment ? 201 : 202, r];
    }
    if (p === 'sim' && method === 'POST') { if (typeof body.autoCalls === 'boolean') w.autoCalls = body.autoCalls; if ([1, 10, 60].includes(body.speed)) w.setSpeed(body.speed); return [200, { autoCalls: w.autoCalls, speed: w.speed }]; }
    if (p === 'demand') {
      const how = Math.floor(w.now() / 3600) % 168;
      return [200, { hourOfWeek: how, zones: w.zones.map(z => ({ id: z.id, lat: g.lat[z.node], lon: g.lon[z.node], nowPerHour: z.rates[how], perWeek: z.rates.reduce((x, y) => x + y, 0) })) }];
    }
    if (p === 'route') {
      const [fa, fo] = url.searchParams.get('from').split(',').map(Number), [ta, to] = url.searchParams.get('to').split(',').map(Number);
      const r = w.routers[url.searchParams.get('algo') || 'ch'];
      const s = g.snap(fa, fo), t = g.snap(ta, to), t0 = performance.now();
      let route = r.route(s, t), reps = 1;
      while (performance.now() - t0 < 2 && reps < 200) { route = r.route(s, t); reps++; }   // time tiny queries over repeats
      const ms = (performance.now() - t0) / reps;
      return [200, { algorithm: r.name, found: route.nodes.length > 0, minutes: route.seconds / 60, settledNodes: route.settled, queryMs: ms, path: route.nodes.map(v => [g.lat[v], g.lon[v]]) }];
    }
    if (p === 'reports') {
      const get = async (f, json) => { try { const r = await fetch('data/' + f); return r.ok ? (json ? r.json() : r.text()) : null; } catch { return null; } };
      return [200, { simulation: await get('simulation.md'), benchmarks: await get('benchmarks.md'), forecast: await get('forecast_metrics.json', true) }];
    }
    return [404, { error: 'not found' }];
  }

  window.GH = { init, api, triage, Heap, Graph, Dijkstra, AStar, Bidirectional, CH };
})();
