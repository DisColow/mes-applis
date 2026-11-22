"use strict";
/* Interface de MicBoard. Toutes les actions passent par window.pywebview.api (micboard/api.py). */

const api = new Proxy({}, { get: (_t, name) => (...args) => window.pywebview.api[name](...args) });
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const icon = (id) => `<svg><use href="#i-${id}"/></svg>`;
const fold = (s) => s.normalize("NFKD").replace(/[̀-ͯ]/g, "").toLowerCase();

const S = {
  view: "browse",       // browse | favorites | disk
  folder: "",           // dossier affiché, relatif à la bibliothèque (« » = racine)
  query: "",
  limit: 240,
  results: [],          // sons affichés (contenu du dossier ou résultats de recherche)
  total: 0,
  cats: [],
  tree: [],
  open: new Set(),      // dossiers dépliés dans l'arbre
  sel: -1,
  running: false,
  mix: {},
  disk: { sid: null, since: 0, rows: [], timer: null, roots: [], on: new Set() },
};
try { S.open = new Set(JSON.parse(localStorage.getItem("micboard.open") || "[]")); } catch { /* stockage indisponible */ }
const saveOpen = () => { try { localStorage.setItem("micboard.open", JSON.stringify([...S.open])); } catch { /* idem */ } };

/* ------------------------------------------------------------------ démarrage */
async function init() {
  await refreshTree();
  const dev = await api.devices();
  applyMix(dev);
  await load();
  setInterval(pollStatus, 110);
  updateBanner();
  if (await api.first_run()) openWizard();
}
if (window.pywebview && window.pywebview.api) init();
else window.addEventListener("pywebviewready", init, { once: true });

/* ------------------------------------------------------------------ arborescence */
const topOf = (rel) => (rel || "").split("/")[0];
const catOf = (name) => S.cats.find((c) => c.name === name) || { color: "#8b6cff", icon: "folder", short: name };
const leaf = (rel) => (rel ? rel.split("/").pop() : "Bibliothèque");
const parentOf = (rel) => rel.split("/").slice(0, -1).join("/");
const plural = (n, word) => `${n.toLocaleString("fr-FR")} ${word}${n > 1 ? "s" : ""}`;

async function refreshTree() {
  [S.cats, S.tree] = await Promise.all([api.categories(), api.tree()]);
  renderTree();
}

function renderTree() {
  const total = S.tree.reduce((n, t) => n + t.count, 0);
  const node = (t, d) => {
    const c = catOf(topOf(t.rel));
    const kids = t.children.length;
    const open = kids && S.open.has(t.rel);
    return `<button class="tnode${d === 0 ? " top" : ""}${open ? " open" : ""}" style="--d:${d};--c:${c.color}"
        data-rel="${esc(t.rel)}" title="${esc(t.rel)}" type="button">
      <span class="tw${kids ? "" : " leaf"}" data-tw>${icon("chevron")}</span>
      <span class="ti">${icon(d === 0 ? c.icon : "folder-fill")}</span>
      <span class="tl">${esc(t.name)}</span><em>${t.count}</em>
    </button>${open ? `<div class="tchildren" style="--d:${d}">${t.children.map((k) => node(k, d + 1)).join("")}</div>` : ""}`;
  };
  $("#tree").innerHTML = `<button class="tnode root" style="--d:0" data-rel="" type="button">
      <span class="tw leaf">${icon("chevron")}</span><span class="ti">${icon("grid")}</span>
      <span class="tl">Tous les sons</span><em>${total}</em></button>` + S.tree.map((t) => node(t, 0)).join("");
  markNav();
}

function markNav() {
  $$(".nav-item[data-view]").forEach((b) => b.classList.toggle("active", b.dataset.view === S.view));
  $$(".tnode").forEach((b) => b.classList.toggle("active", S.view === "browse" && b.dataset.rel === S.folder));
  $(".tnode.active")?.scrollIntoView({ block: "nearest" });
}

$("#tree").addEventListener("click", (e) => {
  const n = e.target.closest(".tnode");
  if (!n) return;
  const rel = n.dataset.rel;
  if (e.target.closest("[data-tw]") && !e.target.closest(".leaf")) {   // flèche : plier / déplier seulement
    S.open.has(rel) ? S.open.delete(rel) : S.open.add(rel);
    saveOpen(); renderTree();
    return;
  }
  openFolder(rel);
});

$("#nav").addEventListener("click", (e) => {
  const b = e.target.closest(".nav-item");
  if (!b) return;
  if (b.dataset.action === "import") return openImport();
  setView(b.dataset.view);
});

function setView(view) {
  S.view = view;
  const disk = view === "disk";
  $("#view-library").hidden = disk;
  $("#view-disk").hidden = !disk;
  markNav();
  if (disk) { initDisk(); $("#dq").focus(); return; }
  S.limit = 240;
  load();
  $("#lib-scroll").scrollTop = 0;
}

/** Ouvre un dossier : le déplie dans l'arbre avec tous ses parents, et affiche son contenu. */
function openFolder(rel) {
  const parts = rel ? rel.split("/") : [];
  parts.forEach((_p, i) => S.open.add(parts.slice(0, i + 1).join("/")));
  saveOpen();
  S.folder = rel;
  S.view = "browse";
  if (S.query) { S.query = ""; $("#q").value = ""; }
  $("#view-library").hidden = false;
  $("#view-disk").hidden = true;
  renderTree();
  S.limit = 400;
  load();
  $("#lib-scroll").scrollTop = 0;
}

/* ------------------------------------------------------------------ contenu : dossier ou recherche */
let searchTimer = null;
let loadSeq = 0;
$("#q").addEventListener("input", (e) => {
  S.query = e.target.value;
  S.limit = 240;
  clearTimeout(searchTimer);
  searchTimer = setTimeout(load, 90);
});

async function load() {
  const seq = ++loadSeq;
  const favs = await api.favorites();
  let data;
  if (S.view === "favorites" || S.query) {
    const scope = S.view === "favorites" ? "" : S.folder;
    data = await api.search(S.query, scope, S.view === "favorites", S.limit);
    data.mode = "search";
  } else {
    data = await api.browse(S.folder, S.limit);
    data.mode = "browse";
    data.results = data.files;
    if (data.rel !== S.folder) { S.folder = data.rel; markNav(); } // dossier disparu entre-temps
  }
  if (seq !== loadSeq) return; // une frappe plus récente a déjà relancé
  S.results = data.results;
  S.total = data.total;
  S.sel = S.query && S.results.length ? 0 : -1;
  $("#count-fav").textContent = favs.length;
  renderCrumbs();
  renderHeading(data);
  renderFavStrip(data.mode === "browse" && !S.folder ? favs : []);
  renderFolders(data.mode === "browse" ? data.folders : []);
  renderGrid(data);
}

function renderCrumbs() {
  const el = $("#crumbs");
  if (S.view === "favorites") { el.innerHTML = ""; return; }
  const parts = S.folder ? S.folder.split("/") : [];
  const items = [{ name: "Bibliothèque", rel: "" }, ...parts.map((p, i) => ({ name: p, rel: parts.slice(0, i + 1).join("/") }))];
  el.innerHTML = `<button class="up" data-goto="${esc(parentOf(S.folder))}" title="Dossier parent (Retour arrière)" type="button" ${S.folder ? "" : "disabled"}>${icon("up")}</button>` +
    items.map((it, i) => {
      const last = i === items.length - 1;
      return `${i ? `<svg class="sep-ch"><use href="#i-chevron"/></svg>` : ""}<button class="${last ? "cur" : ""}" data-goto="${esc(it.rel)}" type="button">${i === 0 ? icon("grid") : ""}<span>${esc(it.name)}</span></button>`;
    }).join("");
}
$("#crumbs").addEventListener("click", (e) => {
  const b = e.target.closest("[data-goto]");
  if (b && !b.disabled && !b.classList.contains("cur")) openFolder(b.dataset.goto);
});

/* Mode d'affichage des sons : grille (compacte) ou liste (phrases entières, pour les titres
   qui contiennent la transcription de ce qui est dit). Retenu d'une fois sur l'autre. */
try { S.layout = localStorage.getItem("micboard.layout") === "list" ? "list" : "grid"; } catch { S.layout = "grid"; }
function layoutSwitchHTML() {
  return `<div class="seg" role="group" aria-label="Affichage">
    <button class="${S.layout === "grid" ? "on" : ""}" data-layout="grid" title="Grille : compacte" type="button">${icon("grid")}</button>
    <button class="${S.layout === "list" ? "on" : ""}" data-layout="list" title="Liste : phrases entières, pratique quand le titre est la transcription du son" type="button">${icon("rows")}</button>
  </div>`;
}
$("#lib-actions").addEventListener("click", (e) => {
  const b = e.target.closest("[data-layout]");
  if (!b || b.dataset.layout === S.layout) return;
  S.layout = b.dataset.layout;
  try { localStorage.setItem("micboard.layout", S.layout); } catch { /* stockage indisponible */ }
  $$("#lib-actions [data-layout]").forEach((x) => x.classList.toggle("on", x.dataset.layout === S.layout));
  load();
});

