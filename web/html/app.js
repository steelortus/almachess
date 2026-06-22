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
const STARTING_COUNTS = { P:8, N:2, B:2, R:2, Q:1, p:8, n:2, b:2, r:2, q:1 };
// Order in which captured pieces are listed (lowest value first).
const CAPTURE_ORDER = ["p","n","b","r","q"];
const boardEl  = document.getElementById("board");
const fenEl    = document.getElementById("fen-input");
const pgnEl    = document.getElementById("pgn-input");
const statusEl = document.getElementById("status");
const logEl    = document.getElementById("log");
const capturesTopEl    = document.getElementById("captures-top");
const capturesBottomEl = document.getElementById("captures-bottom");
const materialTopEl    = document.getElementById("material-top");
const materialBottomEl = document.getElementById("material-bottom");
const PIECE_VALUES = { p: 1, n: 3, b: 3, r: 5, q: 9 };

let selected = null;          // algebraic square, e.g. "e2"
let legalTargets = new Set(); // destinations for the selected square
let currentBoard = {};        // square -> piece char
let fenDirty = false;         // user edited fen textarea, don't overwrite
let lastMoveSquares = null;   // { from: "e2", to: "e4" } | null
let currentTurn = "white";    // side to move from last server state
let currentGameOver = false;
let boardFlipped = false;     // true = black at the bottom
let resultModalShownFor = null; // dedup key for the result modal
let manualGameOver = false;     // resign/draw flag — pure client-side

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
  const ranks = boardFlipped ? [1,2,3,4,5,6,7,8] : [8,7,6,5,4,3,2,1];
  const files = boardFlipped ? [7,6,5,4,3,2,1,0] : [0,1,2,3,4,5,6,7];
  // Edge files/ranks where labels are placed. Flipping swaps them so labels
  // always sit on the visible bottom and left edges.
  const fileEdgeRank = boardFlipped ? 8 : 1;
  const rankEdgeFile = boardFlipped ? "h" : "a";
  for (const rank of ranks) {
    for (const f of files) {
      const sq = FILES[f] + rank;
      const cell = document.createElement("div");
      cell.className = "sq " + (((rank + f) % 2 === 0) ? "dark" : "light");
      cell.dataset.sq = sq;
      const pieceGlyph = document.createElement("span");
      pieceGlyph.className = "piece-glyph";
      cell.appendChild(pieceGlyph);
      if (rank === fileEdgeRank) {
        const fileLabel = document.createElement("span");
        fileLabel.className = "coord coord-file";
        fileLabel.textContent = FILES[f];
        cell.appendChild(fileLabel);
      }
      if (FILES[f] === rankEdgeFile) {
        const rankLabel = document.createElement("span");
        rankLabel.className = "coord coord-rank";
        rankLabel.textContent = String(rank);
        cell.appendChild(rankLabel);
      }
      cell.addEventListener("click",     () => onSquareClick(sq));
      cell.addEventListener("dragstart", (e) => onDragStart(e, sq));
      cell.addEventListener("dragover",  (e) => onDragOver(e, sq));
      cell.addEventListener("dragleave", (e) => onDragLeave(e, sq));
      cell.addEventListener("drop",      (e) => onDrop(e, sq));
      cell.addEventListener("dragend",   (e) => onDragEnd(e, sq));
      boardEl.appendChild(cell);
    }
  }
  syncCoordsVisibility();
}

function syncCoordsVisibility() {
  boardEl.classList.toggle("show-coords", !!(analysis && analysis.enabled));
}

function setBoardFlipped(flipped) {
  if (boardFlipped === flipped) return;
  boardFlipped = flipped;
  buildBoard();
  renderBoard();
  renderClocks();
}

function renderBoard() {
  for (const cell of boardEl.children) {
    const sq = cell.dataset.sq;
    const piece = currentBoard[sq];
    const pieceGlyph = cell.querySelector(".piece-glyph");
    cell.classList.remove("selected", "legal", "capture", "piece-white", "piece-black", "drop-target", "last-move");
    if (piece) {
      pieceGlyph.textContent = PIECE_GLYPHS[piece.toLowerCase()];
      cell.classList.add(piece === piece.toUpperCase() ? "piece-white" : "piece-black");
      cell.setAttribute("draggable", "true");
    } else {
      pieceGlyph.textContent = "";
      cell.removeAttribute("draggable");
    }
    if (lastMoveSquares && (lastMoveSquares.from === sq || lastMoveSquares.to === sq)) {
      cell.classList.add("last-move");
    }
    if (selected === sq) cell.classList.add("selected");
    if (legalTargets.has(sq)) {
      cell.classList.add("legal");
      if (piece) cell.classList.add("capture");
    }
  }
  renderCaptures();
}

function renderCaptures() {
  const counts = {};
  for (const piece of Object.values(currentBoard)) {
    counts[piece] = (counts[piece] || 0) + 1;
  }
  const missing = (p) => Math.max(0, (STARTING_COUNTS[p] || 0) - (counts[p] || 0));

  const blackLost = CAPTURE_ORDER.flatMap(t => Array(missing(t)).fill(PIECE_GLYPHS[t]));
  const whiteLost = CAPTURE_ORDER.flatMap(t => Array(missing(t.toUpperCase())).fill(PIECE_GLYPHS[t]));
  capturesTopEl.textContent    = (boardFlipped ? whiteLost : blackLost).join("");
  capturesBottomEl.textContent = (boardFlipped ? blackLost : whiteLost).join("");

  // Material score: sum captured-piece values per side. Positive means that
  // side captured more material than it lost. Diff is shown on the leading
  // side only, e.g. "+3".
  const sumValues = (arr) => arr.reduce((s, t) => s + (PIECE_VALUES[t] || 0), 0);
  const blackCapturedValue = sumValues(CAPTURE_ORDER.flatMap(
    t => Array(missing(t.toUpperCase())).fill(t)));
  const whiteCapturedValue = sumValues(CAPTURE_ORDER.flatMap(
    t => Array(missing(t)).fill(t)));
  const diff = whiteCapturedValue - blackCapturedValue;
  const whiteText = diff > 0 ? `+${diff}` : "";
  const blackText = diff < 0 ? `+${-diff}` : "";
  // Top label belongs to the side at the top of the board.
  const topIsWhite = boardFlipped;
  if (materialTopEl)    materialTopEl.textContent    = topIsWhite ? whiteText : blackText;
  if (materialBottomEl) materialBottomEl.textContent = topIsWhite ? blackText : whiteText;
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
  currentTurn = state.turn || "white";
  currentGameOver = !!state.gameOver;
  selected = null;
  legalTargets = new Set();
  lastMoveSquares = parseLastMove(state.lastMove);
  renderBoard();
  checkGameResult(state);
}

function parseLastMove(uci) {
  if (typeof uci !== "string" || uci.length < 4) return null;
  return { from: uci.slice(0, 2), to: uci.slice(2, 4) };
}

function lastMoveFromMoves(moves) {
  if (!Array.isArray(moves) || moves.length === 0) return null;
  return parseLastMove(moves[moves.length - 1]);
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

function legalMovesApiUrl(sq) {
  const prefix = isLichessSessionActive() ? "/api/lichess" : "/api/game";
  return `${prefix}/legal-moves?from=${sq}`;
}

async function onSquareClick(sq) {
  if (setup.active) { setupSquareClick(sq); return; }
  if (selected && legalTargets.has(sq)) {
    if (clockGameOver()) { log("Zug abgelehnt: Zeit abgelaufen", "err"); return; }
    const from = selected;
    await attemptMove(from, sq, /* originEl */ boardEl.querySelector(`.sq[data-sq="${sq}"]`));
    return;
  }
  if (!currentBoard[sq]) { clearSelection(); return; }
  try {
    const res = await api("GET", legalMovesApiUrl(sq));
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
  if (setup.active) { setupDragStart(e, sq); return; }
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
  ghost.textContent = PIECE_GLYPHS[currentBoard[sq].toLowerCase()];
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
    const res = await api("GET", legalMovesApiUrl(sq));
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
  if (setup.active) { setupDragOver(e); return; }
  if (!dragFrom) return;
  // Always accept the drag while a piece is airborne. `dragover` only fires
  // on mouse movement, so if we gated on `legalTargets.has(sq)` here the user
  // couldn't drop when they picked up a piece, held it still over a target,
  // and waited for the legal-moves API to resolve. The drop handler does the
  // real legality check.
  e.preventDefault();
  e.dataTransfer.dropEffect = "move";
  if (legalTargets.has(sq)) e.currentTarget.classList.add("drop-target");
}

function onDragLeave(e, _sq) {
  if (setup.active) { setupDragLeave(e); return; }
  e.currentTarget.classList.remove("drop-target");
}

async function onDrop(e, sq) {
  if (setup.active) { setupDrop(e, sq); return; }
  e.preventDefault();
  const from = dragFrom || e.dataTransfer.getData("text/plain");
  dragFrom = null;
  if (!from || !legalTargets.has(sq)) { clearSelection(); return; }
  if (clockGameOver()) { log("Zug abgelehnt: Zeit abgelaufen", "err"); clearSelection(); return; }
  await attemptMove(from, sq, e.currentTarget);
}

// Centralised move submission. Detects pawn promotion and shows the picker
// before sending the API request. Promotion char is sent lowercase to match
// the backend (MoveRequest.promotion: Option[String]).
async function attemptMove(from, to, anchorEl) {
  const piece = currentBoard[from];
  const isPromotion =
    (piece === "P" && to[1] === "8") || (piece === "p" && to[1] === "1");
  if (isPromotion) {
    const choice = await pickPromotion(anchorEl, piece === "P" ? "white" : "black");
    if (!choice) { clearSelection(); return; }
    await sendMove(from, to, choice);
  } else {
    await sendMove(from, to, null);
  }
}

async function sendMove(from, to, promotion) {
  const mover = currentTurn;
  const wasCapture = !!currentBoard[to]; // approximate: misses en-passant
  const body = promotion ? { from, to, promotion } : { from, to };
  if (isLichessSessionActive()) {
    await sendLichessMove(from, to, promotion, { mover, wasCapture });
    return;
  }
  try {
    const state = await api("POST", "/api/game/move", body);
    fenDirty = false;
    applyState(state);
    onClockMovePlayed(mover);
    await refreshPgn();
    log(`Zug ${from}${to}${promotion ? "=" + promotion.toUpperCase() : ""}`, "ok");
    const note = describeLocalStatus(state);
    if (note) log(note, "ok");
    playMoveSound(state, { capture: wasCapture, promotion: !!promotion });
    requestAnalysis(mover);
    maybeAutosave();
    maybeAutoAi("nach Spielerzug");
  } catch (e) {
    log(`Zug abgelehnt: ${e.message}`, "err");
    clearSelection();
  }
}

// Local game status → German message. The Controller emits:
//   "checkmate - white wins" / "stalemate" / "white is in check" / "white to move"
// We only return something for noteworthy states; "X to move" is silent.
function describeLocalStatus(state) {
  if (!state) return "";
  const status = String(state.status || "").toLowerCase();
  if (status.startsWith("checkmate")) {
    const winner = status.includes("white wins") ? "Weiß" : "Schwarz";
    return `Schachmatt — ${winner} gewinnt!`;
  }
  if (status === "stalemate") return "Patt!";
  if (status.includes("is in check")) {
    const side = status.startsWith("white") ? "Weiß" : "Schwarz";
    return `Schach! ${side} muss reagieren.`;
  }
  return "";
}

// Lichess session status → German message. The stream reports terminal
// states like "mate" / "draw" / "outoftime" / "resign". For non-terminal
// turns we synthesise a check warning from session.inCheck.
function describeLichessStatus(session) {
  if (!session) return "";
  if (session.inCheck && !session.gameOver) {
    const side = session.inCheck === "white" ? "Weiß" : "Schwarz";
    return `Schach! ${side} muss reagieren.`;
  }
  const status = String(session.status || "").toLowerCase();
  const winner = session.winner ? (session.winner === "white" ? "Weiß" : "Schwarz") : null;
  switch (status) {
    case "mate":      return winner ? `Schachmatt — ${winner} gewinnt!` : "Schachmatt!";
    case "stalemate": return "Patt!";
    case "draw":      return "Remis";
    case "outoftime": return winner ? `Zeit abgelaufen — ${winner} gewinnt!` : "Zeit abgelaufen";
    case "resign":    return winner ? `Aufgegeben — ${winner} gewinnt!` : "Aufgegeben";
    case "abort":     return "Spiel abgebrochen";
    default:          return "";
  }
}

// Pick the right sound based on the post-move state. Order matters: gameover
// trumps everything, then check, then promotion, then capture, then plain.
function playMoveSound(state, { capture = false, promotion = false } = {}) {
  if (state && state.gameOver)                      { playSound("gameover"); return; }
  const status = String((state && state.status) || "").toLowerCase();
  if (status.includes("check"))                      { playSound("check");    return; }
  if (promotion)                                    { playSound("promote");  return; }
  if (capture)                                      { playSound("capture");  return; }
  playSound("move");
}

function onDragEnd(_e, _sq) {
  if (setup.active) { setupDragEnd(); return; }
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
    resultModalShownFor = null;
    manualGameOver = false;
    applyState(await api("POST", "/api/game/reset"));
    await refreshPgn();
    resetClocks();
    renderClocks();
    analysis.prevEvalCp = null;
    if (analysis.enabled) requestAnalysis(null);
    log("Neues Spiel", "ok");
    syncStockfishUiState({ trigger: "reset" });
  } catch (e) { log(e.message, "err"); }
});

