let DATA = null;
let current = null;
let mode = "single";
let drawEdges = true;
let drawBridges = true;
let drawLabels = false;
let visibleCount = 1;
let transform = { scale: 1, tx: 0, ty: 0 };
let dragging = false;
let last = null;

const canvas = document.getElementById("canvas");
const ctx = canvas.getContext("2d");
const hud = document.getElementById("hud");
const nodeQuery = document.getElementById("nodeQuery");
const edgeQuery = document.getElementById("edgeQuery");
const queryStatus = document.getElementById("queryStatus");

function fmt(value, digits = 3) {
  if (!Number.isFinite(value)) return "-";
  return value.toFixed(digits);
}

function idPart(value) {
  return String(value).replace(/[^A-Za-z0-9_]/g, "_");
}

function rect() {
  return canvas.getBoundingClientRect();
}

function colors() {
  return DATA ? DATA.colors : {};
}

function nodeScore(idx) {
  return Math.abs(Math.sin(Number(idx) * 12.9898 + 78.233));
}

function selectionSet() {
  const baseRun = DATA.runs.dire ? "dire" : DATA.runs.spectral ? "spectral" : current;
  const base = DATA.runs[baseRun].nodes;
  if (visibleCount >= base.length) return new Set(base.map(n => n.idx));
  const groups = Object.keys(colors());
  const buckets = new Map(groups.map(group => [group, []]));
  for (const node of base) {
    if (!buckets.has(node.group)) buckets.set(node.group, []);
    buckets.get(node.group).push(node);
  }
  for (const nodes of buckets.values()) nodes.sort((a, b) => nodeScore(a.idx) - nodeScore(b.idx));
  const selected = [];
  let offset = 0;
  while (selected.length < visibleCount) {
    let added = false;
    for (const group of buckets.keys()) {
      const node = buckets.get(group)[offset];
      if (node && selected.length < visibleCount) {
        selected.push(node.idx);
        added = true;
      }
    }
    if (!added) break;
    offset += 1;
  }
  return new Set(selected);
}

function nodesFor(runKey, selected = selectionSet()) {
  return DATA.runs[runKey].nodes.filter(n => selected.has(n.idx));
}

function edgesFor(runKey, selected = selectionSet()) {
  return DATA.edges.filter(e => selected.has(e.source) && selected.has(e.target));
}

function graphFor(runKey, selected = selectionSet()) {
  return { nodes: nodesFor(runKey, selected), edges: edgesFor(runKey, selected) };
}

function componentStats(nodes, edges) {
  const offsets = new Map(nodes.map((node, index) => [node.idx, index]));
  const parent = nodes.map((_, index) => index);
  const rank = nodes.map(() => 0);
  function find(x) {
    while (parent[x] !== x) {
      parent[x] = parent[parent[x]];
      x = parent[x];
    }
    return x;
  }
  function union(a, b) {
    let rootA = find(a);
    let rootB = find(b);
    if (rootA === rootB) return;
    if (rank[rootA] < rank[rootB]) [rootA, rootB] = [rootB, rootA];
    parent[rootB] = rootA;
    if (rank[rootA] === rank[rootB]) rank[rootA] += 1;
  }
  for (const edge of edges) {
    const source = offsets.get(edge.source);
    const target = offsets.get(edge.target);
    if (source !== undefined && target !== undefined) union(source, target);
  }
  const counts = new Map();
  for (let i = 0; i < nodes.length; i++) {
    const root = find(i);
    counts.set(root, (counts.get(root) || 0) + 1);
  }
  return {
    components: counts.size,
    largest: Math.max(0, ...counts.values()),
  };
}

function meanEdgeLength(nodes, edges) {
  const byIdx = new Map(nodes.map(node => [node.idx, node]));
  let total = 0;
  let count = 0;
  for (const edge of edges) {
    const source = byIdx.get(edge.source);
    const target = byIdx.get(edge.target);
    if (!source || !target) continue;
    total += Math.hypot(target.x - source.x, target.y - source.y);
    count += 1;
  }
  return count ? total / count : NaN;
}

function boundsFor(runKeys) {
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  const selected = selectionSet();
  for (const runKey of runKeys) {
    for (const n of nodesFor(runKey, selected)) {
      minX = Math.min(minX, n.x);
      maxX = Math.max(maxX, n.x);
      minY = Math.min(minY, n.y);
      maxY = Math.max(maxY, n.y);
    }
  }
  if (!Number.isFinite(minX)) return { minX: -1, maxX: 1, minY: -1, maxY: 1 };
  return { minX, maxX, minY, maxY };
}