function renderHeading(d) {
  const h = $("#lib-title"), sub = $("#lib-sub"), acts = $("#lib-actions");
  if (S.view === "favorites") {
    h.textContent = "Favoris";
    sub.textContent = S.query ? `${plural(d.total, "résultat")} pour « ${S.query} »` : plural(d.total, "son");
    acts.innerHTML = layoutSwitchHTML();
    return;
  }
  const c = catOf(topOf(S.folder));
  h.innerHTML = S.folder
    ? `<span class="cat-ico" style="--c:${c.color}">${icon(S.folder.includes("/") ? "folder-fill" : c.icon)}</span>${esc(leaf(S.folder))}`
    : "Tous les sons";
  const where = S.folder ? `dans « ${esc(leaf(S.folder))} »` : "dans toute la bibliothèque";
  if (d.mode === "search") {
    sub.innerHTML = d.total
      ? `${plural(d.total, "résultat")} pour « ${esc(S.query)} » ${where} · Entrée pour jouer le premier`
      : `Aucun résultat pour « ${esc(S.query)} » ${where}`;
    if (S.folder) sub.innerHTML += ` · <button class="link scope" data-action="search-all" type="button">chercher partout</button>`;
  } else {
    const bits = [];
    if (d.folders.length) bits.push(plural(d.folders.length, "dossier"));
    bits.push(S.folder ? `${plural(d.count, "son")} au total` : plural(d.count, "son"));
    if (S.folder && d.folders.length && d.total) bits.push(`${d.total} ici`);
    sub.textContent = bits.join(" · ");
  }
  acts.innerHTML = layoutSwitchHTML() + `
    <button class="secondary small" data-action="import-folder" title="Copier un dossier du PC ici, avec tous ses sous-dossiers" type="button">${icon("folder-plus")}Importer un dossier</button>
    <button class="secondary small" data-action="add-files" type="button">${icon("plus")}Fichiers</button>
    <button class="secondary small" data-action="random" title="Un son au hasard dans ce dossier" type="button">${icon("dice")}</button>
    <button class="secondary small" data-action="open-here" title="Ouvrir dans l'Explorateur" type="button">${icon("folder")}</button>`;
}

function renderFavStrip(favs) {
  const strip = $("#fav-strip");
  if (!favs.length) { strip.innerHTML = ""; return; }
  strip.innerHTML = `<div class="pads-title">Favoris</div>` + favs.slice(0, 18).map((f) => {
    const c = catOf(f.category);
    return `<button class="pad" style="--c:${c.color}" data-path="${esc(f.path)}" title="${esc(f.dir)}/${esc(f.file)}" type="button">
      <b>${esc(f.name)}</b>
      <span class="pad-foot"><span>${esc(c.short)}</span>${f.hotkey ? `<kbd>${esc(f.hotkey)}</kbd>` : ""}</span>
    </button>`;
  }).join("");
}
$("#fav-strip").addEventListener("click", (e) => {
  const p = e.target.closest(".pad");
  if (p) play(p.dataset.path);
});

function renderFolders(folders) {
  const el = $("#folders");
  el.classList.toggle("big", !S.folder);
  if (!folders.length) { el.innerHTML = ""; return; }
  el.innerHTML = `<div class="section-title">${S.folder ? "Dossiers" : "Catégories"}</div>` + folders.map((f, i) => {
    const c = catOf(topOf(f.rel));
    const ic = S.folder ? "folder-fill" : c.icon;
    const info = [plural(f.count, "son"), f.subfolders ? plural(f.subfolders, "dossier") : ""].filter(Boolean).join(" · ");
    return `<button class="ftile" style="--c:${c.color};animation-delay:${Math.min(i, 20) * 10}ms" data-goto="${esc(f.rel)}" title="${esc(f.rel)}" type="button">
      <span class="fi">${icon(ic)}</span><div><b>${esc(f.name)}</b><small>${info}</small></div>
      <svg class="go"><use href="#i-chevron"/></svg></button>`;
  }).join("");
}
$("#folders").addEventListener("click", (e) => {
  const t = e.target.closest("[data-goto]");
  if (t) openFolder(t.dataset.goto);
});

function highlight(text) {
  const toks = fold(S.query).split(/[^0-9a-z]+/).filter(Boolean).sort((a, b) => b.length - a.length);
  if (!toks.length) return esc(text);
  const f = fold(text);
  const marks = new Array(text.length).fill(false);
  for (const t of toks) {
    let i = f.indexOf(t);
    while (i !== -1) { for (let k = i; k < i + t.length; k++) marks[k] = true; i = f.indexOf(t, i + t.length); }
  }
  let out = "", open = false;
  for (let i = 0; i < text.length; i++) {
    if (marks[i] !== open) { out += marks[i] ? "<mark>" : "</mark>"; open = marks[i]; }
    out += esc(text[i]);
  }
  return out + (open ? "</mark>" : "");
}

const fmtDur = (d) => (d == null ? "" : d < 60 ? `${d.toFixed(1).replace(".", ",")} s` : `${Math.floor(d / 60)} min ${Math.round(d % 60)} s`);

/** Chemin du dossier d'un son, vu depuis le dossier affiché (« » s'il est dedans directement). */
function relFromHere(dir) {
  if (S.view === "favorites" || !S.folder) return dir;
  return dir === S.folder ? "" : dir.slice(S.folder.length + 1);
}

function cardHTML(r, i, showPath) {
  const c = catOf(r.category);
  const where = showPath ? relFromHere(r.dir) : "";
  const pathHTML = where
    ? `<span>·</span><span class="folder goto" data-act="goto" title="Aller dans ${esc(r.dir)}">${highlight(where.split("/").slice(-2).join(" › "))}</span>`
    : "";
  const fromTags = r.source && r.source !== "file";
  const fileHTML = fromTags ? `<span>·</span><span class="folder file">${highlight(r.file)}</span>` : "";
  const tip = [r.title && `Titre : ${r.title}`, r.comment && `Commentaire : ${r.comment}`,
    `Fichier : ${r.dir ? `${r.dir}/` : ""}${r.file}`].filter(Boolean).join("\n");
  return `<div class="card${i === S.sel ? " sel" : ""}${fromTags ? " tagged" : ""}" style="--c:${c.color};animation-delay:${Math.min(i, 30) * 8}ms"
              data-i="${i}" data-path="${esc(r.path)}" title="${esc(tip)}" tabindex="-1">
    <span class="play">${icon("play")}</span>
    <div class="info">
      <div class="name">${highlight(r.name)}</div>
      <div class="meta"><span class="tag">${esc(showPath && !S.folder || S.view === "favorites" ? c.short : r.file.split(".").pop().toUpperCase())}</span>${pathHTML}${r.duration ? `<span>·</span><span>${fmtDur(r.duration)}</span>` : ""}${fileHTML}</div>
      ${S.layout === "list" && r.source === "title" && r.comment ? `<div class="note">${highlight(r.comment)}</div>` : ""}
    </div>
    <div class="badges">${r.hotkey ? `<kbd>${esc(r.hotkey)}</kbd>` : ""}${r.favorite ? `<svg class="star"><use href="#i-star"/></svg>` : ""}</div>
    <div class="acts">
      <button data-act="preview" title="Écouter dans mon casque seulement (Maj+Entrée)" type="button">${icon("headphones")}</button>
      <button data-act="fav" class="${r.favorite ? "fav-on" : ""}" title="${r.favorite ? "Retirer des favoris" : "Ajouter aux favoris"}" type="button">${icon(r.favorite ? "star" : "star-o")}</button>
      <button data-act="hotkey" title="Raccourci clavier" type="button">${icon("key")}</button>
      <button data-act="folder" title="Ouvrir dans l'Explorateur" type="button">${icon("folder")}</button>
    </div>
    <span class="bar"></span>
  </div>`;
}

function renderGrid(d) {
  const grid = $("#grid");
  const empty = $("#lib-empty");
  const showPath = d.mode === "search";
  grid.classList.toggle("list", S.layout === "list");
  const title = d.mode === "browse" && S.results.length && d.folders.length ? `<div class="section-title">Sons</div>` : "";
  grid.innerHTML = title + S.results.map((r, i) => cardHTML(r, i, showPath)).join("");
  $("#more").hidden = S.results.length >= S.total;
  const nothing = !S.results.length && !(d.mode === "browse" && d.folders.length);
  empty.hidden = !nothing;
  if (nothing) empty.innerHTML = emptyHTML(d);
}
$("#more").addEventListener("click", () => { S.limit += 400; load(); });

function emptyHTML(d) {
  if (S.query) return `<div class="empty-art">${icon("search")}</div><h2>Rien trouvé</h2>
    <p class="muted">Essaie un autre mot${S.folder ? `, <button class="link" data-action="search-all" type="button">cherche dans toute la bibliothèque</button>` : ""}
    ou cherche ce son <button class="link" data-action="disk-with-query" type="button">sur ton PC</button>.</p>`;
  if (S.view === "favorites") return `<div class="empty-art">${icon("star-o")}</div><h2>Pas encore de favoris</h2>
    <p class="muted">Clique sur l'étoile d'un son pour l'avoir toujours sous la main, en gros bouton.</p>`;
  if (S.folder) return `<div class="empty-art">${icon("folder")}</div><h2>Ce dossier est vide</h2>
    <p class="muted">Importe un dossier du PC ici (avec tous ses sous-dossiers) ou ajoute des fichiers.</p>
    <div class="modal-actions" style="justify-content:center">
      <button class="primary" data-action="import-folder" type="button">${icon("folder-plus")}Importer un dossier ici</button>
      <button class="secondary" data-action="add-files" type="button">${icon("plus")}Ajouter des fichiers</button></div>`;
  return `<div class="empty-art">${icon("music")}</div><h2>Ta bibliothèque est vide</h2>
    <p class="muted">Importe les sons depuis tes jeux installés, ou un dossier de sons de ton PC.</p>${gamesHTML()}`;
}

