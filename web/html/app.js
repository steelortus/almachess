// Minimal test frontend for AlmaChess.
// All calls are same-origin — nginx proxies /api, /notation, /ai, /health
// to the three backend containers defined in docker-compose.yml.

// Single filled glyph per piece type — the side is expressed via CSS colour
// (piece-white / piece-black) so both colours stay solid regardless of the
// square background.
const PIECE_GLYPHS = {
  k: "\u265A", q: "\u265B", r: "\u265C", b: "\u265D", n: "\u265E", p: "\u265F",
};

const FILES = ["a","b","c","d","e","f","g","h"];
const boardEl  = document.getElementById("board");
const fenEl    = document.getElementById("fen-input");
const pgnEl    = document.getElementById("pgn-input");
const statusEl = document.getElementById("status");
const logEl    = document.getElementById("log");

let selected = null;          // algebraic square, e.g. "e2"
let legalTargets = new Set(); // destinations for the selected square
let currentBoard = {};        // square -> piece char
let fenDirty = false;         // user edited fen textarea, don't overwrite

// ---------- logging --------------------------------------------------------

function log(msg, cls = "") {
  const line = document.createElement("div");
  if (cls) line.className = cls;
  line.textContent = `[${new Date().toLocaleTimeString()}] ${msg}`;
  logEl.prepend(line);
}

// ---------- fetch helpers --------------------------------------------------

async function api(method, url, body) {
  const opts = { method, headers: {} };
  if (body !== undefined) {
    opts.headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(body);
  }
  const res = await fetch(url, opts);
  const text = await res.text();
  const data = text ? tryJson(text) : null;
  if (!res.ok) {
    const err = (data && data.error) || text || res.statusText;
    throw new Error(`${res.status} ${err}`);
  }
  return data;
}

function tryJson(s) { try { return JSON.parse(s); } catch { return s; } }

// ---------- board rendering ------------------------------------------------

function parseFen(fen) {
  const rows = fen.split(" ")[0].split("/");
  const squares = {};
  for (let r = 0; r < 8; r++) {
    let file = 0;
    for (const ch of rows[r]) {
      if (/\d/.test(ch)) { file += parseInt(ch, 10); continue; }
      const rank = 8 - r;
      squares[FILES[file] + rank] = ch;
      file++;
    }
  }
  return squares;
}

function buildBoard() {
  boardEl.innerHTML = "";
  for (let rank = 8; rank >= 1; rank--) {
    for (let f = 0; f < 8; f++) {
      const sq = FILES[f] + rank;
      const cell = document.createElement("div");
      cell.className = "sq " + (((rank + f) % 2 === 0) ? "dark" : "light");
      cell.dataset.sq = sq;
      cell.addEventListener("click",     () => onSquareClick(sq));
      cell.addEventListener("dragstart", (e) => onDragStart(e, sq));
      cell.addEventListener("dragover",  (e) => onDragOver(e, sq));
      cell.addEventListener("dragleave", (e) => onDragLeave(e, sq));
      cell.addEventListener("drop",      (e) => onDrop(e, sq));
      cell.addEventListener("dragend",   (e) => onDragEnd(e, sq));
      boardEl.appendChild(cell);
    }
  }
}

function renderBoard() {
  for (const cell of boardEl.children) {
    const sq = cell.dataset.sq;
    const piece = currentBoard[sq];
    cell.classList.remove("selected", "legal", "capture", "piece-white", "piece-black", "drop-target");
    if (piece) {
      cell.textContent = PIECE_GLYPHS[piece.toLowerCase()];
      cell.classList.add(piece === piece.toUpperCase() ? "piece-white" : "piece-black");
      cell.setAttribute("draggable", "true");
    } else {
      cell.textContent = "";
      cell.removeAttribute("draggable");
    }
    if (selected === sq) cell.classList.add("selected");
    if (legalTargets.has(sq)) {
      cell.classList.add("legal");
      if (piece) cell.classList.add("capture");
    }
  }
}

// ---------- state updates --------------------------------------------------

async function refreshState() {
  const state = await api("GET", "/api/game");
  applyState(state);
  await refreshPgn();
}

function applyState(state) {
  currentBoard = parseFen(state.fen);
  if (!fenDirty) fenEl.value = state.fen;
  statusEl.textContent = state.gameOver
    ? `${state.status} (Ende)`
    : state.status;
  selected = null;
  legalTargets = new Set();
  renderBoard();
}

