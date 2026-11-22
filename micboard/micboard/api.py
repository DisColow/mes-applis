"""Fonctions appelées par l'interface (window.pywebview.api.* côté JavaScript).

Tout l'état est dans des attributs privés (préfixe _) : pywebview n'expose que les méthodes
publiques, et chaque méthode renvoie des données simples (dict, list, str…) sérialisables en JSON.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
import uuid
from pathlib import Path

from . import importers, library
from .audio import AudioEngine, Mixer
from .search import DiskSearch, Index, drives

APP = "MicBoard"
CATEGORY_STYLE = {  # couleur et icône de chaque catégorie dans l'interface
    "Wolfenstein Enemy Territory": {"color": "#9bb35a", "icon": "helmet", "short": "W:ET"},
    "Warcraft III - Paysans": {"color": "#e0b84a", "icon": "hammer", "short": "WC3"},
    "Counter-Strike Source": {"color": "#f08a3c", "icon": "target", "short": "CS:S"},
    "Mes sons": {"color": "#7aa2ff", "icon": "music", "short": "Perso"},
}
FALLBACK_COLORS = ["#c38bff", "#4fd1c5", "#ff7a9a", "#a3e635"]


def config_path() -> Path:
    base = Path(os.environ.get("APPDATA", Path.home() / ".config"))
    return base / APP / "config.json"


class Api:
    def __init__(self, engine: AudioEngine | None = None, cfg_file: Path | None = None):
        self._cfg_file = cfg_file or config_path()
        self._cfg = self._load()
        self._root = Path(self._cfg.get("sounds_root") or library.default_root())
        library.ensure_root(self._root)
        self._mixer = Mixer()
        self._engine = engine or AudioEngine(self._mixer)
        self._engine.mixer = self._mixer
        self._cache = library.SoundCache()
        self._hotkeys: dict[str, str] = self._cfg.get("hotkeys", {})
        self._favorites: list[str] = self._cfg.get("favorites", [])
        self._durations: dict[str, float] = {}
        self._tags: dict[str, dict] = {}  # chemin -> {"title", "comment", "duration"}
        self._tag_cache = library.TagCache(self._cfg_file.parent / "tags.json")
        self._lib_version = 0             # change quand des noms arrivent des métadonnées
        self._scan_id = 0
        self._disk: dict[str, DiskSearch] = {}
        self._window = None
        self._message = ""
        self._apply_mix()
        self.rescan()
        self._register_hotkeys()

    def _attach(self, window) -> None:
        self._window = window

    # --- configuration -----------------------------------------------------------------
    def _load(self) -> dict:
        try:
            return json.loads(self._cfg_file.read_text(encoding="utf-8"))
        except Exception:
            return {}

    def _save(self) -> None:
        self._cfg.update(hotkeys=self._hotkeys, favorites=self._favorites, sounds_root=str(self._root))
        try:
            self._cfg_file.parent.mkdir(parents=True, exist_ok=True)
            self._cfg_file.write_text(json.dumps(self._cfg, indent=2, ensure_ascii=False), encoding="utf-8")
        except OSError:
            pass

    def _mix_cfg(self) -> dict:
        return {"sound_volume": self._cfg.get("sound_volume", 0.8),
                "mic_volume": self._cfg.get("mic_volume", 1.0),
                "monitor_volume": self._cfg.get("monitor_volume", 0.6),
                "mic_enabled": self._cfg.get("mic_enabled", True),
                "exclusive": self._cfg.get("exclusive", False)}

    def _apply_mix(self) -> None:
        c = self._mix_cfg()
        m = self._mixer
        m.sound_gain, m.mic_gain, m.monitor_gain = c["sound_volume"], c["mic_volume"], c["monitor_volume"]
        m.mic_enabled, m.exclusive = c["mic_enabled"], c["exclusive"]

    # --- bibliothèque --------------------------------------------------------------------
    def rescan(self) -> dict:
        tree = library.scan(self._root)
        files: list[Path] = []

        def collect(node):
            files.extend(node["files"])
            for sub in node["dirs"].values():
                collect(sub)
        collect(tree)
        self._tree = tree
        self._index = Index(self._root, files)
        self._favorites = [f for f in self._favorites if Path(f).exists()]
        # Métadonnées déjà connues : appliquées tout de suite ; les autres sont lues en arrière-plan.
        missing = []
        for f in files:
            key = str(f)
            tags = self._tags.get(key) or self._tag_cache.get(key)
            if tags is None:
                missing.append(f)
            else:
                self._apply_tags(key, tags)
        self._scan_id += 1
        self._lib_version += 1
        threading.Thread(target=self._read_tags, args=(missing, self._scan_id, {str(f) for f in files}),
                         daemon=True).start()
        return self.categories()

    def _apply_tags(self, key: str, tags: dict) -> bool:
        self._tags[key] = tags
        self._durations[key] = tags.get("duration") or 0.0
        name, source = library.display_name(Path(key).stem, tags)
        return self._index.set_name(key, name, source)

    def _read_tags(self, files: list[Path], scan_id: int, all_keys: set[str]) -> None:
        """Lit titre, commentaire et durée des fichiers pas encore en cache (tâche de fond)."""
        changed = 0
        for i, f in enumerate(files):
            if scan_id != self._scan_id:  # une nouvelle analyse a été lancée entre-temps
                return
            key = str(f)
            tags = library.read_tags(key)
            self._tag_cache.put(key, tags)
            changed += self._apply_tags(key, tags)
            if changed and i % 100 == 99:  # l'interface se met à jour par paquets
                self._lib_version += 1
                changed = 0
        if changed:
            self._lib_version += 1
        self._tag_cache.save(keep=all_keys)

    def _measure(self, files: list[Path]) -> None:  # utilisé par les outils de test
        for f in files:
            self._apply_tags(str(f), library.read_tags(f))

    def categories(self) -> list[dict]:
        counts = self._index.categories()
        names = [d.name for d in sorted(self._root.iterdir(), key=lambda p: p.name.lower()) if d.is_dir()]
        ordered = [c for c in library.DEFAULT_CATEGORIES if c in names] + \
                  [c for c in names if c not in library.DEFAULT_CATEGORIES]
        out = []
        for i, name in enumerate(ordered):
            style = CATEGORY_STYLE.get(name, {"color": FALLBACK_COLORS[i % len(FALLBACK_COLORS)],
                                              "icon": "folder", "short": name[:5]})
            out.append({"name": name, "count": counts.get(name, 0), **style})
        return out

    def _entry_dict(self, e) -> dict:
        key = str(e.path)
        tags = self._tags.get(key) or {}
        return {"path": key, "name": e.name, "source": e.source, "file": e.path.name,
                "title": tags.get("title", ""), "comment": tags.get("comment", ""),
                "category": e.category, "folder": e.folder, "dir": e.rel_dir,
                "duration": self._durations.get(key),
                "favorite": key in self._favorites, "hotkey": self._hotkeys.get(key, "")}

    # --- arborescence ------------------------------------------------------------------
    def _node(self, rel: str) -> dict | None:
        node = self._tree
        for part in [p for p in rel.split("/") if p]:
            node = node["dirs"].get(part)
            if node is None:
                return None
        return node

    @staticmethod
    def _count(node: dict) -> int:
        return len(node["files"]) + sum(Api._count(n) for n in node["dirs"].values())

    def _dest(self, rel: str) -> Path:
        """Chemin absolu d'un dossier de la bibliothèque, sans pouvoir en sortir."""
        target = (self._root / rel).resolve()
        if not target.is_relative_to(self._root.resolve()):
            raise ValueError("Dossier hors de la bibliothèque")
        return target

    def _ordered_dirs(self, node: dict, rel: str) -> list[str]:
        names = list(node["dirs"])
        if not rel:  # les catégories des jeux d'abord
            return [c for c in library.DEFAULT_CATEGORIES if c in names] + \
                   [c for c in names if c not in library.DEFAULT_CATEGORIES]
        return names

    def tree(self) -> list[dict]:
        """Arborescence complète des dossiers, pour la barre latérale."""
        def build(node, rel):
            out = []
            for name in self._ordered_dirs(node, rel):
                sub = node["dirs"][name]
                r = f"{rel}/{name}" if rel else name
                out.append({"name": name, "rel": r, "count": self._count(sub), "children": build(sub, r)})
            return out
        return build(self._tree, "")

    def browse(self, rel: str = "", limit: int = 400) -> dict:
        """Contenu d'un dossier : fil d'Ariane, sous-dossiers et sons qu'il contient directement."""
        node = self._node(rel)
        if node is None:
            rel, node = "", self._tree
        parts = [p for p in rel.split("/") if p]
        crumbs = [{"name": p, "rel": "/".join(parts[:i + 1])} for i, p in enumerate(parts)]
        folders = []
        for name in self._ordered_dirs(node, rel):
            sub = node["dirs"][name]
            folders.append({"name": name, "rel": f"{rel}/{name}" if rel else name,
                            "count": self._count(sub), "subfolders": len(sub["dirs"])})
        here = sorted((e for e in self._index.entries if e.rel_dir == rel), key=lambda e: e.name.lower())
        return {"rel": rel, "crumbs": crumbs, "folders": folders, "total": len(here),
                "count": self._count(node), "files": [self._entry_dict(e) for e in here[:int(limit)]],
                "path": str(self._root / rel) if rel else str(self._root)}

    def search(self, query: str = "", folder: str = "", favorites: bool = False,
               limit: int = 240) -> dict:
        only = set(self._favorites) if favorites else None
        results, total = self._index.search(query, folder or None, int(limit), only)
        if favorites and not query:
            order = {p: i for i, p in enumerate(self._favorites)}
            results.sort(key=lambda e: order.get(str(e.path), 0))
        return {"results": [self._entry_dict(e) for e in results], "total": total,
                "library_total": len(self._index.entries)}

    def favorites(self) -> list[dict]:
        by_path = {str(e.path): e for e in self._index.entries}
        return [self._entry_dict(by_path[p]) for p in self._favorites if p in by_path]

    def toggle_favorite(self, path: str) -> bool:
        if path in self._favorites:
            self._favorites.remove(path)
        else:
            self._favorites.append(path)
        self._save()
        return path in self._favorites

    def random_sound(self, folder: str = "") -> dict | None:
        import random
        pool = [e for e in self._index.entries if Index.in_folder(e, folder)]
        if not pool:
            return None
        e = random.choice(pool)
        self.play(str(e.path))
        return self._entry_dict(e)

    # --- lecture ------------------------------------------------------------------------
    def play(self, path: str) -> dict:
        try:
            data = self._cache.get(path)
        except Exception as e:
            return {"ok": False, "error": f"Lecture impossible : {e}"}
        if not self._engine.running:
            return {"ok": False, "error": "Active d’abord le micro : bouton « Micro hors ligne » en haut à gauche."}
        self._mixer.play(data, tag=path)
        return {"ok": True}

    def preview(self, path: str) -> dict:
        """Écoute dans le casque seulement (n'est pas envoyé dans le micro)."""
        try:
            data = self._cache.get(path)
            sinks = self._engine.preview_sinks(self._device("monitor", "output"))
        except Exception as e:
            return {"ok": False, "error": f"Pré-écoute impossible : {e}"}
        if not self._mixer.play(data, tag=path, sinks=sinks):
            return {"ok": False, "error": "Choisis un « Retour casque » dans les réglages pour la pré-écoute."}
        return {"ok": True}

    def stop(self, path: str = "") -> None:
        if path:
            self._mixer.stop(path)
        else:
            self._mixer.stop_all()

    def status(self) -> dict:
        mic, out = self._mixer.levels()
        msg, self._message = self._message, ""
        return {"running": self._engine.running, "playing": self._mixer.active(), "lib_version": self._lib_version,
                "mic_level": mic, "out_level": out, "message": msg}

    # --- mélange ------------------------------------------------------------------------
    def set_mix(self, key: str, value) -> None:
        if key in self._mix_cfg():
            self._cfg[key] = value
            self._apply_mix()
            self._save()

    # --- périphériques -------------------------------------------------------------------
    def devices(self) -> dict:
        try:
            apis = AudioEngine.host_apis()
        except Exception as e:
            return {"error": f"Audio indisponible : {e}", "apis": []}
        api = self._cfg.get("host_api")
        if api not in apis:
            api = next((a for a in ("Windows DirectSound", "MME") if a in apis), apis[0] if apis else "")
        inputs = [n for _i, n in AudioEngine.devices(api, "input")]
        outputs = [n for _i, n in AudioEngine.devices(api, "output")]

        def pick(saved, names, prefer=None, avoid="cable", allow_none=True):
            if saved in names or (allow_none and saved == ""):
                return saved
            for n in names:
                if prefer and prefer in n.lower():
                    return n
            others = [n for n in names if avoid not in n.lower()]
            return others[0] if others else ("" if allow_none else (names[0] if names else ""))

        sel = {"host_api": api,
               "mic": pick(self._cfg.get("mic"), inputs),
               "cable": pick(self._cfg.get("cable"), outputs, prefer="cable input", avoid="\0", allow_none=False),
               "monitor": pick(self._cfg.get("monitor"), outputs)}
        return {"apis": apis, "inputs": inputs, "outputs": outputs, "selected": sel,
                "cable_found": any("cable" in n.lower() for n in outputs), **self._mix_cfg(),
                "stop_hotkey": self._cfg.get("stop_hotkey", ""), "sounds_root": str(self._root)}

    def set_device(self, key: str, value: str) -> dict:
        if key in ("host_api", "mic", "cable", "monitor"):
            self._cfg[key] = value
            if key == "host_api":
                for k in ("mic", "cable", "monitor"):
                    self._cfg.pop(k, None)
            self._engine.close_preview()
            self._save()
        return self.devices()

    def _device(self, key: str, kind: str) -> int | None:
        name = self._cfg.get(key)
        if not name:
            return None
        api = self._cfg.get("host_api") or self.devices()["selected"]["host_api"]
        return next((i for i, n in AudioEngine.devices(api, kind) if n == name), None)

    def start(self) -> dict:
        sel = self.devices().get("selected")
        if not sel:
            return {"ok": False, "error": "Aucun périphérique audio."}
        self._cfg.update(sel)
        cable = self._device("cable", "output")
        if cable is None:
            return {"ok": False, "error": "Choisis la sortie « CABLE Input » dans les réglages."}
        try:
            self._engine.start(self._device("mic", "input"), cable, self._device("monitor", "output"))
        except Exception as e:
            return {"ok": False, "error": f"Impossible d'ouvrir les périphériques : {e}. "
                                          "Essaie le pilote « MME » dans les réglages."}
        self._save()
        return {"ok": True}

    def stop_engine(self) -> None:
        self._engine.stop()

    # --- raccourcis clavier globaux ------------------------------------------------------------
    def set_hotkey(self, path: str, combo: str) -> str:
        for p, c in list(self._hotkeys.items()):
            if c == combo:
                del self._hotkeys[p]
        if combo:
            self._hotkeys[path] = combo
        else:
            self._hotkeys.pop(path, None)
        self._register_hotkeys()
        self._save()
        return combo

    def set_stop_hotkey(self, combo: str) -> None:
        self._cfg["stop_hotkey"] = combo
        self._register_hotkeys()
        self._save()

    def _hotkey_play(self, path: str) -> None:
        r = self.play(path)
        if not r["ok"]:
            self._message = r["error"]

    def _register_hotkeys(self) -> None:
        try:
            import keyboard
            keyboard.unhook_all_hotkeys()
        except Exception:
            return
        for path, combo in self._hotkeys.items():
            if Path(path).exists():
                try:
                    keyboard.add_hotkey(combo, self._hotkey_play, args=(path,))
                except Exception:
                    pass
        if self._cfg.get("stop_hotkey"):
            try:
                keyboard.add_hotkey(self._cfg["stop_hotkey"], self._mixer.stop_all)
            except Exception:
                pass

    # --- recherche sur le PC -------------------------------------------------------------
    def disk_roots(self) -> list[dict]:
        home = Path.home()
        out = [{"path": str(home), "label": "Mon dossier utilisateur"}]
        for d in drives():
            if d != str(home):
                out.append({"path": d, "label": f"Disque {d.rstrip(os.sep)}"})
        return out

    def pick_folder(self) -> str | None:
        return self._dialog("folder", str(Path.home()))

    def disk_search(self, query: str, roots: list[str]) -> str:
        for s in self._disk.values():
            s.cancel()
        sid = uuid.uuid4().hex
        self._disk = {sid: DiskSearch(roots, query).start()}
        return sid

    def disk_poll(self, sid: str, since: int = 0) -> dict:
        s = self._disk.get(sid)
        if not s:
            return {"results": [], "total": 0, "done": True, "dirs": 0, "files": 0, "current": ""}
        return s.poll(int(since))

    def disk_cancel(self) -> None:
        for s in self._disk.values():
            s.cancel()

    def add_files(self, paths: list[str], folder: str = "Mes sons") -> dict:
        n = importers.import_files(paths, self._dest(folder or "Mes sons"))
        self.rescan()
        return {"added": n}

    def import_folder_path(self, src: str, folder: str = "") -> dict:
        """Copie un dossier (et toute son arborescence) dans `folder` de la bibliothèque."""
        try:
            n, created = importers.import_folder(Path(src), self._dest(folder))
        except Exception as e:
            return {"error": str(e)}
        self.rescan()
        return {"added": n, "rel": created.relative_to(self._root).as_posix()}

    def import_folder(self, folder: str = "") -> dict:
        """Demande un dossier à importer dans `folder` (« » = nouvelle catégorie)."""
        src = self._dialog("folder", str(Path.home()))
        if not src:
            return {"cancelled": True}
        return self.import_folder_path(src, folder)

    def pick_files(self, folder: str = "") -> dict:
        src = self._dialog("file", "", ("Sons (*.wav;*.ogg;*.flac;*.mp3)", "Tous les fichiers (*.*)"),
                           multiple=True)
        if not src:
            return {"cancelled": True}
        return self.add_files(src, folder or "Mes sons")

    # --- imports depuis les jeux -----------------------------------------------------------
    def _dialog(self, kind: str, directory: str = "", file_types=(), multiple: bool = False):
        import webview
        dlg = {"folder": webview.FOLDER_DIALOG, "file": webview.OPEN_DIALOG}[kind]
        res = self._window.create_file_dialog(dlg, directory=directory, file_types=tuple(file_types),
                                              allow_multiple=multiple)
        if not res:
            return None
        if isinstance(res, str):
            return [res] if multiple else res
        return list(res) if multiple else res[0]

    def import_game(self, game: str, voices_only: bool = True) -> dict:
        try:
            if game == "et":
                c = importers.et_candidates()
                src = self._dialog("folder", str(c[0]) if c else "")
                if not src:
                    return {"cancelled": True}
                n = importers.import_et(Path(src), self._root / "Wolfenstein Enemy Territory", voices_only)
            elif game == "css":
                c = importers.css_candidates()
                src = self._dialog("file", str(c[0].parent) if c else "",
                                   ("Archive VPK (*_dir.vpk)", "Tous les fichiers (*.*)"))
                if not src:
                    return {"cancelled": True}
                n = importers.import_css(Path(src), self._root / "Counter-Strike Source", voices_only)
            elif game == "wc3":
                src = self._dialog("folder")
                if not src:
                    return {"cancelled": True}
                n = importers.import_wc3(Path(src), self._root / "Warcraft III - Paysans", True)
            elif game == "files":
                src = self._dialog("file", "", ("Sons (*.wav;*.ogg;*.flac;*.mp3)", "Tous les fichiers (*.*)"),
                                   multiple=True)
                if not src:
                    return {"cancelled": True}
                n = importers.import_files(src, self._root / "Mes sons")
            else:
                return {"error": "Jeu inconnu"}
        except Exception as e:
            return {"error": str(e)}
        self.rescan()
        return {"added": n}

    # --- divers ------------------------------------------------------------------------
    def open_folder(self, path: str = "") -> None:
        target = Path(path) if path else self._root
        if target.is_file():
            target = target.parent
        if sys.platform == "win32":
            os.startfile(target)  # type: ignore[attr-defined]
        else:
            subprocess.Popen(["xdg-open", str(target)])

    def change_root(self) -> dict:
        src = self._dialog("folder", str(self._root))
        if src:
            self._root = Path(src)
            library.ensure_root(self._root)
            self._save()
            self.rescan()
        return self.devices()

    # --- assistant de configuration ------------------------------------------------------
    def first_run(self) -> bool:
        """Vrai tant que l'assistant de configuration n'a jamais été terminé."""
        return not self._cfg.get("setup_done")

    def setup_done(self, done: bool = True) -> None:
        self._cfg["setup_done"] = bool(done)
        self._save()

    def setup_status(self, refresh: bool = False) -> dict:
        """État de chaque étape, pour les coches de l'assistant."""
        if refresh and not self._engine.running:
            try:
                self._engine.close_preview()
                AudioEngine.refresh_devices()
            except Exception:
                pass
        dev = self.devices()
        sel = dev.get("selected", {})
        return {"cable_found": dev.get("cable_found", False), "mic": sel.get("mic", ""),
                "cable": sel.get("cable", ""), "monitor": sel.get("monitor", ""),
                "cable_ok": "cable" in sel.get("cable", "").lower(),
                "running": self._engine.running, "sounds": len(self._index.entries),
                "setup_done": bool(self._cfg.get("setup_done")), "error": dev.get("error", "")}

    def play_test(self) -> dict:
        """Petit carillon de test (deux notes), envoyé dans le micro et le casque."""
        if not self._engine.running:
            return {"ok": False, "error": "Active d'abord le micro."}
        import numpy as np
        from .audio import SAMPLE_RATE
        t = np.arange(int(0.22 * SAMPLE_RATE)) / SAMPLE_RATE
        env = np.minimum(1, t * 80) * np.exp(-t * 9)
        notes = [np.sin(2 * np.pi * f * t) * env for f in (660, 880)]
        gap = np.zeros(int(0.03 * SAMPLE_RATE))
        tone = np.concatenate([notes[0], gap, notes[1], gap, notes[1] * 0.6]).astype(np.float32) * 0.6
        self._mixer.play(tone, tag="__test__")
        return {"ok": True}

    def open_url(self, which: str) -> None:
        """Ouvre une page connue dans le navigateur (liste fermée : pas d'adresse venant de la page)."""
        import webbrowser
        urls = {"vbcable": "https://vb-audio.com/Cable/"}
        if which in urls:
            webbrowser.open(urls[which])

    def open_sound_settings(self) -> None:
        """Ouvre le panneau Son de Windows, onglet Enregistrement (micro par défaut)."""
        if sys.platform == "win32":
            subprocess.Popen(["control", "mmsys.cpl,,1"])

    def shutdown(self) -> None:
        self.disk_cancel()
        self._engine.stop()
        self._engine.close_preview()