/* ------------------------------------------------------------------ cartes : souris et clavier */
$("#grid").addEventListener("click", (e) => {
  const card = e.target.closest(".card");
  if (!card) return;
  const r = S.results[+card.dataset.i];
  select(+card.dataset.i, false);
  const act = e.target.closest("[data-act]")?.dataset.act;
  if (!act) return play(r.path);
  if (act === "preview") return preview(r.path);
  if (act === "fav") return toggleFav(r);
  if (act === "hotkey") return openHotkey(r);
  if (act === "folder") return api.open_folder(r.path);
  if (act === "goto") return openFolder(r.dir);
});

function select(i, scroll = true) {
  $$(".card.sel").forEach((c) => c.classList.remove("sel"));
  S.sel = Math.max(-1, Math.min(i, S.results.length - 1));
  const card = $(`.card[data-i="${S.sel}"]`);
  if (card) { card.classList.add("sel"); if (scroll) card.scrollIntoView({ block: "nearest" }); }
}
function gridColumns() {
  return getComputedStyle($("#grid")).gridTemplateColumns.split(" ").length || 1;
}

document.addEventListener("keydown", (e) => {
  const modalOpen = !$("#modal").hidden;
  if (modalOpen) { if (e.key === "Escape" && !hotkeyCapture) closeModal(); return; }
  const inSearch = document.activeElement === $("#q");
  const typing = ["INPUT", "SELECT", "TEXTAREA"].includes(document.activeElement?.tagName) && !inSearch;
  if (S.view === "disk" || typing) {
    if (e.key === "Escape" && S.view === "disk") api.stop();
    return;
  }
  if ((e.key === "/" && !inSearch) || (e.ctrlKey && e.key.toLowerCase() === "f")) {
    e.preventDefault(); $("#q").focus(); $("#q").select(); return;
  }
  if (e.key === "Escape") {
    if (inSearch && S.query) { $("#q").value = ""; S.query = ""; load(); }
    else { api.stop(); $("#q").blur(); }
    return;
  }
  if (!inSearch && S.view === "browse" && S.folder && (e.key === "Backspace" || (e.altKey && e.key === "ArrowLeft"))) {
    e.preventDefault(); openFolder(parentOf(S.folder)); return;
  }
  const cols = gridColumns();
  const moves = { ArrowRight: 1, ArrowLeft: -1, ArrowDown: cols, ArrowUp: -cols };
  if (e.key in moves && S.results.length) {
    if (inSearch && (e.key === "ArrowLeft" || e.key === "ArrowRight")) return; // déplacer le curseur du texte
    e.preventDefault();
    if (e.key === "ArrowUp" && S.sel < cols && !inSearch) { $("#q").focus(); return; }
    if (inSearch) $("#q").blur();
    select(S.sel < 0 ? 0 : S.sel + moves[e.key]);
    return;
  }
  if (e.key === "Enter" && S.results.length) {
    e.preventDefault();
    const r = S.results[Math.max(0, S.sel)];
    e.shiftKey ? preview(r.path) : play(r.path);
  }
});

/* ------------------------------------------------------------------ lecture */
async function play(path) {
  const r = await api.play(path);
  if (!r.ok) toast(r.error, "err");
}
async function preview(path) {
  const r = await api.preview(path);
  if (!r.ok) toast(r.error, "err");
}
async function toggleFav(r) {
  r.favorite = await api.toggle_favorite(r.path);
  toast(r.favorite ? `« ${r.name} » ajouté aux favoris` : `« ${r.name} » retiré des favoris`);
  load();
}

const byPath = () => new Map(S.results.map((r) => [r.path, r]));
async function pollStatus() {
  let st;
  try { st = await api.status(); } catch { return; }
  setLive(st.running);
  wizardTick(st);
  if (S.libVersion !== undefined && st.lib_version !== S.libVersion && S.view !== "disk") load(); // titres lus
  S.libVersion = st.lib_version;
  if (st.message) toast(st.message, "err");
  const vu = $("#vu-mic");
  vu.style.width = `${Math.min(100, Math.sqrt(st.mic_level) * 100)}%`;

  const playing = new Map(st.playing.filter((p) => !p.preview).map((p) => [p.tag, p]));
  $$(".card").forEach((card) => {
    const p = playing.get(card.dataset.path) || st.playing.find((x) => x.tag === card.dataset.path);
    card.classList.toggle("playing", !!p);
    $(".bar", card).style.width = p ? `${p.progress * 100}%` : "0";
  });
  $$(".pad").forEach((pad) => pad.classList.toggle("playing", st.playing.some((x) => x.tag === pad.dataset.path)));
  const now = $("#now");
  const cur = st.playing[st.playing.length - 1];
  now.classList.toggle("active", !!cur);
  if (cur) {
    const r = byPath().get(cur.tag);
    const name = r ? r.name : cur.tag.split(/[\\/]/).pop().replace(/\.\w+$/, "");
    const c = r ? catOf(r.category) : { color: "#22d3ee" };
    $("#now-art").style.setProperty("--c", c.color);
    $("#now-name").textContent = name;
    const left = Math.max(0, cur.duration * (1 - cur.progress));
    const more = st.playing.length > 1 ? ` · +${st.playing.length - 1} autre${st.playing.length > 2 ? "s" : ""}` : "";
    $("#now-sub").textContent = `${cur.preview ? "Pré-écoute dans le casque" : "Dans le micro"} · ${left.toFixed(1).replace(".", ",")} s${more}`;
  } else {
    $("#now-name").textContent = "Rien en cours";
    $("#now-sub").textContent = S.running ? "Clique sur un son, Entrée ou un raccourci pour jouer" : "Active le micro pour envoyer les sons";
  }
}

function setLive(on) {
  if (on === S.running && $("#live").dataset.init) return;
  S.running = on;
  $("#live").dataset.init = 1;
  $("#live").classList.toggle("on", on);
  document.body.classList.toggle("on-air", on);
  $("#live-title").textContent = on ? "En direct" : "Micro hors ligne";
  $("#live-sub").textContent = on ? "Les sons partent dans CABLE Output" : "Clique pour activer";
}
$("#live").addEventListener("click", async () => {
  if (S.running) { await api.stop_engine(); setLive(false); return; }
  const r = await api.start();
  if (r.ok) { setLive(true); toast("C'est parti ! Choisis « CABLE Output » comme micro dans Discord ou ton jeu.", "ok"); }
  else { toast(r.error, "err"); openSettings(); }
});

/* ------------------------------------------------------------------ mélange */
function paintRange(el) {
  el.style.setProperty("--v", `${(el.value / el.max) * 100}%`);
}
function applyMix(dev) {
  S.mix = dev;
  $$("input[data-mix]").forEach((el) => { el.value = dev[el.dataset.mix] ?? 1; paintRange(el); });
  setMic(dev.mic_enabled !== false);
}
let mixTimer = null;
$$("input[data-mix]").forEach((el) => el.addEventListener("input", () => {
  paintRange(el);
  clearTimeout(mixTimer);
  mixTimer = setTimeout(() => api.set_mix(el.dataset.mix, parseFloat(el.value)), 40);
}));
function setMic(on) {
  S.mix.mic_enabled = on;
  const b = $("#mic-toggle");
  b.classList.toggle("off", !on);
  b.title = on ? "Ta voix est transmise (clique pour la couper)" : "Ta voix est coupée, seuls les sons passent";
  $("use", b).setAttribute("href", on ? "#i-mic" : "#i-mic-off");
}
$("#mic-toggle").addEventListener("click", () => {
  setMic(!S.mix.mic_enabled);
  api.set_mix("mic_enabled", S.mix.mic_enabled);
  toast(S.mix.mic_enabled ? "Ta voix est transmise" : "Voix coupée : seuls les sons passent");
});

/* ------------------------------------------------------------------ actions globales */
document.addEventListener("click", async (e) => {
  const a = e.target.closest("[data-action]");
  if (!a || a.closest("#nav")) return;
  const act = a.dataset.action;
  if (act === "settings") openSettings();
  else if (act === "help") openHelp();
  else if (act === "wizard") openWizard();
  else if (act === "import") openImport();
  else if (act === "close") closeModal();
  else if (act === "stop") api.stop();
  else if (act === "random") {
    const r = await api.random_sound(S.view === "browse" ? S.folder : "");
    if (!r) toast("Aucun son dans ce dossier", "err");
    else if (!S.running) toast("Active le micro pour envoyer les sons", "err");
  } else if (act === "open-here") api.open_folder((await api.browse(S.folder, 0)).path);
  else if (act === "search-all") { const q = S.query; openFolder(""); S.query = q; $("#q").value = q; load(); }
  else if (act === "import-folder") importFolder(S.view === "browse" ? S.folder : "");
  else if (act === "add-files") addFilesHere();
  else if (act === "disk-with-query") { $("#dq").value = S.query; setView("disk"); }
  else if (a.dataset.game) importGame(a.dataset.game, a.dataset.voices !== "0");
});