function fit() {
  if (!DATA || !current) return;
  const r = rect();
  const b = boundsFor([current]);
  const pad = 56;
  const sx = (r.width - 2 * pad) / (b.maxX - b.minX || 1);
  const sy = (r.height - 2 * pad) / (b.maxY - b.minY || 1);
  transform.scale = Math.min(sx, sy);
  transform.tx = pad - b.minX * transform.scale + (r.width - 2 * pad - (b.maxX - b.minX) * transform.scale) / 2;
  transform.ty = r.height - pad + b.minY * transform.scale - (r.height - 2 * pad - (b.maxY - b.minY) * transform.scale) / 2;
}

function screen(node, viewport = null, b = null) {
  if (!viewport) {
    return {
      x: node.x * transform.scale + transform.tx,
      y: -node.y * transform.scale + transform.ty,
    };
  }
  const pad = 44;
  const padTop = 72;
  const padBottom = 36;
  const plotW = viewport.w - 2 * pad;
  const plotH = viewport.h - padTop - padBottom;
  const scale = Math.min(
    plotW / (b.maxX - b.minX || 1),
    plotH / (b.maxY - b.minY || 1),
  );
  return {
    x: viewport.x + pad + (node.x - b.minX) * scale + (plotW - (b.maxX - b.minX) * scale) / 2,
    y: viewport.y + viewport.h - padBottom - (node.y - b.minY) * scale - (plotH - (b.maxY - b.minY) * scale) / 2,
  };
}

function drawPanelFrame(viewport, label, runKey) {
  ctx.fillStyle = "#ffffff";
  ctx.strokeStyle = "#cbd3de";
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.roundRect(viewport.x, viewport.y, viewport.w, viewport.h, 8);
  ctx.fill();
  ctx.stroke();
  ctx.fillStyle = "#151922";
  ctx.font = "600 15px -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif";
  ctx.fillText(label, viewport.x + 16, viewport.y + 26);
  ctx.fillStyle = "#5d6776";
  ctx.font = "12px -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif";
  const m = DATA.metrics[runKey] || {};
  ctx.fillText(`stress ${fmt(m.stress)}  mean edge ${fmt(m.meanEdgeLength)}`, viewport.x + 16, viewport.y + 45);
}

function drawRun(runKey, options = {}) {
  const graph = graphFor(runKey);
  const nodesByIdx = new Map(graph.nodes.map(n => [n.idx, n]));
  const viewport = options.viewport || null;
  const b = options.bounds || null;

  if (drawEdges) {
    ctx.lineCap = "round";
    for (const e of graph.edges) {
      if (!drawBridges && e.kind === "bridge") continue;
      const source = nodesByIdx.get(e.source);
      const target = nodesByIdx.get(e.target);
      if (!source || !target) continue;
      const a = screen(source, viewport, b);
      const c = screen(target, viewport, b);
      ctx.strokeStyle = e.kind === "bridge" ? "rgba(122, 85, 13, 0.58)" : "rgba(37, 48, 65, 0.13)";
      ctx.lineWidth = e.kind === "bridge" ? 1.35 : 0.65;
      ctx.beginPath();
      ctx.moveTo(a.x, a.y);
      ctx.lineTo(c.x, c.y);
      ctx.stroke();
    }
  }

  for (const n of graph.nodes) {
    const p = screen(n, viewport, b);
    ctx.fillStyle = colors()[n.group] || "#555";
    ctx.beginPath();
    ctx.arc(p.x, p.y, options.pointRadius ?? 3.1, 0, Math.PI * 2);
    ctx.fill();
    if (drawLabels && (graph.nodes.length <= 120 || n.idx % 25 === 0)) {
      ctx.fillStyle = "#222832";
      ctx.font = "11px -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif";
      ctx.fillText(n.name, p.x + 5, p.y - 5);
    }
  }
}

