"""Moteur de recherche des fichiers audio.

Deux usages :
- `Index` : recherche instantanée dans la bibliothèque MicBoard (tolère accents, majuscules,
  noms collés comme « PeasantYes1 », mots dans le désordre, fautes de frappe légères) ;
- `DiskSearch` : parcourt les disques ou des dossiers du PC, en tâche de fond, pour trouver
  des fichiers audio à ajouter.
"""

from __future__ import annotations

import os
import re
import string
import threading
import unicodedata
from dataclasses import dataclass, field
from difflib import SequenceMatcher
from pathlib import Path

from .library import display_name, is_audio, read_tags

_CAMEL = re.compile(r"(?<=[a-z])(?=[A-Z])|(?<=[A-Za-z])(?=\d)|(?<=\d)(?=[A-Za-z])")
_SPLIT = re.compile(r"[^0-9a-z]+")


def fold(text: str) -> str:
    """Minuscules sans accents."""
    text = unicodedata.normalize("NFKD", text)
    return "".join(c for c in text if not unicodedata.combining(c)).lower()


def tokens(text: str) -> list[str]:
    return [t for t in _SPLIT.split(fold(_CAMEL.sub(" ", text))) if t]


_DIGITS = re.compile(r"(\d+)")


def file_key(path) -> tuple:
    """Tri A-Z par nom de fichier, sans tenir compte des majuscules ni des accents, et avec les
    nombres comparés comme des nombres : « PeasantYes2 » avant « PeasantYes10 »."""
    name = fold(Path(path).name)
    return tuple((0, int(part), "") if part.isdigit() else (1, 0, part)
                 for part in _DIGITS.split(name) if part) + ((2, 0, Path(path).name),)


def pretty_name(stem: str) -> str:
    """« peasantYes1 » / « a-medic_3 » -> « Peasant Yes 1 » / « A Medic 3 »."""
    words = re.split(r"[\s_\-.]+", _CAMEL.sub(" ", stem))
    return " ".join(w[:1].upper() + w[1:] for w in words if w)


@dataclass
class Entry:
    path: Path
    name: str          # nom affiché
    category: str      # premier dossier sous la racine
    folder: str        # sous-dossier (sans la catégorie)
    rel_dir: str = ""  # dossier complet depuis la racine, « Catégorie/sous/dossier »
    source: str = "file"  # d'où vient `name` : « title », « comment » ou « file »
    name_tokens: list[str] = field(default_factory=list)
    folder_tokens: list[str] = field(default_factory=list)
    blob: str = ""     # tout le texte collé, pour les sous-chaînes


def _token_score(q: str, toks: list[str]) -> float:
    best = 0.0
    for t in toks:
        if t == q:
            return 1.0
        if t.startswith(q):
            best = max(best, 0.8)
        elif q in t:
            best = max(best, 0.6)
        elif len(q) >= 4 and abs(len(t) - len(q)) <= 2:
            r = SequenceMatcher(None, q, t).ratio()
            if r >= 0.8:  # faute de frappe
                best = max(best, 0.4 * r)
    return best


class Index:
    def __init__(self, root: Path, files: list[Path]):
        self.root = Path(root)
        self.entries: list[Entry] = []
        for f in files:
            try:
                rel = f.relative_to(self.root)
            except ValueError:
                rel = Path(f.name)
            parts = rel.parts[:-1]
            category = parts[0] if parts else ""
            folder = "/".join(parts[1:])
            e = Entry(path=f, name=pretty_name(f.stem), category=category, folder=folder,
                      rel_dir="/".join(parts))
            e.folder_tokens = tokens(folder)
            self._set_name(e, e.name, "file")
            self.entries.append(e)
        self._by_path = {str(e.path): e for e in self.entries}

    @staticmethod
    def _set_name(e: Entry, name: str, source: str) -> None:
        """Change le nom affiché ; la recherche trouve le son par ce nom ET par son nom de fichier."""
        e.name, e.source = name, source
        shown, stem = tokens(name), tokens(e.path.stem)
        e.name_tokens = shown + [t for t in stem if t not in shown]
        e.blob = "".join(shown) + "|" + "".join(stem)

    def set_name(self, path: str, name: str, source: str) -> bool:
        e = self._by_path.get(str(path))
        if e is None or (e.name, e.source) == (name, source):
            return False
        self._set_name(e, name, source)
        return True

    def categories(self) -> dict[str, int]:
        counts: dict[str, int] = {}
        for e in self.entries:
            counts[e.category] = counts.get(e.category, 0) + 1
        return counts

    def score(self, e: Entry, q_tokens: list[str], q_joined: str) -> float:
        total = 0.0
        for q in q_tokens:
            s = max(_token_score(q, e.name_tokens), 0.7 * _token_score(q, e.folder_tokens),
                    0.5 * _token_score(q, tokens(e.category)))
            if s == 0:
                return 0.0  # tous les mots doivent correspondre
            total += s
        if len(q_tokens) > 1 and q_joined in e.blob:
            total += 0.5  # « peasant yes » trouve « PeasantYes1 » en premier
        return total / len(q_tokens) - 0.002 * len(e.name_tokens)

    @staticmethod
    def in_folder(e: Entry, folder: str) -> bool:
        """Le son est-il dans ce dossier ou un de ses sous-dossiers ? (« » = toute la bibliothèque)"""
        return not folder or e.rel_dir == folder or e.rel_dir.startswith(folder + "/")

    def search(self, query: str, folder: str | None = None, limit: int = 200,
               only: set[str] | None = None) -> tuple[list[Entry], int]:
        """Renvoie (résultats triés, nombre total de résultats), dans `folder` et ses sous-dossiers."""
        pool = [e for e in self.entries
                if self.in_folder(e, folder or "") and (only is None or str(e.path) in only)]
        q_tokens = tokens(query)
        if not q_tokens:
            pool.sort(key=lambda e: (e.rel_dir.lower(), file_key(e.path)))
            return pool[:limit], len(pool)
        joined = "".join(q_tokens)
        scored = [(s, e) for e in pool if (s := self.score(e, q_tokens, joined)) > 0]
        scored.sort(key=lambda x: (-x[0], file_key(x[1].path)))
        return [e for _s, e in scored[:limit]], len(scored)