/** Libellé lisible d'un dossier de destination. */
const destLabel = (rel) => (rel ? `« ${leaf(rel)} »` : "une nouvelle catégorie");

async function afterImport(r, what) {
  if (r.cancelled) return false;
  if (r.error) { toast(`Import impossible : ${r.error}`, "err"); return false; }
  toast(`${plural(r.added, "son")} ${what} 🎉`, "ok");
  await refreshTree();
  if (r.rel) openFolder(r.rel); else load();
  return true;
}
async function importFolder(dest) {
  toast(`Choisis le dossier à copier dans ${destLabel(dest)}, avec tous ses sous-dossiers…`);
  if (await afterImport(await api.import_folder(dest), "importés avec leur arborescence")) closeModal();
}
async function addFilesHere() {
  const dest = S.view === "browse" && S.folder ? S.folder : "Mes sons";
  const r = await api.pick_files(dest);
  if (await afterImport(r, `ajoutés dans « ${leaf(dest)} »`)) openFolder(dest);
}
$("#modal").addEventListener("mousedown", (e) => { if (e.target.id === "modal") closeModal(); });

/* ------------------------------------------------------------------ notifications */
function toast(msg, kind = "") {
  const t = document.createElement("div");
  t.className = `toast ${kind}`;
  t.textContent = msg;
  $("#toasts").append(t);
  setTimeout(() => { t.classList.add("out"); setTimeout(() => t.remove(), 300); }, kind === "err" ? 5200 : 3200);
}

/* ------------------------------------------------------------------ fenêtres */
function openModal(html, narrow = false) {
  $("#modal-body").innerHTML = html;
  $(".modal-card").classList.toggle("narrow", narrow);
  $(".modal-card").classList.remove("wide");
  $("#modal").hidden = false;
}
function closeModal() {
  $("#modal").hidden = true;
  hotkeyCapture = null;
  W.open = false;
}

const GAMES = [
  { id: "et", cat: "Wolfenstein Enemy Territory", title: "Wolfenstein: Enemy Territory",
    text: "Choisis le dossier du jeu (ou ET: Legacy). Les voix : « Medic! », « Need backup! »…", voices: true },
  { id: "css", cat: "Counter-Strike Source", title: "Counter-Strike: Source",
    text: "Choisis cstrike\\cstrike_pak_dir.vpk dans Steam. La radio et les bots : « Fire in the hole! »…", voices: true },
  { id: "wc3", cat: "Warcraft III - Paysans", title: "Warcraft III",
    text: "Extrais d'abord le dossier « units » (CascView pour Reforged, MPQ Editor pour la version classique), puis choisis-le. Seuls les ouvriers sont gardés.", voices: false },
];
function gamesHTML() {
  return `<div class="games">${GAMES.map((g) => {
    const c = catOf(g.cat);
    return `<div class="game" style="--c:${c.color}">
      <span class="cat-ico">${icon(c.icon)}</span>
      <h3>${g.title}</h3><p>${esc(g.text)}</p>
      <div class="row">${g.voices
        ? `<button class="primary small" data-game="${g.id}" data-voices="1" type="button">Les voix</button>
           <button class="secondary small" data-game="${g.id}" data-voices="0" type="button">Tous les sons</button>`
        : `<button class="primary small" data-game="${g.id}" type="button">Choisir le dossier</button>`}</div>
    </div>`;
  }).join("")}</div>`;
}
function openImport() {
  openModal(`<h2>Importer depuis mes jeux</h2>
    <p class="muted">Les sons sont copiés depuis tes jeux installés vers ta bibliothèque MicBoard.</p>
    ${gamesHTML()}
    <div class="modal-actions">
      <button class="secondary" data-game="files" type="button">${icon("plus")}Ajouter des fichiers…</button>
      <button class="primary" data-action="import-folder" type="button">${icon("folder-plus")}Importer un dossier dans ${esc(destLabel(S.view === "browse" ? S.folder : ""))}</button>
    </div>
    <p class="muted" style="font-size:12px;margin:10px 0 0;text-align:right">Le dossier est copié avec tous ses sous-dossiers, et reste navigable tel quel.</p>`);
}
async function importGame(game, voices) {
  toast("Choisis l'emplacement dans la fenêtre qui s'ouvre…");
  const r = await api.import_game(game, voices);
  if (r.cancelled) return;
  if (r.error) { toast(`Import impossible : ${r.error}`, "err"); return; }
  closeModal();
  toast(`${plural(r.added, "son")} importé${r.added > 1 ? "s" : ""} 🎉`, "ok");
  await refreshTree();
  const target = { et: "Wolfenstein Enemy Territory", css: "Counter-Strike Source", wc3: "Warcraft III - Paysans", files: "Mes sons" }[game];
  openFolder(target || "");
}

/* réglages */
async function openSettings() {
  const d = await api.devices();
  if (d.error) { openModal(`<h2>Réglages</h2><p class="muted">${esc(d.error)}</p>`); return; }
  const opts = (list, sel, none) => (none ? `<option value="">${none}</option>` : "") +
    list.map((n) => `<option ${n === sel ? "selected" : ""}>${esc(n)}</option>`).join("");
  openModal(`<h2>Réglages</h2><p class="muted">Ton micro passe par MicBoard, qui y ajoute les sons, puis va dans le câble virtuel.</p>
    ${d.cable_found ? "" : `<div class="warn">${icon("help")}<div><b>« CABLE Input » introuvable.</b><br>
      Installe VB-Audio Virtual Cable (gratuit) depuis vb-audio.com/Cable, redémarre le PC, puis rouvre MicBoard.</div></div>`}
    <div class="form">
      <label class="lbl">Mon vrai micro</label><select data-dev="mic">${opts(d.inputs, d.selected.mic, "(aucun : seulement les sons)")}</select>
      <label class="lbl">Sortie vers le câble</label><select data-dev="cable">${opts(d.outputs, d.selected.cable)}</select>
      <span class="hint">Choisis « CABLE Input ». Dans Discord ou ton jeu, ton micro sera « CABLE Output ».</span>
      <label class="lbl">Retour casque</label><select data-dev="monitor">${opts(d.outputs, d.selected.monitor, "(aucun)")}</select>
      <span class="hint">Pour entendre les sons toi aussi, et pour la pré-écoute.</span>
      <label class="lbl">Pilote audio</label><select data-dev="host_api">${opts(d.apis, d.selected.host_api)}</select>
      <span class="hint">Si un périphérique refuse de s'ouvrir, essaie « MME ».</span>
      <div class="sep"></div>
      <label class="lbl">Lancer avec Windows</label>
      <div class="toggle-row"><span class="muted">${d.autostart_supported
        ? "MicBoard démarre caché dans la zone de notification, prêt pour tes raccourcis"
        : "Disponible seulement dans MicBoard.exe"}</span>
        <button class="tog ${d.autostart ? "on" : ""}" id="tog-autostart" type="button" ${d.autostart_supported ? "" : "disabled"}></button></div>
      <label class="lbl">Réduire près de l'horloge</label>
      <div class="toggle-row"><span class="muted">Le bouton « Réduire » range MicBoard dans la zone de notification (icône près de l'horloge)${d.tray_available ? "" : " — indisponible ici"}</span>
        <button class="tog ${d.tray_minimize ? "on" : ""}" id="tog-tray" type="button"></button></div>
      <label class="lbl">Micro actif au lancement</label>
      <div class="toggle-row"><span class="muted">Active le micro dès l'ouverture de MicBoard, sans cliquer sur « Micro hors ligne »</span>
        <button class="tog ${d.start_live ? "on" : ""}" id="tog-startlive" type="button"></button></div>
      <div class="sep"></div>
      <label class="lbl">Un seul son à la fois</label>
      <div class="toggle-row"><span class="muted">Un nouveau son coupe le précédent</span><button class="tog ${d.exclusive ? "on" : ""}" id="tog-excl" type="button"></button></div>
      <label class="lbl">Tout arrêter</label>
      <div class="toggle-row"><span>${d.stop_hotkey ? `<kbd>${esc(d.stop_hotkey)}</kbd>` : `<span class="muted">Échap dans MicBoard · aucun raccourci global</span>`}</span>
        <button class="secondary small" id="stop-key" type="button">${icon("key")}Définir</button></div>
      <label class="lbl">Texte des métadonnées</label><select id="tag-enc">${(d.tag_encodings || []).map((e) =>
        `<option value="${esc(e.id)}" ${e.id === d.tag_encoding ? "selected" : ""}>${esc(e.label)}</option>`).join("")}</select>
      <span class="hint">Si des accents s'affichent mal dans les noms des sons (« Pr�t », « Ã© »), choisis l'encodage de tes fichiers.</span>
      <label class="lbl">Dossier des sons</label>
      <div class="toggle-row"><span class="muted" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(d.sounds_root)}</span>
        <span style="display:flex;gap:6px"><button class="secondary small" id="root-open" type="button">Ouvrir</button><button class="secondary small" id="root-change" type="button">Changer…</button></span></div>
    </div>
    <div class="modal-actions">${S.running ? `<span class="muted" style="margin-right:auto;align-self:center">Les changements de périphérique s’appliquent quand tu réactives le micro.</span>` : ""}
      <button class="primary" data-action="close" type="button">Terminé</button></div>`);
  $$("select[data-dev]").forEach((s) => s.addEventListener("change", async () => {
    await api.set_device(s.dataset.dev, s.value);
    if (s.dataset.dev === "host_api") openSettings();
  }));
  $("#tog-autostart").addEventListener("click", async (e) => {
    const btn = e.currentTarget;
    const r = await api.set_autostart(!btn.classList.contains("on"));
    if (r.error) { toast(r.error, "err"); return; }
    btn.classList.toggle("on", r.autostart);
    toast(r.autostart ? "MicBoard se lancera avec Windows, dans la zone de notification" : "MicBoard ne se lancera plus avec Windows", "ok");
  });
  for (const [id, key] of [["#tog-tray", "tray_minimize"], ["#tog-startlive", "start_live"]]) {
    $(id).addEventListener("click", (e) => {
      const on = !e.currentTarget.classList.contains("on");
      e.currentTarget.classList.toggle("on", on);
      api.set_option(key, on);
    });
  }
  $("#tog-excl").addEventListener("click", (e) => {
    const on = !e.currentTarget.classList.contains("on");
    e.currentTarget.classList.toggle("on", on);
    api.set_mix("exclusive", on);
  });
  $("#stop-key").addEventListener("click", () => openHotkey(null));
  $("#tag-enc").addEventListener("change", async (e) => {
    await api.set_tag_encoding(e.target.value);
    toast("Les noms des sons vont être relus avec cet encodage…");
  });
  $("#root-open").addEventListener("click", () => api.open_folder());
  $("#root-change").addEventListener("click", async () => {
    await api.change_root();
    await refreshTree(); openFolder(""); openSettings();
  });
}