function drawCompare() {
  const r = rect();
  const gap = 14;
  const top = 18;
  const h = r.height - 36;
  const w = (r.width - 36 - gap) / 2;
  const left = { x: 18, y: top, w, h };
  const right = { x: 18 + w + gap, y: top, w, h };
  const leftRun = DATA.runs.spectral ? "spectral" : DATA.order[0];
  const spectralBounds = boundsFor([leftRun]);
  const currentBounds = boundsFor([current]);
  drawPanelFrame(left, DATA.metrics[leftRun]?.name || leftRun, leftRun);
  drawPanelFrame(right, DATA.metrics[current]?.name || current, current);
  drawRun(leftRun, { viewport: left, bounds: spectralBounds, pointRadius: 2.8 });
  drawRun(current, { viewport: right, bounds: currentBounds, pointRadius: 2.8 });
}

function draw() {
  const r = rect();
  ctx.clearRect(0, 0, r.width, r.height);
  if (!DATA || !current) return;
  if (mode === "compare" && DATA.order.length > 1) {
    drawCompare();
  } else {
    drawRun(current);
  }
}

function setRun(run) {
  current = run;
  updateUi();
  fit();
  draw();
}

function setMode(nextMode) {
  mode = nextMode;
  updateUi();
  fit();
  draw();
}

function updateUi() {
  if (!DATA || !current) return;
  document.querySelectorAll("button.run").forEach(b => b.classList.toggle("active", b.dataset.run === current));
  document.querySelectorAll("#modeButtons button").forEach(b => b.classList.toggle("active", b.dataset.mode === mode));
  const selectedSet = selectionSet();
  const data = graphFor(current, selectedSet);
  const m = DATA.metrics[current] || {};
  const components = componentStats(data.nodes, data.edges);
  const edgeKinds = data.edges.reduce((acc, edge) => {
    acc[edge.kind] = (acc[edge.kind] || 0) + 1;
    return acc;
  }, {});
  const groupCounts = data.nodes.reduce((acc, node) => {
    acc[node.group] = (acc[node.group] || 0) + 1;
    return acc;
  }, {});
  document.getElementById("nodeCount").textContent = data.nodes.length.toLocaleString();
  document.getElementById("edgeCount").textContent = data.edges.length.toLocaleString();
  document.getElementById("stress").textContent = fmt(m.stress);
  document.getElementById("meanEdge").textContent = fmt(meanEdgeLength(data.nodes, data.edges));
  document.getElementById("components").textContent = components.components.toLocaleString();
  document.getElementById("largest").textContent = components.largest.toLocaleString();
  document.getElementById("localEdges").textContent = (edgeKinds.local || 0).toLocaleString();
  document.getElementById("bridgeEdges").textContent = (edgeKinds.bridge || 0).toLocaleString();
  document.getElementById("edgeLegendLocal").textContent = (edgeKinds.local || 0).toLocaleString();
  document.getElementById("edgeLegendBridge").textContent = (edgeKinds.bridge || 0).toLocaleString();
  document.getElementById("activeBadge").textContent = `active: ${DATA.activeRun}`;
  document.getElementById("loadedBadge").textContent = `loaded: ${DATA.totalNodes.toLocaleString()}`;
  document.getElementById("vertexOutput").textContent = visibleCount >= DATA.totalNodes
    ? `All ${DATA.totalNodes.toLocaleString()}`
    : visibleCount.toLocaleString();
  for (const [group] of Object.entries(DATA.groups)) {
    const output = document.getElementById(`legendCount-${idPart(group)}`);
    if (output) output.textContent = (groupCounts[group] || 0).toLocaleString();
  }
}

function rebuildRuns() {
  const list = document.getElementById("runList");
  list.replaceChildren();
  for (const run of DATA.order) {
    const m = DATA.metrics[run] || {};
    const button = document.createElement("button");
    button.className = "run";
    button.dataset.run = run;
    button.innerHTML = `<strong>${m.name || run}</strong><span>${m.description || "Stored coordinates"}</span>`;
    button.onclick = () => setRun(run);
    list.appendChild(button);
  }
}

function rebuildLegend() {
  const legend = document.getElementById("legend");
  legend.replaceChildren();
  for (const [group, count] of Object.entries(DATA.groups).sort()) {
    const row = document.createElement("div");
    row.className = "legend-row";
    row.innerHTML = `<span class="legend-label"><span class="swatch" style="background:${colors()[group]}"></span>${group}</span><span id="legendCount-${idPart(group)}">${count}</span>`;
    legend.appendChild(row);
  }
}