async function refreshPgn() {
  try {
    const res = await api("GET", "/api/pgn");
    pgnEl.value = res.pgn || "";
  } catch (_) { /* ignore — PGN is optional */ }
}

// ---------- clipboard ------------------------------------------------------

async function copyToClipboard(text, label) {
  try {
    await navigator.clipboard.writeText(text);
    log(`${label} kopiert`, "ok");
  } catch (e) {
    log(`Kopieren fehlgeschlagen: ${e.message}`, "err");
  }
}

// Drop PGN tag pairs (e.g. [Event "AlmaChess"]) and keep only the movetext.
function stripPgnHeaders(pgn) {
  if (!pgn) return "";
  const blankSep = pgn.search(/\r?\n\s*\r?\n/);
  const body = blankSep >= 0 ? pgn.slice(blankSep) : pgn;
  return body
    .split(/\r?\n/)
    .filter(line => !line.trim().startsWith("["))
    .join("\n")
    .trim();
}

// ---------- interactions ---------------------------------------------------

async function onSquareClick(sq) {
  if (selected && legalTargets.has(sq)) {
    const from = selected;
    try {
      const state = await api("POST", "/api/game/move", { from, to: sq });
      fenDirty = false;
      applyState(state);
      await refreshPgn();
      log(`Zug ${from}${sq}`, "ok");
    } catch (e) {
      log(`Zug abgelehnt: ${e.message}`, "err");
    }
    return;
  }
  if (!currentBoard[sq]) { clearSelection(); return; }
  try {
    const res = await api("GET", `/api/game/legal-moves?from=${sq}`);
    const targets = (res.moves || []).map(uci => uci.slice(2, 4));
    if (targets.length === 0) { clearSelection(); return; }
    selected = sq;
    legalTargets = new Set(targets);
    renderBoard();
  } catch (e) {
    log(`legal-moves Fehler: ${e.message}`, "err");
  }
}

function clearSelection() {
  selected = null;
  legalTargets = new Set();
  renderBoard();
}

// ---------- drag & drop ----------------------------------------------------

let dragFrom = null;

async function onDragStart(e, sq) {
  if (!currentBoard[sq]) { e.preventDefault(); return; }
  dragFrom = sq;
  selected = sq;
  legalTargets = new Set();
  try {
    e.dataTransfer.effectAllowed = "move";
    e.dataTransfer.setData("text/plain", sq);
  } catch (_) { /* some browsers are picky about dataTransfer */ }

  // Build a custom drag image that is just the piece glyph, no square.
  const src = e.currentTarget;
  const cs  = getComputedStyle(src);
  const size = src.getBoundingClientRect();
  const ghost = document.createElement("div");
  ghost.className = "drag-ghost";
  ghost.textContent = src.textContent;
  ghost.style.width     = `${size.width}px`;
  ghost.style.height    = `${size.height}px`;
  ghost.style.fontSize  = cs.fontSize;
  ghost.style.fontFamily= cs.fontFamily;
  ghost.style.color     = cs.color;
  ghost.style.textShadow= cs.textShadow;
  document.body.appendChild(ghost);
  try { e.dataTransfer.setDragImage(ghost, size.width / 2, size.height / 2); }
  catch (_) { /* older browsers */ }
  setTimeout(() => ghost.remove(), 0);

  // Hide the glyph on the origin while dragging — .dragging sets color:transparent.
  src.classList.add("dragging");

  // Fetch legal targets asynchronously — the drag is already in flight,
  // highlights appear a moment later which is fine.
  try {
    const res = await api("GET", `/api/game/legal-moves?from=${sq}`);
    const targets = (res.moves || []).map(uci => uci.slice(2, 4));
    legalTargets = new Set(targets);
    renderBoard();
    // re-apply .dragging after renderBoard wiped classes
    const src = boardEl.querySelector(`.sq[data-sq="${sq}"]`);
    if (src) src.classList.add("dragging");
  } catch (err) {
    log(`legal-moves: ${err.message}`, "err");
  }
}

function onDragOver(e, sq) {
  if (!dragFrom || !legalTargets.has(sq)) return;
  e.preventDefault();
  e.dataTransfer.dropEffect = "move";
  e.currentTarget.classList.add("drop-target");
}