/* raccourcis clavier */
let hotkeyCapture = null;
const KEY_NAMES = { " ": "space", arrowup: "up", arrowdown: "down", arrowleft: "left", arrowright: "right",
  escape: "esc", control: "ctrl", meta: "windows", os: "windows", contextmenu: "menu", pageup: "page up", pagedown: "page down" };
/* Touches dont le caractère dépend de la disposition du clavier (AZERTY, QWERTY…) : on envoie leur
   position physique (e.code) et Python enregistre leur code matériel, qui ne dépend pas de la
   disposition (micboard/hotkeys.py). Les autres touches (F1, flèches…) gardent leur nom. */
const PHYSICAL = /^(Key[A-Z]|Digit\d|Numpad(\d|Add|Subtract|Multiply|Decimal)|Backquote|Minus|Equal|BracketLeft|BracketRight|Semicolon|Quote|Backslash|Comma|Period|Slash|IntlBackslash)$/;
const LABELS = { ctrl: "Ctrl", alt: "Alt", shift: "Maj", windows: "Win", space: "Espace", up: "↑", down: "↓",
  left: "←", right: "→", esc: "Échap", enter: "Entrée", tab: "Tab", backspace: "Retour arrière" };
/** { id envoyé à Python, label affiché selon le clavier de l'utilisateur } */
function keyName(e) {
  if (PHYSICAL.test(e.code)) {
    let label;
    if (e.code.startsWith("Numpad")) label = `Num ${{ Add: "+", Subtract: "-", Multiply: "*", Decimal: "," }[e.code.slice(6)] ?? e.code.slice(6)}`;
    else if (e.code.startsWith("Digit")) label = e.code.slice(5);
    else label = (e.key.length === 1 && e.key.trim() ? e.key : e.code.replace(/^Key/, "")).toUpperCase();
    return { id: e.code, label };
  }
  const k = e.key.toLowerCase();
  const id = KEY_NAMES[k] || k;
  return { id, label: LABELS[id] || (/^f\d+$/.test(id) ? id.toUpperCase() : id.length === 1 ? id.toUpperCase() : id) };
}
function openHotkey(r) {
  const forStop = r === null;
  openModal(`<h2>${forStop ? "Raccourci « tout arrêter »" : "Raccourci clavier"}</h2>
    <p class="muted">${forStop ? "Arrête tous les sons, même en plein jeu." : `Pour jouer « ${esc(r.name)} », même quand le jeu est au premier plan.`}</p>
    <div class="keycap-zone"><div class="keys" id="keys"><span class="muted">Appuie sur une touche ou une combinaison…</span></div></div>
    <p class="muted" style="font-size:12px">Astuce : les touches F5 à F12 ou le pavé numérique gênent rarement en jeu. Si ton jeu tourne en administrateur, lance MicBoard en administrateur aussi.</p>
    <div class="modal-actions">
      ${(forStop ? S.mix.stop_hotkey : r.hotkey) ? `<button class="secondary" id="hk-del" type="button" style="margin-right:auto">Supprimer le raccourci</button>` : ""}
      <button class="secondary" data-action="close" type="button">Annuler</button>
      <button class="primary" id="hk-save" type="button" disabled>Enregistrer</button>
    </div>`, true);
  hotkeyCapture = { combo: "" };
  const save = async (combo) => {
    let label;
    const seen = combo ? hotkeyCapture.label : "";
    if (forStop) { label = await api.set_stop_hotkey(combo, seen); S.mix.stop_hotkey = label; closeModal(); openSettings(); }
    else { label = await api.set_hotkey(r.path, combo, seen); closeModal(); load(); }
    toast(combo ? `Raccourci « ${label} » enregistré` : "Raccourci supprimé", "ok");
  };
  $("#hk-save").addEventListener("click", () => save(hotkeyCapture.combo));
  $("#hk-del")?.addEventListener("click", () => save(""));
}
document.addEventListener("keydown", (e) => {
  if (!hotkeyCapture || $("#modal").hidden) return;
  e.preventDefault();
  e.stopPropagation();
  const MODS = ["ctrl", "alt", "shift", "windows"];
  const mods = [e.ctrlKey && "ctrl", e.altKey && "alt", e.shiftKey && "shift", e.metaKey && "windows"].filter(Boolean);
  const k = keyName(e);
  const keys = mods.map((m) => ({ id: m, label: LABELS[m] }));
  if (!MODS.includes(k.id)) keys.push(k);
  if (!keys.length) return;
  hotkeyCapture.combo = keys.map((x) => x.id).join("+");
  hotkeyCapture.label = keys.map((x) => x.label).join(" + ");
  $("#keys").innerHTML = keys.map((x) => `<kbd>${esc(x.label)}</kbd>`).join(`<span class="muted">+</span>`);
  $("#hk-save").disabled = keys.every((x) => MODS.includes(x.id));
}, true);

/* ------------------------------------------------------------------ assistant de configuration */
const W = { open: false, step: 0, st: {}, heardVoice: false, sentTest: false, tab: "discord" };
const WSTEPS = ["Le principe", "Câble virtuel", "Appareils", "Tester", "Discord / jeu", "Sons"];

/** Schéma du trajet du son, réutilisé dans l'assistant et l'aide. */
function chainHTML() {
  const box = (ic, t, s, cls = "") => `<div class="node ${cls}"><span class="node-ic">${icon(ic)}</span><b>${t}</b><small>${s}</small></div>`;
  const arrow = (label = "") => `<div class="arrow"><span></span>${label ? `<em>${label}</em>` : ""}</div>`;
  return `<div class="chain">
    ${box("mic", "Ton micro", "ta voix")}
    ${arrow()}
    ${box("logo", "MicBoard", "+ les sons", "mb")}
    ${arrow("envoie dans")}
    ${box("headphones", "CABLE Input", "entrée du câble", "cable")}
    ${arrow("câble virtuel")}
    ${box("mic", "CABLE Output", "sortie du câble", "cable")}
    ${arrow("écouté par")}
    ${box("helmet", "Discord, jeu…", "les autres t'entendent")}
  </div>`;
}

async function openWizard(step = 0) {
  W.open = true;
  W.step = step;
  W.st = await api.setup_status(false);
  renderWizard();
}
function check(ok) { return `<span class="ck ${ok ? "on" : ""}">${ok ? "✓" : ""}</span>`; }