# --- recherche sur le disque -----------------------------------------------------------
SKIP_DIRS = {"windows", "$recycle.bin", "system volume information", "winsxs", "node_modules",
             ".git", "__pycache__", "programdata", "recovery", "$windows.~bt", "$winreagent"}


def drives() -> list[str]:
    if os.name != "nt":
        return [str(Path.home())]
    return [f"{d}:\\" for d in string.ascii_uppercase if os.path.exists(f"{d}:\\")]


class DiskSearch:
    """Recherche de fichiers audio sur le disque, dans un thread, interrogeable par sondage."""

    def __init__(self, roots: list[str], query: str = "", max_results: int = 3000, encoding: str = "auto"):
        self.roots = [r for r in roots if r]
        self.encoding = encoding
        self.q_tokens = tokens(query)
        self.max_results = max_results
        self.results: list[dict] = []
        self.scanned_dirs = 0
        self.scanned_files = 0
        self.current = ""
        self.done = False
        self._cancel = threading.Event()
        self._lock = threading.Lock()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self) -> "DiskSearch":
        self._thread.start()
        return self

    def cancel(self) -> None:
        self._cancel.set()

    def matches(self, path: str) -> bool:
        if not self.q_tokens:
            return True
        hay = "".join(tokens(path))
        return all(q in hay for q in self.q_tokens)

    def _match(self, path: str) -> dict | None:
        """Balises du fichier s'il correspond à la recherche (par son chemin, sinon par son titre ou
        son commentaire), None sinon."""
        tags = read_tags(path, self.encoding)
        if self.matches(path):
            return tags
        if self.q_tokens and (tags["title"] or tags["comment"]):
            hay = "".join(tokens(f"{tags['title']} {tags['comment']}"))
            if all(q in hay for q in self.q_tokens):
                return tags
        return None

    def _run(self) -> None:
        stack = list(reversed(self.roots))
        try:
            while stack and not self._cancel.is_set():
                d = stack.pop()
                self.current = d
                self.scanned_dirs += 1
                try:
                    it = os.scandir(d)
                except OSError:
                    continue
                subdirs = []
                with it:
                    for entry in it:
                        try:
                            if entry.is_dir(follow_symlinks=False):
                                if entry.name.lower() not in SKIP_DIRS and not entry.name.startswith("."):
                                    subdirs.append(entry.path)
                            elif is_audio(entry.name):
                                self.scanned_files += 1
                                found = self._match(entry.path)
                                if found is not None:
                                    st = entry.stat()
                                    name, source = display_name(Path(entry.name).stem, found)
                                    with self._lock:
                                        self.results.append({
                                            "path": entry.path, "name": name, "source": source,
                                            "file": entry.name, "folder": d, "size": st.st_size})
                                    if len(self.results) >= self.max_results:
                                        return
                        except OSError:
                            continue
                stack.extend(sorted(subdirs, reverse=True))
        finally:
            self.done = True

    def poll(self, since: int) -> dict:
        with self._lock:
            new = self.results[since:]
        return {"results": new, "total": since + len(new), "dirs": self.scanned_dirs,
                "files": self.scanned_files, "current": self.current, "done": self.done,
                "truncated": len(self.results) >= self.max_results}