function applyData(nextData, statusText) {
  DATA = nextData;
  current = DATA.activeRun in DATA.runs ? DATA.activeRun : DATA.order[0];
  visibleCount = DATA.totalNodes;
  nodeQuery.value = DATA.sample.nodeQuery;
  edgeQuery.value = DATA.sample.edgeQuery;
  queryStatus.textContent = statusText;
  hud.innerHTML = `<strong>${DATA.metrics[current]?.name || current}</strong><span>${statusText}</span>`;
  const vertexControl = document.getElementById("vertexCount");
  vertexControl.max = Math.max(1, DATA.totalNodes);
  vertexControl.value = Math.max(1, DATA.totalNodes);
  rebuildRuns();
  rebuildLegend();
  updateUi();
  resize();
}

async function loadDefault() {
  queryStatus.textContent = "Loading from Neo4j.";
  const response = await fetch("./api/data", { cache: "no-store" });
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.message || JSON.stringify(payload));
  applyData(payload, `Default sample loaded ${payload.totalNodes.toLocaleString()} nodes and ${payload.totalEdges.toLocaleString()} edges.`);
}

async function runCypher() {
  queryStatus.textContent = "Running Cypher.";
  const body = new URLSearchParams();
  body.set("nodeQuery", nodeQuery.value);
  body.set("edgeQuery", edgeQuery.value);
  const response = await fetch("./api/query", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });
  const text = await response.text();
  let payload;
  try {
    payload = JSON.parse(text);
  } catch {
    throw new Error(text || `HTTP ${response.status}`);
  }
  if (!response.ok) throw new Error(payload.message || text);
  applyData(payload, `Loaded ${payload.totalNodes.toLocaleString()} nodes and ${payload.totalEdges.toLocaleString()} edges.`);
}

function nearest(clientX, clientY) {
  if (!DATA || mode === "compare") return null;
  const r = rect();
  const x = clientX - r.left;
  const y = clientY - r.top;
  let best = null;
  let bestD = 12 * 12;
  for (const n of graphFor(current).nodes) {
    const p = screen(n);
    const d = (p.x - x) ** 2 + (p.y - y) ** 2;
    if (d < bestD) { bestD = d; best = n; }
  }
  return best;
}

function resize() {
  const dpr = window.devicePixelRatio || 1;
  const r = rect();
  canvas.width = Math.floor(r.width * dpr);
  canvas.height = Math.floor(r.height * dpr);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  fit();
  draw();
}

document.querySelectorAll("#modeButtons button").forEach(button => {
  button.onclick = () => setMode(button.dataset.mode);
});
document.getElementById("reset").onclick = () => { fit(); draw(); };
document.getElementById("edges").onchange = e => { drawEdges = e.target.checked; draw(); };
document.getElementById("bridges").onchange = e => { drawBridges = e.target.checked; draw(); };
document.getElementById("labels").onchange = e => { drawLabels = e.target.checked; draw(); };
document.getElementById("runCypher").onclick = () => runCypher().catch(showError);
document.getElementById("resetCypher").onclick = () => loadDefault().catch(showError);
document.getElementById("vertexCount").oninput = e => {
  visibleCount = Number(e.target.value);
  updateUi();
  fit();
  draw();
};

canvas.addEventListener("mousedown", e => { dragging = true; last = { x: e.clientX, y: e.clientY }; });
window.addEventListener("mouseup", () => { dragging = false; last = null; });
window.addEventListener("mousemove", e => {
  if (dragging && last && mode !== "compare") {
    transform.tx += e.clientX - last.x;
    transform.ty += e.clientY - last.y;
    last = { x: e.clientX, y: e.clientY };
    draw();
    return;
  }
  const n = nearest(e.clientX, e.clientY);
  if (n) {
    hud.innerHTML = `<strong>${n.name}</strong><span>${n.group} | idx ${n.idx} | ${current} | x ${fmt(n.x, 4)} y ${fmt(n.y, 4)}</span>`;
  }
});
canvas.addEventListener("wheel", e => {
  if (mode === "compare") return;
  e.preventDefault();
  const r = rect();
  const mx = e.clientX - r.left;
  const my = e.clientY - r.top;
  const factor = e.deltaY < 0 ? 1.12 : 0.89;
  transform.tx = mx - (mx - transform.tx) * factor;
  transform.ty = my - (my - transform.ty) * factor;
  transform.scale *= factor;
  draw();
}, { passive: false });

function showError(error) {
  queryStatus.textContent = error.message;
  hud.innerHTML = `<strong>Cypher error</strong><span>${error.message}</span>`;
}

window.addEventListener("resize", resize);
resize();
loadDefault().catch(showError);