document.getElementById("btn-undo").addEventListener("click", async () => {
  try {
    fenDirty = false;
    applyState(await api("POST", "/api/game/undo"));
    await refreshPgn();
    log("Undo", "ok");
    if (timeSettings.enabled) log("Hinweis: Undo verändert die Uhr nicht.");
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
  if (isLichessSessionActive()) {
    log("AI-Zug ist im Lichess-Mensch-Modus deaktiviert", "err");
    return;
  }
  const fallbackDepth = parseInt(document.getElementById("ai-depth").value, 10) || 2;
  const ok = await runAiMove(fallbackDepth);
  // After the manual AI-move click, the auto-AI loop must keep going so
  // successive engine plies happen automatically (e.g. player chose black,
  // clicks "AI ziehen" to move on white's behalf, then the engine should
  // keep playing white when it's white's turn again).
  if (ok) syncStockfishUiState({ trigger: "ai-button" });
});

async function runAiMove(fallbackDepth) {
  if (clockGameOver()) { log("AI-Zug abgelehnt: Zeit abgelaufen", "err"); return false; }
  const mover = currentTurn;
  const payload = buildAiMovePayload(fallbackDepth, mover);
  try {
    const res = await api("POST", "/api/game/ai-move", payload);
    const uci = String(res.move || "");
    const to  = uci.length >= 4 ? uci.slice(2, 4) : null;
    const wasCapture = to ? !!currentBoard[to] : false;
    const wasPromotion = uci.length >= 5;
    fenDirty = false;
    applyState(res.state);
    onClockMovePlayed(mover);
    await refreshPgn();
    log(`AI-Zug: ${res.move}`, "ok");
    playMoveSound(res.state, { capture: wasCapture, promotion: wasPromotion });
    requestAnalysis(mover);
    maybeAutosave();
    return true;
  } catch (e) { log(e.message, "err"); return false; }
}

function buildAiMovePayload(fallbackDepth, aiSide) {
  // Stockfish payload is built from the central fishSettings state. Local
  // ChessAI ignores movetime/skill — it just uses the AI-panel depth.
  const payload = {};
  if (stockfishActive) {
    const cfg = effectiveFishConfig();
    if (cfg.depth    != null) payload.depth    = cfg.depth;
    if (cfg.movetime != null) payload.movetime = cfg.movetime;
    if (cfg.skill    != null) payload.skill    = cfg.skill;
    // Time-mode cap: never let the engine think longer than restMs/30 - 200ms.
    const cap = aiMovetimeCapMs(aiSide);
    if (cap != null) {
      payload.movetime = payload.movetime != null ? Math.min(payload.movetime, cap) : cap;
    }
  } else {
    payload.depth = fallbackDepth;
  }
  return payload;
}

// Map a single ELO value (100–3200) to Stockfish-friendly knobs.
// Low ratings are mostly skill-limited with shallow searches; high ratings
// use full skill, deep search and longer thinking time.
function eloToFishSettings(elo) {
  const e = Math.max(100, Math.min(3200, elo | 0));
  const t = (e - 100) / 3100; // 0..1
  const skill    = Math.max(0, Math.min(20, Math.round(t * 20)));
  const depth    = Math.max(1, Math.min(30, Math.round(1 + t * 29)));
  const movetime = Math.max(100, Math.min(5000, Math.round(100 + t * 4900)));
  return { skill, depth, movetime };
}

// FEN panel: edit freely, load, or copy current value
fenEl.addEventListener("input", () => { fenDirty = true; });

document.getElementById("btn-fen-copy").addEventListener("click", () => {
  copyToClipboard(fenEl.value, "FEN");
});

document.getElementById("btn-fen-load").addEventListener("click", async () => {
  const fen = fenEl.value.trim();
  if (!fen) return;
  try {
    resultModalShownFor = null;
    manualGameOver = false;
    analysis.prevEvalCp = null;
    await api("POST", "/api/fen", { fen });
    fenDirty = false;
    await refreshState();
    pauseClocksAfterLoad();
    if (analysis.enabled) requestAnalysis(null);
    log("FEN geladen", "ok");
    syncStockfishUiState({ trigger: "fen-load" });
  } catch (e) { log(e.message, "err"); }
});

document.getElementById("btn-pgn-copy").addEventListener("click", () => {
  copyToClipboard(stripPgnHeaders(pgnEl.value), "PGN");
});

document.getElementById("btn-pgn-load").addEventListener("click", async () => {
  const pgn = pgnEl.value.trim();
  if (!pgn) return;
  try {
    resultModalShownFor = null;
    manualGameOver = false;
    analysis.prevEvalCp = null;
    await api("POST", "/api/pgn", { pgn });
    fenDirty = false;
    await refreshState();
    pauseClocksAfterLoad();
    if (analysis.enabled) requestAnalysis(null);
    log("PGN geladen", "ok");
    syncStockfishUiState({ trigger: "pgn-load" });
  } catch (e) { log(e.message, "err"); }
});

// ---------- persistence ----------------------------------------------------

const dbBadgeEl       = document.getElementById("db-badge");
const dbMenuEl        = document.getElementById("db-menu");
const dbMenuToggle    = document.getElementById("btn-db-menu");
const dbMenuBackendEl = document.getElementById("db-menu-backend");
const dbIdEl          = document.getElementById("db-game-id");
const dbListEl        = document.getElementById("db-list");
let persistenceEnabled = false;

function currentGameId() {
  const v = (dbIdEl.value || "").trim();
  if (!v) {
    log("Bitte Game ID eingeben", "err");
    return null;
  }
  return v;
}

function formatSavedAt(ms) {
  if (!ms || ms <= 0) return "—";
  const d = new Date(ms);
  if (isNaN(d.getTime())) return "—";
  const pad = (n) => String(n).padStart(2, "0");
  return `${pad(d.getDate())}.${pad(d.getMonth() + 1)}.${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function renderDbList(entries) {
  dbListEl.innerHTML = "";
  if (!entries || entries.length === 0) {
    const li = document.createElement("li");
    li.className = "empty";
    li.textContent = "(keine gespeicherten Spiele)";
    dbListEl.appendChild(li);
    return;
  }
  for (const entry of entries) {
    const li = document.createElement("li");
    const name = document.createElement("span");
    name.className = "db-list-name";
    name.textContent = entry.gameId;
    const date = document.createElement("span");
    date.className = "db-list-date";
    date.textContent = formatSavedAt(entry.savedAt);
    li.appendChild(name);
    li.appendChild(date);
    li.title = "Klicken zum Laden";
    li.addEventListener("click", () => loadGame(entry.gameId));
    dbListEl.appendChild(li);
  }
}

function setBadge(state, text) {
  dbBadgeEl.classList.remove("up", "down");
  if (state === "up")   dbBadgeEl.classList.add("up");
  if (state === "down") dbBadgeEl.classList.add("down");
  dbBadgeEl.textContent = text;
}

async function refreshPersistenceStatus() {
  try {
    const status = await api("GET", "/api/persistence/status");
    persistenceEnabled = !!status.enabled;
    const backend = status.backend || "—";
    dbMenuBackendEl.textContent = backend;
    setBadge(persistenceEnabled ? "up" : "down", `DB ${backend}`);
    if (persistenceEnabled) await refreshDbList();
    else renderDbList([]);
  } catch (e) {
    persistenceEnabled = false;
    dbMenuBackendEl.textContent = "n/a";
    setBadge("down", "DB n/a");
    renderDbList([]);
  }
}

async function refreshDbList() {
  try {
    const res = await api("GET", "/api/persistence/games");
    renderDbList(res.games || []);
  } catch (e) {
    log(`DB-Liste: ${e.message}`, "err");
  }
}

async function loadGame(id) {
  try {
    const dto = await api("GET", `/api/persistence/games/${encodeURIComponent(id)}`);
    fenDirty = false;
    fenEl.value = dto.currentFen || "";
    pgnEl.value = dto.pgn || "";
    currentBoard = parseFen(dto.currentFen || "");
    statusEl.textContent = dto.status || "";
    selected = null;
    legalTargets = new Set();
    renderBoard();
    dbIdEl.value = dto.gameId;
    log(`geladen: ${dto.gameId}`, "ok");
  } catch (e) { log(`Laden: ${e.message}`, "err"); }
}

function toggleDbMenu(force) {
  const open = force !== undefined ? force : dbMenuEl.hasAttribute("hidden");
  if (open) {
    dbMenuEl.removeAttribute("hidden");
    dbMenuToggle.setAttribute("aria-expanded", "true");
    refreshPersistenceStatus();
    refreshLiveStatus();
  } else {
    dbMenuEl.setAttribute("hidden", "");
    dbMenuToggle.setAttribute("aria-expanded", "false");
  }
}

dbMenuToggle.addEventListener("click", (e) => {
  e.stopPropagation();
  toggleDbMenu();
});

document.addEventListener("click", (e) => {
  if (dbMenuEl.hasAttribute("hidden")) return;
  if (dbMenuEl.contains(e.target) || dbMenuToggle.contains(e.target)) return;
  toggleDbMenu(false);
});

document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && !dbMenuEl.hasAttribute("hidden")) toggleDbMenu(false);
});

document.getElementById("btn-db-save").addEventListener("click", async () => {
  const id = currentGameId(); if (!id) return;
  try {
    const dto = await api("POST", `/api/persistence/games/${encodeURIComponent(id)}`);
    log(`gespeichert: ${dto.gameId} (${dto.moves.length} Züge)`, "ok");
    await refreshDbList();
  } catch (e) { log(`Speichern: ${e.message}`, "err"); }
});

document.getElementById("btn-db-load").addEventListener("click", async () => {
  const id = currentGameId(); if (!id) return;
  await loadGame(id);
});

document.getElementById("btn-db-delete").addEventListener("click", async () => {
  const id = currentGameId(); if (!id) return;
  try {
    await api("DELETE", `/api/persistence/games/${encodeURIComponent(id)}`);
    log(`gelöscht: ${id}`, "ok");
    await refreshDbList();
  } catch (e) { log(`Löschen: ${e.message}`, "err"); }
});

document.getElementById("btn-db-list").addEventListener("click", refreshPersistenceStatus);

// ---------- live (redis) ---------------------------------------------------

const redisBadgeEl    = document.getElementById("redis-badge");
const liveBackendEl   = document.getElementById("live-backend");
const liveTtlEl       = document.getElementById("live-ttl");
const liveSessionEl   = document.getElementById("live-session-id");
const liveAutosaveEl  = document.getElementById("chk-live-autosave");
let liveEnabled = false;

function setRedisBadge(state, text) {
  redisBadgeEl.classList.remove("up", "down");
  if (state === "up")   redisBadgeEl.classList.add("up");
  if (state === "down") redisBadgeEl.classList.add("down");
  redisBadgeEl.textContent = text;
}

function currentSessionId() {
  const v = (liveSessionEl.value || "").trim();
  if (!v) {
    log("Bitte Session ID eingeben", "err");
    return null;
  }
  return v;
}

async function refreshLiveStatus() {
  try {
    const status = await api("GET", "/api/live/status");
    liveEnabled = !!status.enabled;
    const backend = status.backend || "—";
    liveBackendEl.textContent = backend;
    liveTtlEl.textContent = liveEnabled && status.ttlSeconds
      ? `(TTL ${status.ttlSeconds}s)`
      : "";
    setRedisBadge(liveEnabled ? "up" : "down", liveEnabled ? `Redis ${backend}` : "Redis n/a");
  } catch (e) {
    liveEnabled = false;
    liveBackendEl.textContent = "n/a";
    liveTtlEl.textContent = "";
    setRedisBadge("down", "Redis n/a");
  }
}

async function liveSave(silent = false) {
  const id = currentSessionId(); if (!id) return false;
  try {
    const dto = await api("POST", `/api/live/${encodeURIComponent(id)}`);
    if (!silent) log(`live gespeichert: ${dto.gameId}`, "ok");
    return true;
  } catch (e) {
    if (!silent) log(`Live-Speichern: ${e.message}`, "err");
    return false;
  }
}

async function liveLoad() {
  const id = currentSessionId(); if (!id) return;
  try {
    const dto = await api("GET", `/api/live/${encodeURIComponent(id)}`);
    fenDirty = false;
    fenEl.value = dto.currentFen || "";
    pgnEl.value = dto.pgn || "";
    currentBoard = parseFen(dto.currentFen || "");
    statusEl.textContent = dto.status || "";
    selected = null;
    legalTargets = new Set();
    renderBoard();
    log(`live geladen: ${dto.gameId}`, "ok");
  } catch (e) { log(`Live-Laden: ${e.message}`, "err"); }
}

async function liveDelete() {
  const id = currentSessionId(); if (!id) return;
  try {
    await api("DELETE", `/api/live/${encodeURIComponent(id)}`);
    log(`live gelöscht: ${id}`, "ok");
  } catch (e) { log(`Live-Löschen: ${e.message}`, "err"); }
}

async function maybeAutosave() {
  if (setup.active) return;
  if (!liveAutosaveEl.checked || !liveEnabled) return;
  await liveSave(true);
}

document.getElementById("btn-live-save").addEventListener("click", () => liveSave(false));
document.getElementById("btn-live-load").addEventListener("click", liveLoad);
document.getElementById("btn-live-delete").addEventListener("click", liveDelete);

// ---------- stockfish status + menu ---------------------------------------

const stockfishBadgeEl = document.getElementById("stockfish-badge");
const fishMenuEl       = document.getElementById("fish-menu");
const fishMenuToggle   = document.getElementById("btn-fish-menu");
const fishEngineNameEl = document.getElementById("fish-engine-name");
const fishAutoEl       = document.getElementById("chk-fish-auto");
let stockfishActive = false;
let stockfishEngineName = "";

function setStockfishBadge(state, text) {
  stockfishBadgeEl.classList.remove("up", "down");
  if (state === "up")   stockfishBadgeEl.classList.add("up");
  if (state === "down") stockfishBadgeEl.classList.add("down");
  stockfishBadgeEl.textContent = text;
}

async function refreshStockfishStatus() {
  try {
    const status = await api("GET", "/ai/status");
    stockfishActive = !!status.enabled && status.backend === "stockfish";
    stockfishEngineName = status.engine || "";
    fishEngineNameEl.textContent = status.engine || status.backend || "—";
    if (stockfishActive) {
      const short = (status.engine || "Stockfish").split(/\s+/).slice(0, 2).join(" ");
      setStockfishBadge("up", short);
    } else if (status.fallback) {
      setStockfishBadge("down", "Stockfish (Fallback)");
    } else {
      setStockfishBadge("down", "Stockfish n/a");
    }
  } catch (e) {
    stockfishActive = false;
    fishEngineNameEl.textContent = "n/a";
    setStockfishBadge("down", "Stockfish n/a");
  }
}

function toggleFishMenu(force) {
  const open = force !== undefined ? force : fishMenuEl.hasAttribute("hidden");
  if (open) {
    fishMenuEl.removeAttribute("hidden");
    fishMenuToggle.setAttribute("aria-expanded", "true");
    refreshStockfishStatus();
  } else {
    fishMenuEl.setAttribute("hidden", "");
    fishMenuToggle.setAttribute("aria-expanded", "false");
  }
}

fishMenuToggle.addEventListener("click", (e) => {
  e.stopPropagation();
  toggleFishMenu();
});

document.addEventListener("click", (e) => {
  if (fishMenuEl.hasAttribute("hidden")) return;
  if (fishMenuEl.contains(e.target) || fishMenuToggle.contains(e.target)) return;
  toggleFishMenu(false);
});

document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && !fishMenuEl.hasAttribute("hidden")) toggleFishMenu(false);
});

// ---------- central stockfish settings ------------------------------------
//
// fishSettings is the single source of truth for what is sent to the engine.
//  - mode "elo": engine knobs are derived from `elo` via eloToFishSettings.
//  - mode "custom": engine knobs come from `custom`, `customLimit` decides
//    whether depth or movetime is the limit (skill is always sent).
//
// Editing the advanced inputs only mutates a *draft*; nothing becomes active
// until the user clicks "Übernehmen". Moving the ELO slider always switches
// back to mode "elo" — that's the natural "reset to preset" gesture.
const fishSettings = {
  mode: "elo",          // "elo" | "custom"
  elo: 1500,
  custom: { depth: 12, movetime: 1000, skill: 20 },
  customLimit: "depth", // "depth" | "movetime"
};

const stockfishUi = {
  playerColor: "white",
  autoPlay: false,
};

const fishEloRangeEl       = document.getElementById("fish-elo-range");
const fishEloDisplayEl     = document.getElementById("fish-elo-display");
const fishActiveModeEl     = document.getElementById("fish-active-mode");
const fishActiveHeadlineEl = document.getElementById("fish-active-headline");
const fishActiveDetailEl   = document.getElementById("fish-active-detail");
const fishDepthEl          = document.getElementById("fish-depth");
const fishMovetimeEl       = document.getElementById("fish-movetime");
const fishSkillEl          = document.getElementById("fish-skill");
const fishAdvancedEl       = document.getElementById("fish-advanced");

const FISH_LIMITS = {
  elo:       { min: 100, max: 3200 },
  depth:     { min: 1,   max: 40   },
  movetime:  { min: 50,  max: 60000 },
  skill:     { min: 0,   max: 20   },
};

function clamp(v, { min, max }) {
  return Math.max(min, Math.min(max, v));
}

function currentPlayerColor() { return stockfishUi.playerColor; }
function currentElo()         { return fishSettings.elo; }

// ----- effective config ----------------------------------------------------
//
// What actually gets sent to the engine, derived from fishSettings.
function effectiveFishConfig() {
  if (fishSettings.mode === "custom") {
    const c = fishSettings.custom;
    return {
      mode: "custom",
      depth: fishSettings.customLimit === "depth"    ? c.depth    : null,
      movetime: fishSettings.customLimit === "movetime" ? c.movetime : null,
      skill: c.skill,
      limit: fishSettings.customLimit,
    };
  }
  const s = eloToFishSettings(fishSettings.elo);
  return { mode: "elo", depth: s.depth, movetime: s.movetime, skill: s.skill, limit: "both" };
}

// ----- rendering -----------------------------------------------------------
function renderActiveDashboard() {
  const cfg = effectiveFishConfig();
  if (cfg.mode === "elo") {
    fishActiveModeEl.textContent     = "Preset";
    fishActiveModeEl.className       = "fish-active-mode preset";
    fishActiveHeadlineEl.textContent = `ELO ${fishSettings.elo}`;
    fishActiveDetailEl.textContent   =
      `Skill ${cfg.skill} · Tiefe ${cfg.depth} · ${cfg.movetime} ms`;
  } else {
    fishActiveModeEl.textContent     = "Custom";
    fishActiveModeEl.className       = "fish-active-mode custom";
    const head = cfg.limit === "depth"
      ? `Tiefe ${cfg.depth} · Skill ${cfg.skill}`
      : `${cfg.movetime} ms · Skill ${cfg.skill}`;
    fishActiveHeadlineEl.textContent = head;
    const c = fishSettings.custom;
    fishActiveDetailEl.textContent   =
      `Skill ${c.skill} · Tiefe ${c.depth} · ${c.movetime} ms`;
  }
}

function renderEloSlider() {
  if (fishEloDisplayEl) fishEloDisplayEl.textContent = String(fishSettings.elo);
  if (fishEloRangeEl && String(fishEloRangeEl.value) !== String(fishSettings.elo)) {
    fishEloRangeEl.value = fishSettings.elo;
  }
}

// Sync the advanced inputs with what would be applied. Called whenever the
// active config changes from outside (e.g. ELO slider moved while panel is
// closed) so opening the panel never shows stale values.
function renderAdvancedFromActive() {
  const cfg = effectiveFishConfig();
  if (fishDepthEl)    fishDepthEl.value    = cfg.depth    ?? fishSettings.custom.depth;
  if (fishMovetimeEl) fishMovetimeEl.value = cfg.movetime ?? fishSettings.custom.movetime;
  if (fishSkillEl)    fishSkillEl.value    = cfg.skill;
  const lim = fishSettings.mode === "custom" ? fishSettings.customLimit : "depth";
  document.querySelectorAll('input[name="fish-mode"]').forEach(el => {
    el.checked = (el.value === lim);
  });
}

function syncStockfishUiState({ trigger = "sync", maybeMove = true } = {}) {
  // Read DOM-only knobs (color, auto checkbox) into stockfishUi.
  const colorEl = document.querySelector('input[name="fish-color"]:checked');
  if (colorEl) stockfishUi.playerColor = colorEl.value;
  if (fishAutoEl) stockfishUi.autoPlay = !!fishAutoEl.checked;

  setBoardFlipped(stockfishUi.playerColor === "black");
  renderEloSlider();
  renderAdvancedFromActive();
  renderActiveDashboard();

  if (fishAutoEl && fishAutoEl.checked !== stockfishUi.autoPlay) {
    fishAutoEl.checked = stockfishUi.autoPlay;
  }

  if (maybeMove) maybeAutoAi(trigger);
}

// ----- auto-AI -------------------------------------------------------------
let autoAiBusy = false;
async function maybeAutoAi(reason = "unknown") {
  if (setup.active) { return; }
  if (autoAiBusy) {
    log(`auto-AI (${reason}): bereits aktiv`);
    return;
  }
  if (!stockfishUi.autoPlay) {
    log(`auto-AI (${reason}): "Gegen Stockfish" nicht aktiv`);
    return;
  }
  if (currentGameOver) {
    log(`auto-AI (${reason}): Spiel ist vorbei`);
    return;
  }
  if (clockGameOver()) {
    log(`auto-AI (${reason}): Zeit abgelaufen`);
    return;
  }
  const aiSide = stockfishUi.playerColor === "white" ? "black" : "white";
  if (currentTurn !== aiSide) {
    log(`auto-AI (${reason}): nicht dran (Zug=${currentTurn}, AI=${aiSide})`);
    return;
  }
  log(`auto-AI (${reason}): Stockfish zieht als ${aiSide}`, "ok");
  autoAiBusy = true;
  try {
    const fallbackDepth = parseInt(document.getElementById("ai-depth").value, 10) || 2;
    await runAiMove(fallbackDepth);
  } finally {
    autoAiBusy = false;
  }
}

// ----- wiring: ELO slider --------------------------------------------------
//
// Moving the slider always switches back to "elo" mode (the natural reset
// gesture). The display is updated live; auto-AI is *not* re-evaluated on
// every tick — only on explicit user actions like color change or moves.
if (fishEloRangeEl) {
  const onSlide = () => {
    const v = parseInt(fishEloRangeEl.value, 10);
    if (Number.isNaN(v)) return;
    fishSettings.mode = "elo";
    fishSettings.elo  = clamp(v, FISH_LIMITS.elo);
    renderEloSlider();
    renderAdvancedFromActive();
    renderActiveDashboard();
  };
  fishEloRangeEl.addEventListener("input",  onSlide);
  fishEloRangeEl.addEventListener("change", onSlide);
}

// ----- wiring: advanced apply / reset / from-elo ---------------------------
function readDraftFromAdvanced() {
  const limitEl = document.querySelector('input[name="fish-mode"]:checked');
  const limit   = limitEl ? limitEl.value : "depth";
  const depth    = clamp(parseInt(fishDepthEl.value, 10)    || FISH_LIMITS.depth.min,    FISH_LIMITS.depth);
  const movetime = clamp(parseInt(fishMovetimeEl.value, 10) || FISH_LIMITS.movetime.min, FISH_LIMITS.movetime);
  const skill    = clamp(parseInt(fishSkillEl.value, 10),                                FISH_LIMITS.skill);
  return { depth, movetime, skill, limit };
}

document.getElementById("btn-fish-apply").addEventListener("click", () => {
  const draft = readDraftFromAdvanced();
  fishSettings.mode = "custom";
  fishSettings.custom = { depth: draft.depth, movetime: draft.movetime, skill: draft.skill };
  fishSettings.customLimit = draft.limit;
  // Write back the clamped values so invalid input is visibly corrected.
  fishDepthEl.value    = draft.depth;
  fishMovetimeEl.value = draft.movetime;
  fishSkillEl.value    = draft.skill;
  renderActiveDashboard();
  log(`Stockfish: Custom übernommen (${draft.limit === "depth" ? `Tiefe ${draft.depth}` : `${draft.movetime} ms`}, Skill ${draft.skill})`, "ok");
});

document.getElementById("btn-fish-from-elo").addEventListener("click", () => {
  const s = eloToFishSettings(fishSettings.elo);
  fishDepthEl.value    = s.depth;
  fishMovetimeEl.value = s.movetime;
  fishSkillEl.value    = s.skill;
  document.querySelectorAll('input[name="fish-mode"]').forEach(el => {
    el.checked = (el.value === "depth");
  });
  log(`Stockfish: Entwurf aus ELO ${fishSettings.elo} befüllt (nicht aktiv bis "Übernehmen")`);
});

document.getElementById("btn-fish-reset").addEventListener("click", () => {
  fishSettings.mode = "elo";
  renderAdvancedFromActive();
  renderActiveDashboard();
  log(`Stockfish: zurück auf Preset ELO ${fishSettings.elo}`, "ok");
});

// ----- wiring: color radios + auto checkbox --------------------------------
function handleColorChange() {
  // Defer one tick so the new :checked is final before we read it.
  setTimeout(() => {
    if (fishAutoEl) fishAutoEl.checked = true;
    syncStockfishUiState({ trigger: "color" });
  }, 0);
}
document.querySelectorAll('input[name="fish-color"]').forEach(el => {
  el.addEventListener("change", handleColorChange);
  el.addEventListener("click",  handleColorChange);
});
fishAutoEl.addEventListener("change", () => syncStockfishUiState({ trigger: "auto-toggle" }));

// ---------- promotion popup -----------------------------------------------
//
// Anchor-positioned picker for pawn promotion. Returns a Promise that
// resolves to "q"|"r"|"b"|"n" or null if dismissed. Click-away or Escape
// cancel the move (caller treats null as "abort"). Side affects glyph
// colour so it visually matches the player's pieces.

const promotionEl = document.getElementById("promotion-popup");
let promotionResolver = null;

function pickPromotion(anchorEl, side) {
  return new Promise((resolve) => {
    promotionResolver = resolve;
    promotionEl.classList.remove("piece-white", "piece-black");
    promotionEl.classList.add(side === "white" ? "piece-white" : "piece-black");
    const rect = (anchorEl || boardEl).getBoundingClientRect();
    // Defer one tick so the click that triggered the promotion finishes
    // bubbling before we register as a click target.
    setTimeout(() => {
      promotionEl.removeAttribute("hidden");
      const popRect = promotionEl.getBoundingClientRect();
      const margin = 6;
      let top  = rect.top - popRect.height - margin;
      if (top < 4) top = rect.bottom + margin;
      let left = rect.left + (rect.width - popRect.width) / 2;
      left = Math.max(4, Math.min(left, window.innerWidth - popRect.width - 4));
      promotionEl.style.top  = `${top + window.scrollY}px`;
      promotionEl.style.left = `${left + window.scrollX}px`;
    }, 0);
  });
}

function closePromotion(value) {
  promotionEl.setAttribute("hidden", "");
  const r = promotionResolver;
  promotionResolver = null;
  if (r) r(value);
}

promotionEl.querySelectorAll("button").forEach(btn => {
  btn.addEventListener("click", (e) => {
    e.stopPropagation();
    closePromotion(btn.dataset.promo);
  });
});
document.addEventListener("click", (e) => {
  if (promotionEl.hasAttribute("hidden")) return;
  if (promotionEl.contains(e.target)) return;
  closePromotion(null);
});
document.addEventListener("keydown", (e) => {
  if (promotionEl.hasAttribute("hidden")) return;
  if (e.key === "Escape") { closePromotion(null); return; }
  const map = { q: "q", r: "r", b: "b", n: "n" };
  if (map[e.key.toLowerCase()]) closePromotion(map[e.key.toLowerCase()]);
});

// ---------- result modal --------------------------------------------------
//
// One central popup for any kind of game end. Triggered from applyState()
// for board endings (checkmate/stalemate/...) and from the clock tick when
// a side flags. Deduped via resultModalShownFor so it only fires once per
// terminal position.

const resultModalEl    = document.getElementById("result-modal");
const resultTitleEl    = document.getElementById("result-title");
const resultMessageEl  = document.getElementById("result-message");
const resultNewBtn     = document.getElementById("btn-result-new");
const resultCloseBtn   = document.getElementById("btn-result-close");

function showGameResult(kind, title, message, dedupKey) {
  if (dedupKey != null && resultModalShownFor === dedupKey) return;
  resultModalShownFor = dedupKey;
  resultModalEl.querySelector(".result-modal").className =
    `result-modal ${kind}`;
  resultTitleEl.textContent   = title;
  resultMessageEl.textContent = message;
  resultModalEl.removeAttribute("hidden");
}

function hideResultModal() {
  resultModalEl.setAttribute("hidden", "");
}

// Read board-game-over result from server state. Status string conventions
// from the backend look like "checkmate - White wins" / "stalemate" / etc.
// We treat "wins" + colour as the deciding signal; anything ambiguous falls
// back to "draw".
function checkGameResult(state) {
  if (!state || !state.gameOver) return;
  const status = String(state.status || "").toLowerCase();
  const playerColor = currentPlayerColor();
  const dedupKey = `board:${status}`;
  let kind = "draw", title = "Unentschieden", msg = state.status || "Spielende";
  if (status.includes("white wins")) {
    if (playerColor === "white") { kind = "win";  title = "Gewonnen"; msg = "Schachmatt — Weiß gewinnt."; }
    else                          { kind = "loss"; title = "Verloren"; msg = "Schachmatt — Weiß gewinnt."; }
  } else if (status.includes("black wins")) {
    if (playerColor === "black") { kind = "win";  title = "Gewonnen"; msg = "Schachmatt — Schwarz gewinnt."; }
    else                          { kind = "loss"; title = "Verloren"; msg = "Schachmatt — Schwarz gewinnt."; }
  } else if (status.includes("stalemate")) {
    msg = "Patt — keine legalen Züge.";
  } else if (status.includes("draw") || status.includes("repetition") ||
             status.includes("fifty") || status.includes("material")) {
    msg = state.status;
  }
  showGameResult(kind, title, msg, dedupKey);
}

function showClockResult(loser) {
  const playerColor = currentPlayerColor();
  const dedupKey = `clock:${loser}`;
  if (loser === playerColor) {
    showGameResult("loss", "Verloren", "Deine Zeit ist abgelaufen.", dedupKey);
  } else {
    showGameResult("win", "Gewonnen",
      `${loser === "white" ? "Weiß" : "Schwarz"} hat auf Zeit verloren.`, dedupKey);
  }
}

resultCloseBtn.addEventListener("click", hideResultModal);
resultNewBtn.addEventListener("click", async () => {
  hideResultModal();
  try {
    fenDirty = false;
    manualGameOver = false;
    applyState(await api("POST", "/api/game/reset"));
    await refreshPgn();
    resetClocks();
    renderClocks();
    analysis.prevEvalCp = null;
    if (analysis.enabled) requestAnalysis(null);
    resultModalShownFor = null;
    log("Neues Spiel", "ok");
    syncStockfishUiState({ trigger: "result-new" });
  } catch (e) { log(e.message, "err"); }
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && !resultModalEl.hasAttribute("hidden")) hideResultModal();
});

// ---------- resign / draw -------------------------------------------------
//
// Both are purely client-side for v1: server-state remains mid-game, but the
// frontend treats the position as terminal (clocks stop, further moves are
// rejected, result modal opens, dedup is keyed so a fresh game re-arms it).

function resignGame() {
  if (currentGameOver || manualGameOver || clocks.expired) return;
  if (!confirm("Partie wirklich aufgeben?")) return;
  manualGameOver = true;
  stopClocksManually();
  log("Aufgegeben", "err");
  showGameResult("loss", "Verloren", "Du hast aufgegeben.", "manual:resign");
  playSound("gameover");
}

function offerDraw() {
  if (currentGameOver || manualGameOver || clocks.expired) return;
  manualGameOver = true;
  stopClocksManually();
  log("Remis vereinbart (Trainingsmodus)", "ok");
  showGameResult("draw", "Remis", "Stockfish nimmt das Remis an.", "manual:draw");
  playSound("gameover");
}

document.getElementById("btn-resign").addEventListener("click", resignGame);
document.getElementById("btn-draw").addEventListener("click", offerDraw);

// ---------- time mode (clocks) --------------------------------------------
//
// Pure client-side chess clock. Backend has no idea this exists. State:
//  - timeSettings: chosen mode (Lichess-style "base+inc" in seconds)
//  - clocks: live remaining ms per side, runningSide, lastTickAt (perf clock)
//
// Lichess-Verhalten: nach Weiß' erstem Zug startet Schwarzs Uhr. Vor dem
// allerersten Zug läuft nichts. Increment wird *nach* dem Zug der ziehenden
// Seite addiert.

const TIME_PRESETS = [
  { id: "none",    name: "Ohne Uhr",      base: 0,    inc: 0,  enabled: false },
  { id: "1+0",     name: "Bullet 1+0",    base: 60,   inc: 0  },
  { id: "2+1",     name: "Bullet 2+1",    base: 120,  inc: 1  },
  { id: "3+0",     name: "Blitz 3+0",     base: 180,  inc: 0  },
  { id: "3+2",     name: "Blitz 3+2",     base: 180,  inc: 2  },
  { id: "5+0",     name: "Blitz 5+0",     base: 300,  inc: 0  },
  { id: "5+3",     name: "Blitz 5+3",     base: 300,  inc: 3  },
  { id: "10+0",    name: "Rapid 10+0",    base: 600,  inc: 0  },
  { id: "10+5",    name: "Rapid 10+5",    base: 600,  inc: 5  },
  { id: "15+10",   name: "Rapid 15+10",   base: 900,  inc: 10 },
  { id: "30+0",    name: "Klassisch 30+0",base: 1800, inc: 0  },
];

const timeSettings = {
  enabled: false,
  baseSeconds: 0,
  incrementSeconds: 0,
  modeName: "Ohne Uhr",
  modeId: "none",
};

const clocks = {
  whiteMs: 0,
  blackMs: 0,
  runningSide: null,   // null | "white" | "black"
  lastTickAt: null,    // performance.now() timestamp
  firstMovePlayed: false,
  expired: false,
  expiredSide: null,
};

const timeMenuEl     = document.getElementById("time-menu");
const timeMenuToggle = document.getElementById("btn-time-menu");
const timePresetsEl  = document.getElementById("time-presets");
const timeActiveEl   = document.getElementById("time-active-name");
const timeCustomBase = document.getElementById("time-custom-base");
const timeCustomInc  = document.getElementById("time-custom-inc");
const clockTopEl     = document.getElementById("clock-top");
const clockBottomEl  = document.getElementById("clock-bottom");
const clockRailEl    = document.getElementById("clock-rail");

function clockGameOver() { return clocks.expired || manualGameOver; }

function stopClocksManually() {
  if (clocks.runningSide && clocks.lastTickAt != null) {
    const now = performance.now();
    const dt  = now - clocks.lastTickAt;
    if (clocks.runningSide === "white") clocks.whiteMs -= dt;
    else                                 clocks.blackMs -= dt;
    if (clocks.whiteMs < 0) clocks.whiteMs = 0;
    if (clocks.blackMs < 0) clocks.blackMs = 0;
  }
  clocks.runningSide = null;
  clocks.lastTickAt = null;
  renderClocks();
}

function formatClock(ms) {
  if (ms <= 0) return "0:00";
  const totalCs = Math.ceil(ms / 10); // centiseconds, ceil so 0.001 still shows
  const totalSec = Math.floor(totalCs / 100);
  const m  = Math.floor(totalSec / 60);
  const s  = totalSec % 60;
  // Show tenths only when under 20s, like Lichess.
  if (ms < 20000) {
    const t = Math.floor((totalCs % 100) / 10);
    return `${m}:${String(s).padStart(2,"0")}.${t}`;
  }
  return `${m}:${String(s).padStart(2,"0")}`;
}

function applyTimeMode(preset) {
  timeSettings.enabled          = preset.enabled !== false && preset.base > 0;
  timeSettings.baseSeconds      = preset.base;
  timeSettings.incrementSeconds = preset.inc;
  timeSettings.modeName         = preset.name;
  timeSettings.modeId           = preset.id;
  resetClocks();
  renderTimePresets();
  renderClocks();
  log(`Zeitmodus: ${preset.name}`, "ok");
}

function pauseClocksAfterLoad() {
  if (!timeSettings.enabled) return;
  // Drain pending elapsed time, then stop the clock entirely. The next move
  // (human or AI) will start the appropriate side's clock from onClockMovePlayed.
  if (clocks.runningSide && clocks.lastTickAt != null) {
    const now = performance.now();
    const dt  = now - clocks.lastTickAt;
    if (clocks.runningSide === "white") clocks.whiteMs -= dt;
    else                                 clocks.blackMs -= dt;
    if (clocks.whiteMs < 0) clocks.whiteMs = 0;
    if (clocks.blackMs < 0) clocks.blackMs = 0;
  }
  // Treat the loaded position as if we're back to "before the next move":
  // black-to-move after load means white's clock will start when white moves
  // — but onClockMovePlayed flips runningSide based on who just moved. Easiest
  // model: clear runningSide and let the next move set it.
  clocks.runningSide = null;
  clocks.lastTickAt  = null;
  clocks.firstMovePlayed = true;  // suppress the "white's first move" special case
  renderClocks();
  log("Hinweis: Uhr ist pausiert; nächster Zug startet sie wieder.");
}

function resetClocks() {
  clocks.whiteMs = timeSettings.baseSeconds * 1000;
  clocks.blackMs = timeSettings.baseSeconds * 1000;
  clocks.runningSide = null;
  clocks.lastTickAt = null;
  clocks.firstMovePlayed = false;
  clocks.expired = false;
  clocks.expiredSide = null;
}

function tickClocks() {
  // Lichess sessions own the clock authoritatively; we just recompute the
  // displayed value from the latest snapshot + elapsed wall-clock time.
  if (isLichessSessionActive() && renderLichessClocks()) return;
  if (!timeSettings.enabled || !clocks.runningSide || clocks.expired) {
    renderClocks();
    return;
  }
  const now = performance.now();
  const dt  = clocks.lastTickAt == null ? 0 : (now - clocks.lastTickAt);
  clocks.lastTickAt = now;
  if (clocks.runningSide === "white") clocks.whiteMs -= dt;
  else                                 clocks.blackMs -= dt;
  if (clocks.whiteMs <= 0 || clocks.blackMs <= 0) {
    const loser = clocks.whiteMs <= 0 ? "white" : "black";
    if (clocks.whiteMs <= 0) clocks.whiteMs = 0;
    if (clocks.blackMs <= 0) clocks.blackMs = 0;
    clocks.expired = true;
    clocks.expiredSide = loser;
    clocks.runningSide = null;
    log(`Zeit abgelaufen: ${loser === "white" ? "Weiß" : "Schwarz"} verliert`, "err");
    showClockResult(loser);
    playSound("gameover");
  }
  renderClocks();
}

// Called after a successful move on either side. `mover` is the side that just
// moved (read from server state *before* we apply the new state — but easier:
// previous turn). We pass it in explicitly.
function onClockMovePlayed(mover) {
  if (!timeSettings.enabled || clocks.expired) return;
  // Drain any pending elapsed time onto the side that was running.
  if (clocks.runningSide && clocks.lastTickAt != null) {
    const now = performance.now();
    const dt  = now - clocks.lastTickAt;
    if (clocks.runningSide === "white") clocks.whiteMs -= dt;
    else                                 clocks.blackMs -= dt;
    if (clocks.whiteMs < 0) clocks.whiteMs = 0;
    if (clocks.blackMs < 0) clocks.blackMs = 0;
  }
  // Increment on the side that just moved (Fischer).
  const incMs = timeSettings.incrementSeconds * 1000;
  if (incMs > 0) {
    if (mover === "white") clocks.whiteMs += incMs;
    else                    clocks.blackMs += incMs;
  }
  // Lichess: black's clock starts after white's first move; before that nothing
  // ticks. After the first move, the opposite side's clock runs.
  if (!clocks.firstMovePlayed) {
    clocks.firstMovePlayed = true;
  }
  clocks.runningSide = (mover === "white") ? "black" : "white";
  clocks.lastTickAt  = performance.now();
  renderClocks();
}

// AI movetime cap: min(stockfishMovetime, restMs/30 - reserveMs).
// Returns null if the cap shouldn't apply (no clock active or AI side unknown).
function aiMovetimeCapMs(aiSide) {
  if (!timeSettings.enabled) return null;
  const restMs = aiSide === "white" ? clocks.whiteMs : clocks.blackMs;
  const reserve = 200;
  const cap = Math.floor(restMs / 30) - reserve;
  return Math.max(50, cap); // never go under engine min
}

function renderTimePresets() {
  timePresetsEl.innerHTML = "";
  for (const p of TIME_PRESETS) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.textContent = p.name;
    if (p.id === timeSettings.modeId) btn.classList.add("active");
    btn.addEventListener("click", () => applyTimeMode(p));
    timePresetsEl.appendChild(btn);
  }
  if (timeActiveEl) timeActiveEl.textContent = timeSettings.modeName;
}

function activeClockSlots() {
  // Bottom slot belongs to the side at the bottom of the board (boardFlipped:
  // black is at the bottom). Top slot is the opposite.
  const bottomSide = boardFlipped ? "black" : "white";
  const topSide    = boardFlipped ? "white" : "black";
  return { topSide, bottomSide };
}

function renderClocks() {
  if (!timeSettings.enabled) {
    clockTopEl.setAttribute("hidden", "");
    clockBottomEl.setAttribute("hidden", "");
    if (clockRailEl) clockRailEl.style.display = "none";
    return;
  }
  if (clockRailEl) clockRailEl.style.display = "";
  clockTopEl.removeAttribute("hidden");
  clockBottomEl.removeAttribute("hidden");
  const { topSide, bottomSide } = activeClockSlots();
  paintClock(clockTopEl,    topSide);
  paintClock(clockBottomEl, bottomSide);
}

function paintClock(el, side) {
  const ms = side === "white" ? clocks.whiteMs : clocks.blackMs;
  el.querySelector(".clock-label").textContent = side === "white" ? "Weiß" : "Schwarz";
  el.querySelector(".clock-time").textContent  = formatClock(ms);
  el.classList.toggle("active",  clocks.runningSide === side);
  el.classList.toggle("low",     ms < 20000 && ms > 0 && !clocks.expired);
  el.classList.toggle("expired", clocks.expired && clocks.expiredSide === side);
}

function toggleTimeMenu(force) {
  const open = force !== undefined ? force : timeMenuEl.hasAttribute("hidden");
  if (open) {
    timeMenuEl.removeAttribute("hidden");
    timeMenuToggle.setAttribute("aria-expanded", "true");
    renderTimePresets();
  } else {
    timeMenuEl.setAttribute("hidden", "");
    timeMenuToggle.setAttribute("aria-expanded", "false");
  }
}

timeMenuToggle.addEventListener("click", (e) => {
  e.stopPropagation();
  toggleTimeMenu();
});
document.addEventListener("click", (e) => {
  if (timeMenuEl.hasAttribute("hidden")) return;
  if (timeMenuEl.contains(e.target) || timeMenuToggle.contains(e.target)) return;
  toggleTimeMenu(false);
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && !timeMenuEl.hasAttribute("hidden")) toggleTimeMenu(false);
});

document.getElementById("btn-time-custom-apply").addEventListener("click", () => {
  const baseMin = Math.max(0, parseInt(timeCustomBase.value, 10) || 0);
  const inc     = Math.max(0, parseInt(timeCustomInc.value, 10)  || 0);
  applyTimeMode({
    id: "custom",
    name: `Custom ${baseMin}+${inc}`,
    base: baseMin * 60,
    inc,
  });
});

setInterval(tickClocks, 150);

// ---------- themes --------------------------------------------------------
//
// Five board presets that swap --light / --dark via CSS variables on :root.
// Accent colours (--sel, --legal) stay constant so highlights look the same
// across themes. Selection is persisted in localStorage.

const THEME_PRESETS = [
  { id: "classic", name: "Klassisch",     light: "#eadfc5", dark: "#6b8e4e" },
  { id: "brown",   name: "Lichess Brown", light: "#f0d9b5", dark: "#b58863" },
  { id: "blue",    name: "Blau",          light: "#dee3e6", dark: "#8ca2ad" },
  { id: "gray",    name: "Grau",          light: "#dcdcdc", dark: "#7a7a7a" },
  { id: "wood",    name: "Holz",          light: "#e8c99b", dark: "#a06a3f" },
];
const THEME_STORAGE_KEY = "almachess.theme";

const themeMenuEl     = document.getElementById("theme-menu");
const themeMenuToggle = document.getElementById("btn-theme-menu");
const themePresetsEl  = document.getElementById("theme-presets");
const themeActiveEl   = document.getElementById("theme-active-name");
let activeThemeId = "classic";

function applyTheme(preset) {
  document.documentElement.style.setProperty("--light", preset.light);
  document.documentElement.style.setProperty("--dark",  preset.dark);
  activeThemeId = preset.id;
  try { localStorage.setItem(THEME_STORAGE_KEY, preset.id); } catch (_) {}
  if (themeActiveEl) themeActiveEl.textContent = preset.name;
  renderThemePresets();
}

function renderThemePresets() {
  themePresetsEl.innerHTML = "";
  for (const p of THEME_PRESETS) {
    const btn = document.createElement("button");
    btn.type = "button";
    if (p.id === activeThemeId) btn.classList.add("active");
    const swatch = document.createElement("span");
    swatch.className = "theme-swatch";
    const lightSpan = document.createElement("span");
    lightSpan.style.background = p.light;
    const darkSpan  = document.createElement("span");
    darkSpan.style.background = p.dark;
    swatch.append(lightSpan, darkSpan);
    const label = document.createElement("span");
    label.textContent = p.name;
    btn.append(swatch, label);
    btn.addEventListener("click", () => applyTheme(p));
    themePresetsEl.appendChild(btn);
  }
}

function loadTheme() {
  let id = "classic";
  try { id = localStorage.getItem(THEME_STORAGE_KEY) || "classic"; } catch (_) {}
  const preset = THEME_PRESETS.find(p => p.id === id) || THEME_PRESETS[0];
  applyTheme(preset);
}

function toggleThemeMenu(force) {
  const open = force !== undefined ? force : themeMenuEl.hasAttribute("hidden");
  if (open) {
    themeMenuEl.removeAttribute("hidden");
    themeMenuToggle.setAttribute("aria-expanded", "true");
    renderThemePresets();
  } else {
    themeMenuEl.setAttribute("hidden", "");
    themeMenuToggle.setAttribute("aria-expanded", "false");
  }
}
themeMenuToggle.addEventListener("click", (e) => { e.stopPropagation(); toggleThemeMenu(); });
document.addEventListener("click", (e) => {
  if (themeMenuEl.hasAttribute("hidden")) return;
  if (themeMenuEl.contains(e.target) || themeMenuToggle.contains(e.target)) return;
  toggleThemeMenu(false);
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && !themeMenuEl.hasAttribute("hidden")) toggleThemeMenu(false);
});

// ---------- sounds --------------------------------------------------------
//
// Tries to play web/html/assets/sounds/{kind}.mp3. If the file fails to load
// (404/decode error), we register a synthetic Web Audio fallback for that
// kind. Toggle + volume in localStorage; no audio context is created until
// the first call so we don't trip browser autoplay policies before a user
// gesture happens.

const SOUND_KINDS = ["move", "capture", "check", "promote", "gameover"];
const SOUND_STORAGE_KEY = "almachess.sound";

const soundState = {
  enabled: true,
  volume: 0.6,
  buffers: {},   // kind -> HTMLAudioElement | null (null = use fallback)
  ctx: null,     // lazy AudioContext for fallback beeps
};

const soundMenuEl     = document.getElementById("sound-menu");
const soundMenuToggle = document.getElementById("btn-sound-menu");
const soundEnabledEl  = document.getElementById("chk-sound-enabled");
const soundVolumeEl   = document.getElementById("sound-volume");
const soundStatusEl   = document.getElementById("sound-status");
const soundTestBtn    = document.getElementById("btn-sound-test");

function loadSoundPrefs() {
  try {
    const raw = localStorage.getItem(SOUND_STORAGE_KEY);
    if (raw) {
      const v = JSON.parse(raw);
      if (typeof v.enabled === "boolean") soundState.enabled = v.enabled;
      if (typeof v.volume === "number")   soundState.volume  = Math.max(0, Math.min(1, v.volume));
    }
  } catch (_) {}
  soundEnabledEl.checked = soundState.enabled;
  soundVolumeEl.value    = Math.round(soundState.volume * 100);
}

function saveSoundPrefs() {
  try {
    localStorage.setItem(SOUND_STORAGE_KEY,
      JSON.stringify({ enabled: soundState.enabled, volume: soundState.volume }));
  } catch (_) {}
}

function preloadSounds() {
  let loaded = 0, missing = 0;
  for (const kind of SOUND_KINDS) {
    const audio = new Audio(`assets/sounds/${kind}.mp3`);
    audio.preload = "auto";
    soundState.buffers[kind] = audio;
    audio.addEventListener("error", () => {
      soundState.buffers[kind] = null; // mark as fallback
      missing++;
      updateSoundStatus(loaded, missing);
    }, { once: true });
    audio.addEventListener("canplaythrough", () => {
      loaded++;
      updateSoundStatus(loaded, missing);
    }, { once: true });
  }
  updateSoundStatus(0, 0);
}

function updateSoundStatus(loaded, missing) {
  if (!soundStatusEl) return;
  if (missing === SOUND_KINDS.length) soundStatusEl.textContent = "Synth-Fallback";
  else if (missing > 0)               soundStatusEl.textContent = `${loaded} MP3 / ${missing} Fallback`;
  else if (loaded === 0)              soundStatusEl.textContent = "lädt…";
  else                                 soundStatusEl.textContent = `${loaded} MP3 geladen`;
}

// Synthetic per-kind beep. Kept short so it never overlaps the next one.
const FALLBACK_TONES = {
  move:     { freq: 480, dur: 0.07, type: "sine"     },
  capture:  { freq: 220, dur: 0.10, type: "square"   },
  check:    { freq: 880, dur: 0.15, type: "triangle" },
  promote:  { freq: 660, dur: 0.18, type: "sine"     },
  gameover: { freq: 160, dur: 0.35, type: "sawtooth" },
};

function playFallback(kind) {
  const tone = FALLBACK_TONES[kind] || FALLBACK_TONES.move;
  try {
    if (!soundState.ctx) soundState.ctx = new (window.AudioContext || window.webkitAudioContext)();
    const ctx = soundState.ctx;
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = tone.type;
    osc.frequency.value = tone.freq;
    gain.gain.value = soundState.volume * 0.25;
    osc.connect(gain).connect(ctx.destination);
    const t0 = ctx.currentTime;
    gain.gain.setValueAtTime(soundState.volume * 0.25, t0);
    gain.gain.exponentialRampToValueAtTime(0.0001, t0 + tone.dur);
    osc.start(t0);
    osc.stop(t0 + tone.dur + 0.02);
  } catch (_) { /* AudioContext unavailable — silently drop */ }
}

function playSound(kind) {
  if (!soundState.enabled) return;
  const buf = soundState.buffers[kind];
  if (buf) {
    try {
      const clone = buf.cloneNode();
      clone.volume = soundState.volume;
      clone.play().catch(() => playFallback(kind));
      return;
    } catch (_) { /* fall through */ }
  }
  playFallback(kind);
}

function toggleSoundMenu(force) {
  const open = force !== undefined ? force : soundMenuEl.hasAttribute("hidden");
  if (open) {
    soundMenuEl.removeAttribute("hidden");
    soundMenuToggle.setAttribute("aria-expanded", "true");
  } else {
    soundMenuEl.setAttribute("hidden", "");
    soundMenuToggle.setAttribute("aria-expanded", "false");
  }
}
soundMenuToggle.addEventListener("click", (e) => { e.stopPropagation(); toggleSoundMenu(); });
document.addEventListener("click", (e) => {
  if (soundMenuEl.hasAttribute("hidden")) return;
  if (soundMenuEl.contains(e.target) || soundMenuToggle.contains(e.target)) return;
  toggleSoundMenu(false);
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && !soundMenuEl.hasAttribute("hidden")) toggleSoundMenu(false);
});
soundEnabledEl.addEventListener("change", () => {
  soundState.enabled = soundEnabledEl.checked;
  saveSoundPrefs();
});
soundVolumeEl.addEventListener("input", () => {
  soundState.volume = (parseInt(soundVolumeEl.value, 10) || 0) / 100;
  saveSoundPrefs();
});
soundTestBtn.addEventListener("click", () => playSound("move"));

// ---------- analysis (live engine eval) -----------------------------------
//
// Toggle in the header arms a per-move evaluation against /ai/evaluate. Only
// one request runs at a time; if a new move comes in while a request is in
// flight, we bump the token and ignore the stale response. The analysis line
// in the header shows current eval, best move, and (if we have a previous
// eval) a classification of the move just played.

const ANALYSIS_STORAGE_KEY = "almachess.analysis.enabled";
const ANALYSIS_DEPTH = 12;
// Mate-scores normalise to a huge value so diffs against cp work cleanly.
const MATE_SCORE = 100000;

const analysisToggleEl  = document.getElementById("btn-analysis-toggle");
const analysisLineEl    = document.getElementById("analysis-line");
const analysisEvalEl    = document.getElementById("analysis-eval");
const analysisBestEl    = document.getElementById("analysis-best");
const analysisClassEl   = document.getElementById("analysis-class");

const analysis = {
  enabled: false,
  busy: false,
  reqToken: 0,           // bumped on each request; stale responses ignored
  prevEvalCp: null,      // White-POV cp of the position before the latest move
  lastMover: null,       // who played the move that produced the current eval
};

function loadAnalysisPref() {
  let on = false;
  try { on = localStorage.getItem(ANALYSIS_STORAGE_KEY) === "1"; } catch (_) {}
  setAnalysisEnabled(on, /* initial */ true);
}

function saveAnalysisPref() {
  try { localStorage.setItem(ANALYSIS_STORAGE_KEY, analysis.enabled ? "1" : "0"); } catch (_) {}
}

function setAnalysisEnabled(on, initial = false) {
  analysis.enabled = !!on;
  analysisToggleEl.setAttribute("aria-pressed", analysis.enabled ? "true" : "false");
  analysisToggleEl.classList.toggle("analysis-off", !analysis.enabled);
  syncCoordsVisibility();
  if (!analysis.enabled) {
    // Cancel by token bump — in-flight responses get dropped.
    analysis.reqToken++;
    analysis.busy = false;
    hideAnalysisLine();
  } else {
    showAnalysisLine();
    // Trigger an immediate evaluation of the current position so the line
    // is meaningful right after enabling.
    if (!initial) requestAnalysis(/* mover */ null);
    else          requestAnalysis(null);
  }
  if (!initial) saveAnalysisPref();
}

function hideAnalysisLine() {
  analysisLineEl.setAttribute("hidden", "");
  analysisEvalEl.textContent  = "—";
  analysisBestEl.textContent  = "";
  analysisClassEl.textContent = "";
  analysisClassEl.className   = "analysis-class";
  analysisEvalEl.className    = "analysis-eval";
}

function showAnalysisLine() {
  analysisLineEl.removeAttribute("hidden");
  analysisLineEl.title = `Analyse: Tiefe ${ANALYSIS_DEPTH} · Skill 20 (unabhängig vom Spiel-ELO)`;
}

function setAnalysisBusy() {
  analysisEvalEl.textContent = "…";
  analysisEvalEl.className   = "analysis-eval busy";
  analysisBestEl.textContent = "";
  analysisClassEl.textContent = "";
  analysisClassEl.className  = "analysis-class";
}

// White-POV centipawn-equivalent score. Mate scores collapse to ±MATE_SCORE
// so diffs are comparable. Returns null if no usable score.
function evalToCp(resp) {
  if (resp == null) return null;
  if (resp.mate != null) return resp.mate >= 0 ? MATE_SCORE - resp.mate : -MATE_SCORE - resp.mate;
  if (resp.centipawns != null) return resp.centipawns;
  return null;
}

function formatPawns(cp) {
  if (cp == null) return "—";
  if (cp >=  MATE_SCORE - 1000) return `M${MATE_SCORE - cp}`;
  if (cp <= -MATE_SCORE + 1000) return `-M${MATE_SCORE + cp}`;
  const sign = cp >= 0 ? "+" : "";
  return `${sign}${(cp / 100).toFixed(2)}`;
}

function classifyMove(prevCpWhite, currCpWhite, mover) {
  if (prevCpWhite == null || currCpWhite == null || mover == null) return null;
  // Loss from the mover's POV: a move is bad if eval moves *against* the mover.
  const sign = mover === "white" ? 1 : -1;
  const loss = sign * (prevCpWhite - currCpWhite);
  if (loss >= 300) return { tag: "??", kind: "blunder",    label: "Blunder" };
  if (loss >= 150) return { tag: "?",  kind: "mistake",    label: "Fehler" };
  if (loss >=  60) return { tag: "?!", kind: "inaccuracy", label: "Ungenauigkeit" };
  return null;
}

async function requestAnalysis(mover) {
  if (setup.active) return;
  if (!analysis.enabled) return;
  if (!stockfishActive) {
    analysisEvalEl.textContent = "Stockfish n/a";
    analysisEvalEl.className   = "analysis-eval busy";
    analysisBestEl.textContent = "";
    analysisClassEl.textContent = "";
    log("Analyse benötigt Stockfish", "err");
    return;
  }
  const fen = (fenEl.value || "").trim();
  if (!fen) return;
  const myToken = ++analysis.reqToken;
  analysis.busy = true;
  setAnalysisBusy();
  try {
    const resp = await api("POST", "/ai/evaluate", { fen, depth: ANALYSIS_DEPTH });
    if (myToken !== analysis.reqToken) return; // stale
    renderAnalysis(resp, mover);
  } catch (e) {
    if (myToken !== analysis.reqToken) return;
    analysisEvalEl.textContent = "Fehler";
    analysisEvalEl.className   = "analysis-eval busy";
    log(`Analyse: ${e.message}`, "err");
  } finally {
    if (myToken === analysis.reqToken) analysis.busy = false;
  }
}

function renderAnalysis(resp, mover) {
  const cp = evalToCp(resp);
  analysisEvalEl.textContent = formatPawns(cp);
  analysisEvalEl.className =
    "analysis-eval " + (cp == null ? "" : cp > 30 ? "pos" : cp < -30 ? "neg" : "");
  analysisBestEl.textContent = resp.bestMove ? `Best: ${resp.bestMove}` : "";

  const cls = classifyMove(analysis.prevEvalCp, cp, mover);
  if (cls) {
    analysisClassEl.textContent = `${cls.tag} ${cls.label}`;
    analysisClassEl.className   = `analysis-class ${cls.kind}`;
  } else {
    analysisClassEl.textContent = "";
    analysisClassEl.className   = "analysis-class";
  }
  analysis.prevEvalCp = cp;
  analysis.lastMover  = mover;
}

analysisToggleEl.addEventListener("click", () => setAnalysisEnabled(!analysis.enabled));

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

// ---------- setup mode (puzzle) -------------------------------------------

const setupPanelEl   = document.getElementById("setup-panel");
const setupHintEl    = document.getElementById("setup-hint");
const setupApplyBtn  = document.getElementById("btn-setup-apply");
const setupCancelBtn = document.getElementById("btn-setup-cancel");
const setupEmptyBtn  = document.getElementById("btn-setup-empty");
const setupStartBtn  = document.getElementById("btn-setup-start");
const puzzleToggleEl = document.getElementById("btn-puzzle-menu");

const STARTING_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

const setup = {
  active: false,
  board: {},                // sq -> piece char
  paletteSelection: null,   // piece char or "x" (delete) or null
  snapshot: null,           // { fen, autoPlay, autoSave, analysisOn } before entering
  dragFrom: null,           // source square during board-internal drag
};

function enterSetupMode() {
  if (setup.active) return;
  setup.snapshot = {
    fen: ((fenEl.value || "").trim()) || STARTING_FEN,
    autoPlay: !!stockfishUi.autoPlay,
    autoSave: !!(liveAutosaveEl && liveAutosaveEl.checked),
    analysisOn: !!analysis.enabled,
  };
  setup.active = true;
  if (fishAutoEl) fishAutoEl.checked = false;
  stockfishUi.autoPlay = false;
  if (liveAutosaveEl) liveAutosaveEl.checked = false;
  if (analysis.enabled) setAnalysisEnabled(false);
  if (timeSettings.enabled) pauseClocksAfterLoad();

  setup.board = parseFen(setup.snapshot.fen);
  setup.paletteSelection = null;
  selected = null;
  legalTargets = new Set();

  setupPanelEl.removeAttribute("hidden");
  puzzleToggleEl.setAttribute("aria-pressed", "true");
  boardEl.classList.add("setup-mode");

  renderSetupBoard();
  renderSetupPalette();
  log("Setup-Modus aktiv", "ok");
}

function exitSetupMode(restoreFlags) {
  if (!setup.active) return;
  const snap = setup.snapshot;
  setup.active = false;
  setup.paletteSelection = null;
  setup.dragFrom = null;
  setupPanelEl.setAttribute("hidden", "");
  puzzleToggleEl.setAttribute("aria-pressed", "false");
  boardEl.classList.remove("setup-mode");
  for (const cell of boardEl.children) {
    cell.classList.remove("setup-target", "dragging");
  }
  if (restoreFlags && snap) {
    if (fishAutoEl) fishAutoEl.checked = snap.autoPlay;
    stockfishUi.autoPlay = snap.autoPlay;
    if (liveAutosaveEl) liveAutosaveEl.checked = snap.autoSave;
    if (snap.analysisOn && !analysis.enabled) setAnalysisEnabled(true);
  }
  setup.snapshot = null;
}

function renderSetupBoard() {
  currentBoard = { ...setup.board };
  selected = null;
  legalTargets = new Set();
  lastMoveSquares = null;
  renderBoard();
  fenEl.value = buildFenFromSetup();
  fenDirty = false;
  updateSetupHint();
}

function renderSetupPalette() {
  document.querySelectorAll(".setup-piece").forEach(btn => {
    btn.classList.toggle("selected", btn.dataset.piece === setup.paletteSelection);
  });
}

function buildFenFromSetup() {
  const turnEl = document.querySelector('input[name="setup-turn"]:checked');
  const turn = turnEl ? turnEl.value : "w";
  const rows = [];
  for (let rank = 8; rank >= 1; rank--) {
    let row = "";
    let empty = 0;
    for (const f of FILES) {
      const sq = f + rank;
      const piece = setup.board[sq];
      if (piece) {
        if (empty > 0) { row += String(empty); empty = 0; }
        row += piece;
      } else {
        empty++;
      }
    }
    if (empty > 0) row += String(empty);
    rows.push(row);
  }
  return `${rows.join("/")} ${turn} - - 0 1`;
}

function validateSetup() {
  let wK = 0, bK = 0, wPawns = 0, bPawns = 0, wTotal = 0, bTotal = 0;
  let whiteKingSq = null, blackKingSq = null;
  for (const [sq, p] of Object.entries(setup.board)) {
    const isWhite = p === p.toUpperCase();
    if (isWhite) wTotal++; else bTotal++;
    if (p === "K") { wK++; whiteKingSq = sq; }
    if (p === "k") { bK++; blackKingSq = sq; }
    if (p === "P") {
      wPawns++;
      if (sq[1] === "1" || sq[1] === "8") return "Bauern dürfen nicht auf Reihe 1 oder 8 stehen";
    }
    if (p === "p") {
      bPawns++;
      if (sq[1] === "1" || sq[1] === "8") return "Bauern dürfen nicht auf Reihe 1 oder 8 stehen";
    }
  }
  if (wK !== 1) return "Genau ein weißer König nötig";
  if (bK !== 1) return "Genau ein schwarzer König nötig";
  if (wPawns > 8) return "Maximal 8 weiße Bauern";
  if (bPawns > 8) return "Maximal 8 schwarze Bauern";
  if (wTotal > 16) return "Maximal 16 weiße Figuren";
  if (bTotal > 16) return "Maximal 16 schwarze Figuren";
  const wf = FILES.indexOf(whiteKingSq[0]);
  const wr = parseInt(whiteKingSq[1], 10);
  const bf = FILES.indexOf(blackKingSq[0]);
  const br = parseInt(blackKingSq[1], 10);
  if (Math.abs(wf - bf) <= 1 && Math.abs(wr - br) <= 1) {
    return "Könige dürfen nicht direkt nebeneinander stehen";
  }
  return null;
}

function updateSetupHint() {
  const err = validateSetup();
  if (err) {
    setupHintEl.textContent = err;
    setupHintEl.classList.add("err");
    setupApplyBtn.disabled = true;
  } else {
    setupHintEl.textContent = "Stellung gültig — „Übernehmen“ lädt sie ins Spiel.";
    setupHintEl.classList.remove("err");
    setupApplyBtn.disabled = false;
  }
}

function setupSquareClick(sq) {
  const sel = setup.paletteSelection;
  if (!sel) return;
  if (sel === "x") {
    const piece = setup.board[sq];
    if (!piece) return;
    if (piece === "K" || piece === "k") {
      log("König kann nicht gelöscht werden — verschiebe ihn per Drag", "err");
      return;
    }
    delete setup.board[sq];
  } else {
    if (sel === "K" || sel === "k") {
      for (const [s, p] of Object.entries(setup.board)) {
        if (p === sel && s !== sq) delete setup.board[s];
      }
    }
    setup.board[sq] = sel;
  }
  renderSetupBoard();
}

function setupDragStart(e, sq) {
  if (!setup.board[sq]) { e.preventDefault(); return; }
  if (setup.paletteSelection) {
    setup.paletteSelection = null;
    renderSetupPalette();
  }
  setup.dragFrom = sq;
  try {
    e.dataTransfer.effectAllowed = "move";
    e.dataTransfer.setData("text/plain", sq);
  } catch (_) {}
  const src = e.currentTarget;
  const cs   = getComputedStyle(src);
  const size = src.getBoundingClientRect();
  const ghost = document.createElement("div");
  ghost.className = "drag-ghost";
  ghost.textContent = PIECE_GLYPHS[setup.board[sq].toLowerCase()];
  ghost.style.width      = `${size.width}px`;
  ghost.style.height     = `${size.height}px`;
  ghost.style.fontSize   = cs.fontSize;
  ghost.style.fontFamily = cs.fontFamily;
  ghost.style.color      = cs.color;
  ghost.style.textShadow = cs.textShadow;
  document.body.appendChild(ghost);
  try { e.dataTransfer.setDragImage(ghost, size.width / 2, size.height / 2); } catch (_) {}
  setTimeout(() => ghost.remove(), 0);
  src.classList.add("dragging");
}

function setupDragOver(e) {
  if (!setup.dragFrom) return;
  e.preventDefault();
  e.dataTransfer.dropEffect = "move";
  e.currentTarget.classList.add("setup-target");
}

function setupDragLeave(e) {
  e.currentTarget.classList.remove("setup-target");
}

function setupDrop(e, sq) {
  e.preventDefault();
  e.currentTarget.classList.remove("setup-target");
  const from = setup.dragFrom || e.dataTransfer.getData("text/plain");
  setup.dragFrom = null;
  if (!from) { renderSetupBoard(); return; }
  if (from === sq) { renderSetupBoard(); return; }
  const piece = setup.board[from];
  if (!piece) { renderSetupBoard(); return; }
  delete setup.board[from];
  setup.board[sq] = piece;
  renderSetupBoard();
}

function setupDragEnd() {
  setup.dragFrom = null;
  for (const cell of boardEl.children) {
    cell.classList.remove("setup-target", "dragging");
  }
}

async function applySetup() {
  const err = validateSetup();
  if (err) { log(`Setup ungültig: ${err}`, "err"); return; }
  const fen = buildFenFromSetup();
  try {
    await api("POST", "/api/fen", { fen });
    fenDirty = false;
    resultModalShownFor = null;
    manualGameOver = false;
    analysis.prevEvalCp = null;
    exitSetupMode(true);
    await refreshState();
    await refreshPgn();
    resetClocks();
    renderClocks();
    log("Stellung übernommen", "ok");
    if (analysis.enabled) requestAnalysis(null);
    syncStockfishUiState({ trigger: "setup-apply" });
  } catch (e) {
    log(`Setup fehlgeschlagen: ${e.message}`, "err");
  }
}

async function cancelSetup() {
  exitSetupMode(true);
  try {
    await refreshState();
    await refreshPgn();
  } catch (e) { log(`Setup-Abbruch: ${e.message}`, "err"); }
  log("Setup abgebrochen", "ok");
}

puzzleToggleEl.addEventListener("click", () => {
  if (setup.active) cancelSetup(); else enterSetupMode();
});

document.querySelectorAll(".setup-piece").forEach(btn => {
  btn.addEventListener("click", () => {
    const piece = btn.dataset.piece;
    setup.paletteSelection = (setup.paletteSelection === piece) ? null : piece;
    renderSetupPalette();
  });
});

document.querySelectorAll('input[name="setup-turn"]').forEach(el => {
  el.addEventListener("change", () => {
    if (!setup.active) return;
    fenEl.value = buildFenFromSetup();
    fenDirty = false;
    updateSetupHint();
  });
});

setupEmptyBtn.addEventListener("click", () => {
  if (!setup.active) return;
  setup.board = {};
  renderSetupBoard();
});

setupStartBtn.addEventListener("click", () => {
  if (!setup.active) return;
  setup.board = parseFen(STARTING_FEN);
  renderSetupBoard();
});

setupApplyBtn.addEventListener("click", applySetup);
setupCancelBtn.addEventListener("click", cancelSetup);

// ---------- lichess bot menu ----------------------------------------------

const lichessMenuEl     = document.getElementById("lichess-menu");
const lichessMenuToggle = document.getElementById("btn-lichess-menu");
const lichessStatusLbl  = document.getElementById("lichess-status-label");
const lichessBoardState = document.getElementById("lichess-board-state");
const lichessBotState   = document.getElementById("lichess-bot-state");
const lichessChallengeBtn  = document.getElementById("btn-lichess-challenge");
const lichessResignBtn     = document.getElementById("btn-lichess-resign");
const lichessDisconnectBtn = document.getElementById("btn-lichess-disconnect");
const lichessModeRadios    = document.querySelectorAll('input[name="lichess-mode"]');
const lichessUsernameEl    = document.getElementById("lichess-bot-username");
const lichessTimeEl        = document.getElementById("lichess-time-control");
const lichessTimeActiveEl  = document.getElementById("lichess-time-active-label");
const lichessTimeHintEl    = document.getElementById("lichess-time-hint");
const lichessRatedEl       = document.getElementById("chk-lichess-rated");

// Mirrors the dropdown selection into a label below it and warns when the
// user picks a correspondence mode (most Lichess bots — Maia included —
// refuse correspondence challenges with `declineReason: "timecontrol"`).
function syncLichessTimeLabel() {
  if (!lichessTimeEl) return;
  const opt = lichessTimeEl.options[lichessTimeEl.selectedIndex];
  const isCorrespondence = /d$/.test(lichessTimeEl.value);
  if (lichessTimeActiveEl) lichessTimeActiveEl.textContent = opt?.textContent?.trim() || lichessTimeEl.value;
  if (lichessTimeHintEl) {
    if (isCorrespondence) lichessTimeHintEl.removeAttribute("hidden");
    else lichessTimeHintEl.setAttribute("hidden", "");
  }
}
if (lichessTimeEl) {
  lichessTimeEl.addEventListener("change", syncLichessTimeLabel);
  syncLichessTimeLabel();
}

const lichessState = {
  session: null,
  pollTimer: null,
  autoBusy: false,
  lastLoggedSignature: null, // dedupe poll-driven status logs
};

// Emit a check/mate/draw note when the underlying status changes since the
// last poll, so the user sees "Schach!" after maia1's reply without spamming
// the same line on every 1.5s tick.
function maybeLogSessionTransition(session) {
  if (!session) { lichessState.lastLoggedSignature = null; return; }
  const sig = [
    session.gameOver ? "over" : "live",
    session.status || "",
    session.inCheck || "-",
    session.winner || "-",
    (session.moves || []).length
  ].join("|");
  if (sig === lichessState.lastLoggedSignature) return;
  lichessState.lastLoggedSignature = sig;
  const note = describeLichessStatus(session);
  if (note) log(note, "ok");
}

function isLichessSessionActive() {
  return !!(lichessState.session && lichessState.session.gameId && !lichessState.session.gameOver);
}

// True when the Lichess stream is alive and reflecting the current game.
// Used to gate move actions — sending a move before the stream confirms the
// game exists is what abandoned our first smoke-test challenges.
function isLichessStreamReady(session) {
  return !!session && session.streamStatus === "streaming";
}

// Stockfish-vs-Bot mode requires the bot token and an active bot session
// where it's our turn. Auto-move triggers automatically from poll updates.
function shouldAutoMoveOnLichess(session) {
  if (!session || !session.gameId) return false;
  if (session.mode !== "bot") return false;
  if (session.gameOver) return false;
  if (!session.yourTurn) return false;
  if (!isLichessStreamReady(session)) return false;
  return true;
}

function setTokenBadge(el, ok) {
  if (!el) return;
  el.classList.remove("up", "down");
  el.classList.add(ok ? "up" : "down");
  el.textContent = ok ? "vorhanden" : "fehlt";
}

function updateLichessModeAvailability(status) {
  // "Stockfish spielt" requires bot token. Disable the radio if not present
  // and fall back to board mode.
  const botRadio = document.querySelector('input[name="lichess-mode"][value="bot"]');
  if (botRadio) {
    const allowed = !!status?.botToken;
    botRadio.disabled = !allowed;
    if (!allowed && botRadio.checked) {
      const boardRadio = document.querySelector('input[name="lichess-mode"][value="board"]');
      if (boardRadio) boardRadio.checked = true;
    }
  }
}

function applyLichessSession(session) {
  if (!session || !session.fen) return;
  lichessState.session = session;
  if (fishAutoEl) {
    fishAutoEl.checked = false;
    stockfishUi.autoPlay = false;
  }
  fenDirty = false;
  currentBoard = parseFen(session.fen);
  if (!fenDirty) fenEl.value = session.fen;
  currentTurn = session.turn || (session.fen.split(" ")[1] === "b" ? "black" : "white");
  currentGameOver = !!session.gameOver;
  // Don't clobber drag state mid-drag. The 1.5s polling cycle would otherwise
  // empty `legalTargets` while a piece is still airborne; the user's drop
  // would then silently fail on `legalTargets.has(sq) === false`.
  if (!dragFrom) {
    selected = null;
    legalTargets = new Set();
  }
  lastMoveSquares = parseLastMove(session.lastMove);
  setBoardFlipped(session.yourColor === "black");
  statusEl.textContent = `Lichess: ${session.status || "aktiv"}`;
  applyLichessClocks(session);
  renderBoard();
}

// Snapshot of the latest authoritative Lichess clock values plus the wall-
// clock time at which they were captured. tickClocks renders by computing
// `snapshotValue - (now - snapshotAt)` for the running side. The displayed
// value (`displayWhiteMs` / `displayBlackMs`) is anti-jump: it never rises
// from one render to the next. That hides the +increment spike that
// otherwise arrives with poll latency after every move, and avoids the ±1s
// jitter caused by drift between local tick and Lichess's authoritative
// values. The cost: the displayed value can lag truth by the cumulative
// unspent-increment time. We treat that as the lesser evil.
const lichessClock = {
  gameId: null,
  whiteMs: null,
  blackMs: null,
  runningSide: null,
  snapshotAt: null,
  displayWhiteMs: null,
  displayBlackMs: null,
};

function applyLichessClocks(session) {
  const hasClocks =
    typeof session.whiteMs === "number" && typeof session.blackMs === "number";
  // Reset the anti-jump baseline whenever we enter a new Lichess game,
  // otherwise the displayed values from the previous game would clamp the
  // initial values of the new one.
  if (lichessClock.gameId !== session.gameId) {
    lichessClock.gameId = session.gameId;
    lichessClock.displayWhiteMs = null;
    lichessClock.displayBlackMs = null;
  }
  if (!hasClocks) {
    timeSettings.enabled = false;
    lichessClock.whiteMs = null;
    lichessClock.blackMs = null;
    lichessClock.runningSide = null;
    clocks.runningSide = null;
    clocks.lastTickAt = null;
    renderClocks();
    return;
  }
  timeSettings.enabled          = true;
  timeSettings.baseSeconds      = Math.round((session.clockInitialMs || 0) / 1000);
  timeSettings.incrementSeconds = Math.round((session.clockIncrementMs || 0) / 1000);
  timeSettings.modeName         = `Lichess ${Math.round(timeSettings.baseSeconds / 60)}+${timeSettings.incrementSeconds}`;
  const moveCount = (session.moves || []).length;
  lichessClock.whiteMs    = session.whiteMs;
  lichessClock.blackMs    = session.blackMs;
  lichessClock.snapshotAt = performance.now();
  // Lichess starts the clock only after move 1. Setting runningSide before
  // then would let the local tick visibly count down while the poll keeps
  // snapping back to the initial value.
  lichessClock.runningSide =
    (session.gameOver || moveCount === 0 || !session.turn) ? null : session.turn;
  clocks.firstMovePlayed = moveCount > 0;
  clocks.expired = false;
  clocks.expiredSide = null;
  renderLichessClocks();
}

// Compute effective remaining time from the snapshot + elapsed wall-clock
// time, then apply anti-jump-up (the display can only decrease). Pushed into
// `clocks.whiteMs/blackMs` so the existing paintClock renderer stays
// unchanged.
function renderLichessClocks() {
  if (lichessClock.whiteMs == null || lichessClock.blackMs == null) return false;
  let whiteTruth = lichessClock.whiteMs;
  let blackTruth = lichessClock.blackMs;
  if (lichessClock.runningSide && lichessClock.snapshotAt != null) {
    const elapsed = performance.now() - lichessClock.snapshotAt;
    if (lichessClock.runningSide === "white") whiteTruth = Math.max(0, whiteTruth - elapsed);
    else                                       blackTruth = Math.max(0, blackTruth - elapsed);
  }
  // Anti-jump: displayed value never goes up.
  if (lichessClock.displayWhiteMs == null || whiteTruth < lichessClock.displayWhiteMs) {
    lichessClock.displayWhiteMs = whiteTruth;
  }
  if (lichessClock.displayBlackMs == null || blackTruth < lichessClock.displayBlackMs) {
    lichessClock.displayBlackMs = blackTruth;
  }
  clocks.whiteMs = lichessClock.displayWhiteMs;
  clocks.blackMs = lichessClock.displayBlackMs;
  clocks.runningSide = lichessClock.runningSide;
  renderClocks();
  return true;
}

function handleLichessStatus(status, { paintBoard = false } = {}) {
  setTokenBadge(lichessBoardState, !!status.boardToken);
  setTokenBadge(lichessBotState,   !!status.botToken);
  lichessState.session = status.session || null;

  if (!status.boardToken && !status.botToken) {
    lichessStatusLbl.textContent = "kein Token konfiguriert";
  } else if (status.session?.gameId) {
    const sess = status.session;
    const turn = sess.yourTurn ? "du bist dran" : "wartet";
    const stream = sess.streamStatus && sess.streamStatus !== "streaming"
      ? ` · ${sess.streamStatus}`
      : "";
    lichessStatusLbl.textContent = `aktiv (${sess.gameId}, ${turn})${stream}`;
  } else {
    lichessStatusLbl.textContent = "bereit";
  }

  const hasSession = !!status.session?.gameId;
  lichessResignBtn.disabled     = !hasSession || !!status.session?.gameOver;
  lichessDisconnectBtn.disabled = !hasSession;
  lichessChallengeBtn.disabled  = hasSession || (!status.boardToken && !status.botToken);
  updateLichessModeAvailability(status);

  if (paintBoard && status.session) applyLichessSession(status.session);
  if (hasSession && !status.session?.gameOver) startLichessPolling();
  if (!hasSession) stopLichessPolling();

  maybeLogSessionTransition(status.session);
  if (shouldAutoMoveOnLichess(status.session)) requestLichessAutoMove();
}

async function refreshLichessStatus() {
  try {
    const status = await api("GET", "/api/lichess/status");
    handleLichessStatus(status);
  } catch (e) {
    setTokenBadge(lichessBoardState, false);
    setTokenBadge(lichessBotState,   false);
    lichessStatusLbl.textContent = "Backend offline";
    lichessChallengeBtn.disabled  = true;
    lichessResignBtn.disabled     = true;
    lichessDisconnectBtn.disabled = true;
    stopLichessPolling();
  }
}

function toggleLichessMenu(force) {
  const open = force !== undefined ? force : lichessMenuEl.hasAttribute("hidden");
  if (open) {
    lichessMenuEl.removeAttribute("hidden");
    lichessMenuToggle.setAttribute("aria-expanded", "true");
    refreshLichessStatus();
  } else {
    lichessMenuEl.setAttribute("hidden", "");
    lichessMenuToggle.setAttribute("aria-expanded", "false");
  }
}

if (lichessMenuToggle) {
  lichessMenuToggle.addEventListener("click", (e) => {
    e.stopPropagation();
    toggleLichessMenu();
  });
  document.addEventListener("click", (e) => {
    if (lichessMenuEl.hasAttribute("hidden")) return;
    if (lichessMenuEl.contains(e.target) || lichessMenuToggle.contains(e.target)) return;
    toggleLichessMenu(false);
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !lichessMenuEl.hasAttribute("hidden")) toggleLichessMenu(false);
  });
}

function startLichessPolling() {
  if (lichessState.pollTimer) return;
  // 500 ms keeps the clock display within half a second of Lichess truth,
  // which makes Fischer-increment jumps appear close to the move that
  // caused them. Each poll is a cheap /session read against an in-memory
  // session — no upstream call.
  lichessState.pollTimer = setInterval(pollLichessSession, 500);
}

function stopLichessPolling() {
  if (!lichessState.pollTimer) return;
  clearInterval(lichessState.pollTimer);
  lichessState.pollTimer = null;
}

async function pollLichessSession() {
  try {
    const status = await api("GET", "/api/lichess/session");
    handleLichessStatus(status, { paintBoard: true });
  } catch (e) {
    log(`Lichess-Session: ${e.message}`, "err");
    stopLichessPolling();
  }
}

function selectedRadioValue(name, fallback) {
  const el = document.querySelector(`input[name="${name}"]:checked`);
  return el ? el.value : fallback;
}

function readLichessChallengeSettings() {
  return {
    username: (lichessUsernameEl?.value || "").trim(),
    mode: selectedRadioValue("lichess-mode", "board"),
    color: selectedRadioValue("lichess-color", "random"),
    timeControl: lichessTimeEl?.value || "3+2",
    rated: !!lichessRatedEl?.checked,
  };
}

async function createLichessChallenge() {
  const payload = readLichessChallengeSettings();
  if (!payload.username) {
    log("Bitte Lichess-Bot-Username eingeben", "err");
    return;
  }
  // Lichess usernames are alphanumeric + "-_" only. Reject whitespace early
  // so we don't get an opaque 404 from Lichess later.
  if (!/^[A-Za-z0-9_-]+$/.test(payload.username)) {
    log(`Ungültiger Lichess-Username: "${payload.username}" (nur Buchstaben/Zahlen/-/_ erlaubt)`, "err");
    return;
  }
  try {
    lichessChallengeBtn.disabled = true;
    const status = await api("POST", "/api/lichess/challenge", payload);
    handleLichessStatus(status, { paintBoard: true });
    const modeLabel = payload.mode === "bot" ? "Stockfish-vs-Bot" : "Mensch";
    log(`Lichess-Challenge an ${payload.username} gesendet (${modeLabel})`, "ok");
  } catch (e) {
    log(`Lichess-Challenge: ${e.message}`, "err");
  } finally {
    refreshLichessStatus();
  }
}

// Ask the backend to compute a Stockfish move and send it to Lichess via the
// Bot API. Only valid when session.mode === "bot" (Fair Play). Guarded by
// `autoBusy` so concurrent triggers don't pile up engine requests.
async function requestLichessAutoMove() {
  if (lichessState.autoBusy) return;
  const sess = lichessState.session;
  if (!shouldAutoMoveOnLichess(sess)) return;
  lichessState.autoBusy = true;
  try {
    const cfg = effectiveFishConfig();
    const payload = {};
    if (cfg.depth    != null) payload.depth    = cfg.depth;
    if (cfg.movetime != null) payload.movetime = cfg.movetime;
    if (cfg.skill    != null) payload.skill    = cfg.skill;
    const res = await api("POST", "/api/lichess/auto-move", payload);
    handleLichessStatus(res.status, { paintBoard: true });
    log(`Lichess-Bot-Zug: ${res.move}`, "ok");
  } catch (e) {
    log(`Auto-Move: ${e.message}`, "err");
  } finally {
    lichessState.autoBusy = false;
  }
}

async function sendLichessMove(from, to, promotion, { wasCapture = false } = {}) {
  // Gate manual moves the same way auto-move is gated. Without an open NDJSON
  // stream the game either doesn't exist yet (challenge not accepted) or was
  // declined — sending the move would just earn a 404 from Lichess.
  if (!isLichessStreamReady(lichessState.session)) {
    const why = lichessState.session?.streamStatus || "kein Stream";
    log(`Lichess-Zug blockiert: Stream nicht aktiv (${why})`, "err");
    clearSelection();
    return;
  }
  const body = promotion ? { from, to, promotion } : { from, to };
  try {
    const status = await api("POST", "/api/lichess/move", body);
    handleLichessStatus(status, { paintBoard: true });
    const session = status.session || {};
    log(`Lichess-Zug ${from}${to}${promotion ? "=" + promotion.toUpperCase() : ""}`, "ok");
    playMoveSound(
      { status: session.status || "", gameOver: !!session.gameOver },
      { capture: wasCapture, promotion: !!promotion }
    );
  } catch (e) {
    log(`Lichess-Zug abgelehnt: ${e.message}`, "err");
    clearSelection();
  }
}

async function resignLichessGame() {
  if (!lichessState.session?.gameId) return;
  if (!confirm("Lichess-Partie wirklich aufgeben?")) return;
  try {
    const status = await api("POST", "/api/lichess/resign");
    handleLichessStatus(status, { paintBoard: true });
    log("Lichess-Partie aufgegeben", "err");
  } catch (e) {
    log(`Lichess-Aufgabe: ${e.message}`, "err");
  }
}

async function disconnectLichessGame() {
  try {
    await api("POST", "/api/lichess/disconnect");
    lichessState.session = null;
    stopLichessPolling();
    await refreshState();
    log("Lichess getrennt", "ok");
    refreshLichessStatus();
  } catch (e) {
    log(`Lichess-Trennen: ${e.message}`, "err");
  }
}

if (lichessChallengeBtn) {
  lichessChallengeBtn.addEventListener("click", createLichessChallenge);
}
if (lichessResignBtn) {
  lichessResignBtn.addEventListener("click", resignLichessGame);
}
if (lichessDisconnectBtn) {
  lichessDisconnectBtn.addEventListener("click", disconnectLichessGame);
}

// ---------- boot -----------------------------------------------------------

buildBoard();
loadTheme();
loadSoundPrefs();
preloadSounds();
loadAnalysisPref();
renderTimePresets();
renderClocks();
refreshState()
  .then(() => syncStockfishUiState({ trigger: "boot", maybeMove: false }))
  .catch(e => log(`init: ${e.message}`, "err"));
refreshPersistenceStatus();
refreshLiveStatus();
refreshStockfishStatus();
refreshLichessStatus();
pingHealth();
setInterval(pingHealth, 5000);
setInterval(refreshStockfishStatus, 5000);