async function renderWizard() {
  const st = W.st;
  const done = [true, st.cable_found, st.cable_ok && !!st.mic, W.heardVoice && W.sentTest, false, st.sounds > 0];
  const steps = WSTEPS.map((t, i) => `<button class="wz-step ${i === W.step ? "cur" : ""} ${done[i] && i ? "done" : ""}" data-wz="${i}" type="button">
      <span class="n">${done[i] && i ? "✓" : i + 1}</span><span>${t}</span></button>`).join("");
  let body = "";
  if (W.step === 0) body = `
    <h2>Comment ça marche</h2>
    <p class="muted">Windows ne laisse pas une appli parler à la place de ton micro. L'astuce : un <b>câble virtuel</b>,
      un faux micro gratuit. MicBoard y envoie ta voix <b>et</b> les sons, et Discord ou ton jeu écoute ce faux micro.</p>
    ${chainHTML()}
    <div class="tip">${icon("help")}<div><b>La seule chose à retenir :</b> MicBoard envoie dans <code>CABLE Input</code>,
      et Discord ou ton jeu écoute <code>CABLE Output</code>. Les deux noms se ressemblent, c'est normal :
      c'est l'entrée et la sortie du même câble.</div></div>
    <p class="muted">L'assistant prend 2 minutes et vérifie chaque étape pour toi.</p>`;
  else if (W.step === 1) body = `
    <h2>Installer le câble virtuel</h2>
    <div class="status ${st.cable_found ? "ok" : "ko"}">${st.cable_found
      ? `${check(true)}<b>Câble virtuel détecté.</b> Tu peux passer à la suite.`
      : `${check(false)}<b>Câble virtuel pas encore installé.</b> Suis les 4 étapes ci-dessous.`}</div>
    <ol class="howto">
      <li><button class="secondary small" data-wact="vbcable" type="button">${icon("download")}Ouvrir la page de VB-Cable</button>
        et télécharge <b>VBCABLE_Driver_Pack</b> (gros bouton « Download », c'est gratuit).</li>
      <li>Dans tes Téléchargements, fais un clic droit sur le fichier <code>.zip</code> › <b>Extraire tout…</b></li>
      <li>Dans le dossier extrait, clic droit sur <code>VBCABLE_Setup_x64.exe</code> › <b>Exécuter en tant qu'administrateur</b>,
        puis clique sur <b>Install Driver</b>.</li>
      <li><b>Redémarre le PC</b>, rouvre MicBoard et reviens ici.</li>
    </ol>
    <button class="secondary" data-wact="recheck" type="button">${icon("search")}J'ai installé le câble : vérifier</button>`;
  else if (W.step === 2) {
    const d = await api.devices();
    const opts = (list, sel, none) => (none ? `<option value="">${none}</option>` : "") +
      list.map((n) => `<option ${n === sel ? "selected" : ""}>${esc(n)}</option>`).join("");
    body = `
    <h2>Choisir tes appareils</h2>
    <p class="muted">MicBoard a choisi pour toi. Vérifie juste que ça correspond.</p>
    <div class="pick">
      <div><span class="pick-ic">${icon("mic")}</span><div><b>Ton vrai micro</b><small>Celui dans lequel tu parles (casque-micro, micro USB…). <u>Pas</u> « CABLE Output ».</small></div></div>
      <select data-dev="mic">${opts(d.inputs || [], d.selected?.mic, "(aucun : seulement les sons)")}</select>
      <div><span class="pick-ic cable">${icon("logo")}</span><div><b>Sortie vers le câble</b><small>Toujours <b>CABLE Input</b>.</small></div></div>
      <select data-dev="cable" class="${st.cable_ok ? "" : "bad"}">${opts(d.outputs || [], d.selected?.cable)}</select>
      <div><span class="pick-ic">${icon("headphones")}</span><div><b>Ton casque</b><small>Pour entendre les sons toi aussi. Facultatif.</small></div></div>
      <select data-dev="monitor">${opts(d.outputs || [], d.selected?.monitor, "(aucun)")}</select>
    </div>
    ${st.cable_ok ? "" : `<div class="warn">${icon("help")}<div>Choisis <b>CABLE Input</b> comme sortie. S'il n'est pas dans la liste, reviens à l'étape 2.</div></div>`}`;
  } else if (W.step === 3) body = `
    <h2>Tester</h2>
    <p class="muted">On vérifie que tout passe bien dans le câble, avant d'aller dans Discord.</p>
    <div class="test-row">
      <span class="tn">1</span>
      <div class="grow"><b>Active MicBoard</b><small>${S.running ? "C'est actif : le bouton en haut à gauche affiche « En direct »." : "Le même bouton qu'en haut à gauche, « Micro hors ligne »."}</small></div>
      <button class="${S.running ? "secondary" : "primary"}" data-wact="toggle" type="button">${S.running ? "Désactiver" : "Activer"}</button>
    </div>
    <div class="test-row ${S.running ? "" : "dim"}">
      <span class="tn">2</span>
      <div class="grow"><b>Parle dans ton micro</b><small>La barre doit bouger quand tu parles.</small>
        <span class="vu big"><i id="wz-vu-mic"></i></span></div>
      ${check(W.heardVoice)}
    </div>
    <div class="test-row ${S.running ? "" : "dim"}">
      <span class="tn">3</span>
      <div class="grow"><b>Envoie un son de test</b><small>${st.monitor ? "Tu dois l'entendre dans ton casque." : "Choisis un casque à l'étape 3 pour l'entendre toi aussi."} La barre montre ce que les autres recevront.</small>
        <span class="vu big"><i id="wz-vu-out"></i></span></div>
      <button class="secondary" data-wact="test" type="button" ${S.running ? "" : "disabled"}>${icon("play")}Son de test</button>
      ${check(W.sentTest)}
    </div>
    <p class="muted small-note">La barre ne bouge pas quand tu parles ? Vérifie « Ton vrai micro » à l'étape 3, et que le bouton micro en bas de MicBoard n'est pas barré.</p>`;
  else if (W.step === 4) {
    const tabs = { discord: "Discord", games: "Jeux (CS:S, ET…)", obs: "OBS" };
    const content = {
      discord: `<ol class="howto">
        <li>Dans Discord, ouvre <b>Paramètres utilisateur</b> (roue dentée en bas) › <b>Voix et vidéo</b>.</li>
        <li><b>Périphérique d'entrée</b> : choisis <code>CABLE Output (VB-Audio Virtual Cable)</code>.</li>
        <li>Désactive la <b>suppression du bruit</b> (Krisp) : sinon Discord coupe les sons, qu'il prend pour du bruit.</li>
        <li>Clique sur <b>Vérifions</b> et joue un son dans MicBoard : tu dois l'entendre.</li>
      </ol>
      <div class="tip">${icon("key")}<div>En <b>Push-to-talk</b>, les sons ne passent que pendant que tu appuies sur ta touche.
        En <b>détection de la voix</b>, baisse un peu la sensibilité si les sons calmes sont coupés.</div></div>`,
      games: `<p class="muted">La plupart des jeux utilisent le <b>micro par défaut de Windows</b>. Il faut donc que ce soit le câble.</p>
      <ol class="howto">
        <li><button class="secondary small" data-wact="sound" type="button">${icon("gear")}Ouvrir les réglages de son de Windows</button></li>
        <li>Onglet <b>Enregistrement</b> : clic droit sur <code>CABLE Output</code> › <b>Définir en tant que périphérique par défaut</b>.</li>
        <li>Lance le jeu et active son chat vocal. Dans Counter-Strike: Source : Options › Voix.</li>
      </ol>
      <div class="tip">${icon("help")}<div>Ça ne gêne pas MicBoard : il écoute toujours ton vrai micro, choisi à l'étape 3.
        Pour revenir comme avant, remets ton vrai micro par défaut au même endroit.</div></div>`,
      obs: `<ol class="howto">
        <li>Dans OBS, <b>Sources</b> › <b>+</b> › <b>Capture audio d'entrée</b>.</li>
        <li>Périphérique : <code>CABLE Output (VB-Audio Virtual Cable)</code>.</li>
        <li>Retire ton micro habituel des sources, sinon ta voix sera en double.</li>
      </ol>`,
    };
    body = `<h2>Régler Discord ou ton jeu</h2>
      <p class="muted">Dernière étape technique : dire à ton appli d'écouter <code>CABLE Output</code> au lieu de ton micro.</p>
      <div class="tabs">${Object.entries(tabs).map(([k, t]) => `<button class="${W.tab === k ? "on" : ""}" data-wtab="${k}" type="button">${t}</button>`).join("")}</div>
      ${content[W.tab]}`;
  } else if (W.step === 5) body = `
    <h2>Ajouter tes sons</h2>
    <div class="status ${st.sounds ? "ok" : ""}">${check(st.sounds > 0)}${st.sounds
      ? `<b>${plural(st.sounds, "son")} dans ta bibliothèque.</b> Tu peux en ajouter d'autres quand tu veux.`
      : "<b>Ta bibliothèque est vide.</b> Ajoute des sons pour commencer."}</div>
    <div class="choices">
      <button class="choice" data-wact="games" type="button"><span class="node-ic">${icon("download")}</span><b>Depuis mes jeux</b><small>Wolfenstein ET, Counter-Strike: Source, Warcraft III</small></button>
      <button class="choice" data-wact="folder" type="button"><span class="node-ic">${icon("folder-plus")}</span><b>Un dossier de mon PC</b><small>Copié avec tous ses sous-dossiers</small></button>
      <button class="choice" data-wact="disk" type="button"><span class="node-ic">${icon("drive")}</span><b>Chercher sur mon PC</b><small>Trouver des fichiers audio sur tes disques</small></button>
    </div>
    <div class="keys-help" style="margin-top:18px">
      <kbd>Clic</kbd><span>sur un son : le jouer dans le micro</span>
      <kbd>/</kbd><span>chercher un son, puis Entrée pour jouer le premier résultat</span>
      <kbd>Échap</kbd><span>tout arrêter</span>
    </div>`;
  const last = W.step === WSTEPS.length - 1;
  openModal(`<div class="wizard">
    <div class="wz-steps">${steps}</div>
    <div class="wz-body">${body}</div>
    <div class="modal-actions wz-foot">
      ${W.step ? `<button class="secondary" data-wnav="-1" type="button">Précédent</button>` : `<button class="secondary" data-action="close" type="button">Plus tard</button>`}
      <span class="spacer"></span>
      <span class="muted">Étape ${W.step + 1} sur ${WSTEPS.length}</span>
      <button class="primary" data-wnav="${last ? "end" : 1}" type="button">${last ? "Terminer" : W.step === 0 ? "C'est parti" : "Suivant"}</button>
    </div></div>`);
  $(".modal-card").classList.add("wide");
  $$("select[data-dev]", $("#modal-body")).forEach((s) => s.addEventListener("change", async () => {
    await api.set_device(s.dataset.dev, s.value);
    W.st = await api.setup_status(false);
    renderWizard();
  }));
}

$("#modal").addEventListener("click", async (e) => {
  if (!W.open) return;
  const t = e.target.closest("[data-wz],[data-wnav],[data-wact],[data-wtab]");
  if (!t) return;
  if (t.dataset.wz) { W.step = +t.dataset.wz; return renderWizard(); }
  if (t.dataset.wtab) { W.tab = t.dataset.wtab; return renderWizard(); }
  if (t.dataset.wnav === "end") {
    await api.setup_done(true);
    closeModal();
    updateBanner();
    toast("Configuration terminée. Amuse-toi bien ! 🎉", "ok");
    return;
  }
  if (t.dataset.wnav) { W.step = Math.max(0, Math.min(WSTEPS.length - 1, W.step + +t.dataset.wnav)); W.st = await api.setup_status(false); return renderWizard(); }
  const act = t.dataset.wact;
  if (act === "vbcable") api.open_url("vbcable");
  else if (act === "sound") api.open_sound_settings();
  else if (act === "recheck") {
    if (S.running) { await api.stop_engine(); setLive(false); }
    W.st = await api.setup_status(true);
    toast(W.st.cable_found ? "Câble virtuel détecté ✓" : "Toujours pas de câble. As-tu redémarré le PC après l'installation ?", W.st.cable_found ? "ok" : "err");
    renderWizard();
  } else if (act === "toggle") {
    if (S.running) { await api.stop_engine(); setLive(false); }
    else {
      const r = await api.start();
      if (!r.ok) { toast(r.error, "err"); return; }
      setLive(true);
    }
    renderWizard();
  } else if (act === "test") {
    const r = await api.play_test();
    if (!r.ok) toast(r.error, "err");
    else { W.sentTest = true; renderWizard(); }
  } else if (act === "games") { W.open = false; openImport(); }
  else if (act === "folder") { W.open = false; closeModal(); importFolder(""); }
  else if (act === "disk") { W.open = false; closeModal(); setView("disk"); }
});

/** Appelée à chaque sondage du moteur : anime les barres de l'étape « Tester ». */
function wizardTick(st) {
  if (!W.open || W.step !== 3) return;
  const mic = $("#wz-vu-mic"), out = $("#wz-vu-out");
  if (mic) mic.style.width = `${Math.min(100, Math.sqrt(st.mic_level) * 100)}%`;
  if (out) out.style.width = `${Math.min(100, Math.sqrt(st.out_level) * 100)}%`;
  if (!W.heardVoice && st.running && st.mic_level > 0.04) { W.heardVoice = true; renderWizard(); }
}

async function updateBanner() {
  const st = await api.setup_status(false);
  const b = $("#setup-banner");
  b.hidden = st.setup_done;
  if (!st.setup_done) b.innerHTML = `${icon("help")}<span><b>Configuration pas terminée.</b> L'assistant t'explique tout, étape par étape (2 minutes).</span>
    <button class="primary small" data-action="wizard" type="button">Lancer l'assistant</button>`;
}