function onDragLeave(e, _sq) {
  e.currentTarget.classList.remove("drop-target");
}

async function onDrop(e, sq) {
  e.preventDefault();
  const from = dragFrom || e.dataTransfer.getData("text/plain");
  dragFrom = null;
  if (!from || !legalTargets.has(sq)) { clearSelection(); return; }
  try {
    const state = await api("POST", "/api/game/move", { from, to: sq });
    fenDirty = false;
    applyState(state);
    await refreshPgn();
    log(`Zug ${from}${sq}`, "ok");
  } catch (err) {
    log(`Zug abgelehnt: ${err.message}`, "err");
    clearSelection();
  }
}

function onDragEnd(_e, _sq) {
  dragFrom = null;
  // clear any leftover highlights if drop didn't fire (e.g. dropped off-board)
  for (const cell of boardEl.children) {
    cell.classList.remove("drop-target", "dragging");
  }
  if (selected && legalTargets.size > 0) {
    // user may still want click-to-move — keep highlights
    renderBoard();
    const src = boardEl.querySelector(`.sq[data-sq="${selected}"]`);
    if (src) src.classList.add("selected");
  } else {
    clearSelection();
  }
}

// ---------- button wiring --------------------------------------------------

document.getElementById("btn-reset").addEventListener("click", async () => {
  try {
    fenDirty = false;
    applyState(await api("POST", "/api/game/reset"));
    await refreshPgn();
    log("Neues Spiel", "ok");
  } catch (e) { log(e.message, "err"); }
});

document.getElementById("btn-undo").addEventListener("click", async () => {
  try {
    fenDirty = false;
    applyState(await api("POST", "/api/game/undo"));
    await refreshPgn();
    log("Undo", "ok");
  } catch (e) { log(e.message, "err"); }
});

document.getElementById("btn-redo").addEventListener("click", async () => {
  try {
    fenDirty = false;
    applyState(await api("POST", "/api/game/redo"));
    await refreshPgn();
    log("Redo", "ok");
  } catch (e) { log(e.message, "err"); }
});

document.getElementById("btn-ai").addEventListener("click", async () => {
  const depth = parseInt(document.getElementById("ai-depth").value, 10) || 2;
  try {
    const res = await api("POST", "/api/game/ai-move", { depth });
    fenDirty = false;
    applyState(res.state);
    await refreshPgn();
    log(`AI-Zug: ${res.move}`, "ok");
  } catch (e) { log(e.message, "err"); }
});

// FEN panel: edit freely, load, or copy current value
fenEl.addEventListener("input", () => { fenDirty = true; });

document.getElementById("btn-fen-copy").addEventListener("click", () => {
  copyToClipboard(fenEl.value, "FEN");
});

document.getElementById("btn-fen-load").addEventListener("click", async () => {
  const fen = fenEl.value.trim();
  if (!fen) return;
  try {
    await api("POST", "/api/fen", { fen });
    fenDirty = false;
    await refreshState();
    log("FEN geladen", "ok");
  } catch (e) { log(e.message, "err"); }
});

document.getElementById("btn-pgn-copy").addEventListener("click", () => {
  copyToClipboard(stripPgnHeaders(pgnEl.value), "PGN");
});

document.getElementById("btn-pgn-load").addEventListener("click", async () => {
  const pgn = pgnEl.value.trim();
  if (!pgn) return;
  try {
    await api("POST", "/api/pgn", { pgn });
    fenDirty = false;
    await refreshState();
    log("PGN geladen", "ok");
  } catch (e) { log(e.message, "err"); }
});

// ---------- health polling -------------------------------------------------

async function pingHealth() {
  const targets = [
    { key: "api",      url: "/health" },
    { key: "notation", url: "/notation/health" },
    { key: "ai",       url: "/ai/health" },
  ];
  for (const t of targets) {
    const el = document.querySelector(`.health span[data-svc="${t.key}"]`);
    try {
      const res = await fetch(t.url, { cache: "no-store" });
      el.classList.toggle("up",   res.ok);
      el.classList.toggle("down", !res.ok);
    } catch {
      el.classList.remove("up");
      el.classList.add("down");
    }
  }
}

// ---------- boot -----------------------------------------------------------

buildBoard();
refreshState().catch(e => log(`init: ${e.message}`, "err"));
pingHealth();
setInterval(pingHealth, 5000);