/* aide */
function openHelp() {
  W.open = false;
  const faq = [
    ["Windows m'empêche d'ouvrir MicBoard.exe", "C'est SmartScreen : MicBoard n'est pas signé numériquement. Clique sur <b>Informations complémentaires</b>, puis sur <b>Exécuter quand même</b>."],
    ["Les autres n'entendent rien", "Vérifie que le bouton en haut à gauche affiche <b>En direct</b>, que Discord ou le jeu utilise <code>CABLE Output</code> comme micro, et dans Discord, que la suppression du bruit est désactivée. En Push-to-talk, les sons ne passent que pendant que tu appuies."],
    ["Ils entendent les sons mais pas ma voix", "Dans Réglages, « Mon vrai micro » doit être ton micro (pas CABLE Output). Le bouton micro en bas de MicBoard ne doit pas être barré : sa barre bouge quand tu parles."],
    ["Je n'entends pas les sons moi-même", "Choisis ton casque dans Réglages › « Retour casque », et monte le curseur casque en bas à droite."],
    ["« CABLE Input » n'apparaît pas", "Installe VB-Cable (étape 2 de l'assistant), puis <b>redémarre le PC</b>. Le bouton « vérifier » de l'assistant relit la liste des appareils."],
    ["Le son est haché ou un appareil refuse de s'ouvrir", "Dans Réglages, change le « Pilote audio » pour <b>MME</b>."],
    ["Les raccourcis clavier ne marchent pas en jeu", "Si le jeu est lancé en administrateur, lance aussi MicBoard en administrateur (clic droit › Exécuter en tant qu'administrateur)."],
  ];
  openModal(`<div class="help"><h2>Aide</h2>
    <p class="muted">Le trajet du son :</p>
    ${chainHTML()}
    <div class="help-cta"><div><b>Quelque chose ne marche pas ?</b><br><span class="muted">L'assistant vérifie chaque étape avec toi.</span></div>
      <button class="primary" data-action="wizard" type="button">Relancer l'assistant</button></div>
    <h3>Dépannage</h3>
    <div class="faq">${faq.map(([q, a]) => `<details><summary>${q}</summary><p>${a}</p></details>`).join("")}</div>
    <h3>Raccourcis</h3>
    <div class="keys-help">
      <kbd>/</kbd><span>Chercher un son (tape, puis Entrée pour jouer le premier résultat)</span>
      <kbd>↑ ↓ ← →</kbd><span>Se déplacer entre les sons</span>
      <kbd>Entrée</kbd><span>Jouer dans le micro</span>
      <kbd>Maj + Entrée</kbd><span>Écouter dans le casque seulement</span>
      <kbd>Retour arrière</kbd><span>Remonter d'un dossier</span>
      <kbd>Échap</kbd><span>Effacer la recherche, puis tout arrêter</span>
    </div>
    <div class="modal-actions"><button class="primary" data-action="close" type="button">Fermer</button></div></div>`);
  $(".modal-card").classList.add("wide");
}

/* ------------------------------------------------------------------ recherche sur le PC */
async function initDisk() {
  fillDestSelect();
  if (S.disk.roots.length) return;
  S.disk.roots = await api.disk_roots();
  S.disk.on = new Set([S.disk.roots[0].path]);
  renderRoots();
}
/** Liste de destination : tous les dossiers de la bibliothèque, en arbre. */
function fillDestSelect() {
  const sel = $("#disk-cat");
  const keep = sel.value || (S.folder || "Mes sons");
  const opts = [`<option value="">Nouvelle catégorie (dossier importé)</option>`];
  const walk = (nodes, d) => nodes.forEach((t) => {
    opts.push(`<option value="${esc(t.rel)}">${"   ".repeat(d)}${d ? "└ " : ""}${esc(t.name)}</option>`);
    walk(t.children, d + 1);
  });
  walk(S.tree, 0);
  sel.innerHTML = opts.join("");
  sel.value = [...sel.options].some((o) => o.value === keep) ? keep : "Mes sons";
  sel.title = "Où ranger les sons dans ta bibliothèque";
}
function renderRoots() {
  $("#roots").innerHTML = S.disk.roots.map((r) => `
    <button class="chip ${S.disk.on.has(r.path) ? "on" : ""}" data-root="${esc(r.path)}" title="${esc(r.path)}" type="button">
      ${icon(r.label.startsWith("Disque") ? "drive" : "folder")}<span>${esc(r.label)}</span></button>`).join("") +
    `<button class="chip dashed" id="root-add" type="button">${icon("plus")}<span>Un autre dossier…</span></button>`;
}
$("#roots").addEventListener("click", async (e) => {
  if (e.target.closest("#root-add")) {
    const p = await api.pick_folder();
    if (p) { S.disk.roots.push({ path: p, label: p.split(/[\\/]/).filter(Boolean).pop() || p }); S.disk.on.add(p); renderRoots(); }
    return;
  }
  const chip = e.target.closest("[data-root]");
  if (!chip) return;
  const p = chip.dataset.root;
  S.disk.on.has(p) ? S.disk.on.delete(p) : S.disk.on.add(p);
  renderRoots();
});

$("#disk-go").addEventListener("click", startDisk);
$("#dq").addEventListener("keydown", (e) => { if (e.key === "Enter") startDisk(); });
$("#disk-cancel").addEventListener("click", () => api.disk_cancel());

async function startDisk() {
  if (!S.disk.on.size) { toast("Choisis au moins un endroit où chercher", "err"); return; }
  clearInterval(S.disk.timer);
  S.disk.sid = await api.disk_search($("#dq").value, [...S.disk.on]);
  S.disk.since = 0;
  S.disk.rows = [];
  $("#disk-list").innerHTML = "";
  $("#disk-empty").hidden = true;
  $("#disk-status").hidden = false;
  $("#disk-status").classList.remove("done");
  $("#disk-cancel").hidden = false;
  $("#disk-bar").hidden = true;
  $("#disk-all").checked = false;
  S.disk.timer = setInterval(pollDisk, 250);
}

const fmtSize = (b) => (b < 1024 * 1024 ? `${Math.max(1, Math.round(b / 1024))} Ko` : `${(b / 1048576).toFixed(1).replace(".", ",")} Mo`);
function diskHighlight(text) {
  const q = $("#dq").value;
  const saved = S.query;
  S.query = q;
  const h = highlight(text);
  S.query = saved;
  return h;
}

async function pollDisk() {
  const sid = S.disk.sid;
  const r = await api.disk_poll(sid, S.disk.since);
  if (sid !== S.disk.sid) return;
  S.disk.since = r.total;
  if (r.results.length) {
    const start = S.disk.rows.length;
    S.disk.rows.push(...r.results);
    const frag = document.createElement("template");
    frag.innerHTML = r.results.map((f, k) => `
      <div class="row-file" data-k="${start + k}">
        <label class="check"><input type="checkbox"><span></span></label>
        <button class="play" data-dact="preview" title="Écouter dans mon casque" type="button">${icon("headphones")}</button>
        <div style="min-width:0"><b>${diskHighlight(f.name)}</b><small>${esc(f.file)}</small></div>
        <div class="path" title="${esc(f.folder)}">‎${esc(f.folder)}</div>
        <div class="size">${fmtSize(f.size)}</div>
        <div class="acts">
          <button data-dact="add" title="Ajouter ce son à la bibliothèque" type="button">${icon("plus")}</button>
          <button data-dact="import-dir" title="Importer tout son dossier, avec ses sous-dossiers" type="button">${icon("folder-plus")}</button>
          <button data-dact="folder" title="Ouvrir dans l'Explorateur" type="button">${icon("folder")}</button>
        </div>
      </div>`).join("");
    $("#disk-list").append(frag.content);
    $("#disk-bar").hidden = false;
  }
  const n = S.disk.rows.length.toLocaleString("fr-FR");
  $("#disk-count").textContent = `${n} son${S.disk.rows.length > 1 ? "s" : ""} trouvé${S.disk.rows.length > 1 ? "s" : ""}`;
  const dirs = r.dirs.toLocaleString("fr-FR");
  if (r.done) {
    clearInterval(S.disk.timer);
    $("#disk-status").classList.add("done");
    $("#disk-cancel").hidden = true;
    $("#disk-status-text").textContent = `Terminé : ${n} son${S.disk.rows.length > 1 ? "s" : ""} dans ${dirs} dossiers parcourus${r.truncated ? " (limite atteinte, précise ta recherche)" : ""}.`;
    if (!S.disk.rows.length) {
      $("#disk-empty").hidden = false;
      $("#disk-empty").innerHTML = `<div class="empty-art">${icon("search")}</div><h2>Aucun fichier audio trouvé</h2><p class="muted">Essaie un autre mot ou d'autres dossiers. Les sons des jeux sont souvent rangés dans des archives : utilise plutôt « Importer depuis mes jeux ».</p>`;
    }
  } else {
    $("#disk-status-text").textContent = `${dirs} dossiers parcourus · ${n} trouvé${S.disk.rows.length > 1 ? "s" : ""} · ${r.current}`;
  }
}

$("#disk-list").addEventListener("click", async (e) => {
  const row = e.target.closest(".row-file");
  if (!row) return;
  const f = S.disk.rows[+row.dataset.k];
  const act = e.target.closest("[data-dact]")?.dataset.dact;
  if (act === "preview") preview(f.path);
  else if (act === "folder") api.open_folder(f.path);
  else if (act === "add") addDisk([f.path], [row]);
  else if (act === "import-dir") importDiskFolder(f.folder);
  updateDiskSel();
});
$("#disk-list").addEventListener("dblclick", (e) => {
  const row = e.target.closest(".row-file");
  if (row && !e.target.closest("button, label")) preview(S.disk.rows[+row.dataset.k].path);
});
$("#disk-all").addEventListener("change", (e) => {
  $$("#disk-list .row-file:not(.in-lib) input").forEach((c) => (c.checked = e.target.checked));
  updateDiskSel();
});
function updateDiskSel() {
  const n = $$("#disk-list input:checked").length;
  $("#disk-add").disabled = !n;
  $("#disk-add").lastChild.textContent = n ? `Ajouter ${n} son${n > 1 ? "s" : ""}` : "Ajouter la sélection";
}
$("#disk-list").addEventListener("change", updateDiskSel);
$("#disk-add").addEventListener("click", () => {
  const rows = $$("#disk-list .row-file").filter((r) => $("input", r).checked);
  addDisk(rows.map((r) => S.disk.rows[+r.dataset.k].path), rows);
});
async function addDisk(paths, rows) {
  const dest = $("#disk-cat").value || "Mes sons";   // des fichiers seuls ne créent pas de catégorie
  const r = await api.add_files(paths, dest);
  rows.forEach((row) => { row.classList.add("in-lib"); $("input", row).checked = false; });
  updateDiskSel();
  toast(r.added ? `${plural(r.added, "son")} ajouté${r.added > 1 ? "s" : ""} dans « ${leaf(dest)} »` : "Déjà dans la bibliothèque", r.added ? "ok" : "");
  await refreshTree();
  fillDestSelect();
}
async function importDiskFolder(src) {
  const dest = $("#disk-cat").value;
  const r = await api.import_folder_path(src, dest);
  if (r.error) { toast(`Import impossible : ${r.error}`, "err"); return; }
  const name = r.rel.split("/").pop();
  toast(`Dossier « ${name} » importé dans ${destLabel(dest)} : ${plural(r.added, "son")}, sous-dossiers compris`, "ok");
  $$("#disk-list .row-file").forEach((row) => {
    const f = S.disk.rows[+row.dataset.k];
    if (f.folder === src || f.folder.startsWith(src + "/") || f.folder.startsWith(src + "\\")) row.classList.add("in-lib");
  });
  await refreshTree();
  fillDestSelect();
}

/* ------------------------------------------------------------------ barre de titre maison */
const MIN_W = 900, MIN_H = 600;
const MAX_ICON = '<rect x="2" y="2" width="8" height="8" rx="1.5"/>';
const RESTORE_ICON = '<rect x="2" y="4" width="6" height="6" rx="1.2"/><path d="M4 4V3.2C4 2.5 4.5 2 5.2 2H8.8C9.5 2 10 2.5 10 3.2V6.8C10 7.5 9.5 8 8.8 8H8"/>';

function setMaximized(on) {
  document.body.classList.toggle("maximized", on);
  $("#tb-max-ic").innerHTML = on ? RESTORE_ICON : MAX_ICON;
  $('[data-win="max"]').title = on ? "Niveau inférieur" : "Agrandir";
  // une fenêtre agrandie ne se déplace pas : on retire les zones de déplacement
  $$("[data-drag]").forEach((el) => el.classList.toggle("pywebview-drag-region", !on));
}
async function toggleMax() {
  try { setMaximized(await api.win_toggle_max()); } catch { /* hors de l'appli Windows */ }
}
$(".tb-btns").addEventListener("click", (e) => {
  const b = e.target.closest("[data-win]");
  if (!b) return;
  if (b.dataset.win === "min") api.win_minimize();
  else if (b.dataset.win === "max") toggleMax();
  else if (b.dataset.win === "close") api.win_close();
});
$$("[data-drag]").forEach((el) => el.addEventListener("dblclick", toggleMax));

/* redimensionnement par les bords et les coins */
$$(".rz").forEach((handle) => handle.addEventListener("pointerdown", async (e) => {
  if (e.button !== 0 || document.body.classList.contains("maximized")) return;
  e.preventDefault();
  handle.setPointerCapture(e.pointerId);
  const dir = handle.dataset.dir;
  const sx = e.screenX, sy = e.screenY;
  let g;
  try { g = await api.win_geometry(); } catch { return; }
  let pending = null, busy = false;
  const target = (ev) => {
    const dx = ev.screenX - sx, dy = ev.screenY - sy;
    let { x, y, w, h } = g;
    if (dir.includes("e")) w = Math.max(MIN_W, g.w + dx);
    if (dir.includes("s")) h = Math.max(MIN_H, g.h + dy);
    if (dir.includes("w")) { w = Math.max(MIN_W, g.w - dx); x = g.x + g.w - w; }
    if (dir.includes("n")) { h = Math.max(MIN_H, g.h - dy); y = g.y + g.h - h; }
    return { x, y, w, h };
  };
  const flush = async () => { // un seul appel à la fois : on applique toujours la dernière position
    busy = true;
    while (pending) { const p = pending; pending = null; await api.win_set_geometry(p.x, p.y, p.w, p.h); }
    busy = false;
  };
  const onMove = (ev) => { pending = target(ev); if (!busy) flush(); };
  const onUp = () => { handle.removeEventListener("pointermove", onMove); handle.removeEventListener("pointerup", onUp); };
  handle.addEventListener("pointermove", onMove);
  handle.addEventListener("pointerup", onUp);
}));
