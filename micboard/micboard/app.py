"""Interface Windows (tkinter) de MicBoard."""

from __future__ import annotations

import json
import os
import random
import subprocess
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

from . import importers, library
from .audio import AudioEngine, Mixer

APP = "MicBoard"
NO_DEVICE = "(aucun)"
HELP = """Comment ça marche

1. Installe VB-Audio Virtual Cable (gratuit) : https://vb-audio.com/Cable/
   Il crée deux périphériques : « CABLE Input » (une sortie) et « CABLE Output » (un micro).

2. Dans MicBoard :
   • Micro réel : ton vrai micro (il est mélangé aux sons).
   • Sortie vers le câble : « CABLE Input ».
   • Retour casque : ton casque, pour entendre les sons toi aussi (facultatif).
   Puis clique sur « Démarrer ».

3. Dans Discord, le jeu, OBS… choisis « CABLE Output » comme micro.

Sons : menu « Importer » pour les copier depuis tes jeux installés.
Double-clic ou Entrée = jouer.  Échap = tout arrêter.
Clic droit sur un son = raccourci clavier global / favori.
Les raccourcis marchent même quand le jeu est au premier plan
(si le jeu tourne en administrateur, lance MicBoard en administrateur aussi)."""


def config_path() -> Path:
    base = Path(os.environ.get("APPDATA", Path.home() / ".config"))
    return base / APP / "config.json"


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.cfg = self._load_cfg()
        self.sounds_root = Path(self.cfg.get("sounds_root") or library.default_root())
        library.ensure_root(self.sounds_root)
        self.mixer = Mixer()
        self.engine = AudioEngine(self.mixer)
        self.cache = library.SoundCache()
        self.hotkeys: dict[str, str] = self.cfg.get("hotkeys", {})  # chemin -> combinaison
        self.stop_hotkey = self.cfg.get("stop_hotkey", "")
        self.favorites: list[str] = self.cfg.get("favorites", [])
        self.all_files: list[Path] = []

        root.title(f"{APP} — sons dans ton micro")
        root.geometry("980x680")
        root.minsize(760, 520)
        self._build_ui()
        self._refresh_apis()
        self.refresh_library()
        self._register_hotkeys()
        root.protocol("WM_DELETE_WINDOW", self.on_close)
        root.bind("<Escape>", lambda _e: self.stop_all())
        if not self.cfg.get("help_shown"):
            self.root.after(300, self.show_help)
            self.cfg["help_shown"] = True
            self._save_cfg()

    # --- configuration -----------------------------------------------------------------
    def _load_cfg(self) -> dict:
        try:
            return json.loads(config_path().read_text(encoding="utf-8"))
        except Exception:
            return {}

    def _save_cfg(self) -> None:
        self.cfg.update(hotkeys=self.hotkeys, favorites=self.favorites,
                        stop_hotkey=self.stop_hotkey, sounds_root=str(self.sounds_root),
                        sound_volume=self.vol_sounds.get(), mic_volume=self.vol_mic.get(),
                        monitor_volume=self.vol_monitor.get(), mic_enabled=self.mic_on.get(),
                        exclusive=self.exclusive.get())
        p = config_path()
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(self.cfg, indent=2, ensure_ascii=False), encoding="utf-8")

    # --- interface -----------------------------------------------------------------------
    def _build_ui(self) -> None:
        menubar = tk.Menu(self.root)
        imp = tk.Menu(menubar, tearoff=False)
        imp.add_command(label="Wolfenstein: Enemy Territory…", command=self.import_et)
        imp.add_command(label="Counter-Strike: Source…", command=self.import_css)
        imp.add_command(label="Warcraft III (dossier extrait)…", command=self.import_wc3)
        imp.add_separator()
        imp.add_command(label="Ajouter mes propres fichiers…", command=self.import_files)
        menubar.add_cascade(label="Importer", menu=imp)
        opts = tk.Menu(menubar, tearoff=False)
        opts.add_command(label="Ouvrir le dossier des sons", command=self.open_folder)
        opts.add_command(label="Changer le dossier des sons…", command=self.change_root)
        opts.add_command(label="Raccourci « tout arrêter »…", command=self.set_stop_hotkey)
        menubar.add_cascade(label="Options", menu=opts)
        menubar.add_command(label="Aide", command=self.show_help)
        self.root.config(menu=menubar)

        dev = ttk.LabelFrame(self.root, text="Périphériques", padding=8)
        dev.pack(fill="x", padx=8, pady=(8, 4))
        self.api_var = tk.StringVar()
        self.mic_var = tk.StringVar()
        self.cable_var = tk.StringVar()
        self.mon_var = tk.StringVar()
        rows = [("Pilote audio", self.api_var), ("Micro réel", self.mic_var),
                ("Sortie vers le câble", self.cable_var), ("Retour casque", self.mon_var)]
        self.combos = {}
        for i, (label, var) in enumerate(rows):
            ttk.Label(dev, text=label).grid(row=i // 2, column=(i % 2) * 2, sticky="w", padx=(0, 6), pady=2)
            cb = ttk.Combobox(dev, textvariable=var, state="readonly", width=42)
            cb.grid(row=i // 2, column=(i % 2) * 2 + 1, sticky="ew", padx=(0, 14), pady=2)
            self.combos[label] = cb
        dev.columnconfigure(1, weight=1)
        dev.columnconfigure(3, weight=1)
        self.combos["Pilote audio"].bind("<<ComboboxSelected>>", lambda _e: self._refresh_devices())
        self.start_btn = ttk.Button(dev, text="▶ Démarrer", command=self.toggle_engine)
        self.start_btn.grid(row=0, column=4, rowspan=2, sticky="ns", padx=(4, 0))

        mix = ttk.Frame(self.root, padding=(8, 0))
        mix.pack(fill="x")
        self.vol_sounds = tk.DoubleVar(value=self.cfg.get("sound_volume", 0.8))
        self.vol_mic = tk.DoubleVar(value=self.cfg.get("mic_volume", 1.0))
        self.vol_monitor = tk.DoubleVar(value=self.cfg.get("monitor_volume", 0.6))
        for label, var in (("Volume sons", self.vol_sounds), ("Volume micro", self.vol_mic),
                           ("Volume casque", self.vol_monitor)):
            ttk.Label(mix, text=label).pack(side="left")
            ttk.Scale(mix, from_=0, to=1.5, variable=var, length=130,
                      command=lambda _v: self._apply_mix()).pack(side="left", padx=(4, 14))
        self.mic_on = tk.BooleanVar(value=self.cfg.get("mic_enabled", True))
        self.exclusive = tk.BooleanVar(value=self.cfg.get("exclusive", False))
        ttk.Checkbutton(mix, text="Transmettre mon micro", variable=self.mic_on,
                        command=self._apply_mix).pack(side="left", padx=4)
        ttk.Checkbutton(mix, text="Un seul son à la fois", variable=self.exclusive,
                        command=self._apply_mix).pack(side="left", padx=4)
        self._apply_mix()

        body = ttk.PanedWindow(self.root, orient="horizontal")
        body.pack(fill="both", expand=True, padx=8, pady=6)

        left = ttk.Frame(body)
        body.add(left, weight=3)
        top = ttk.Frame(left)
        top.pack(fill="x")
        ttk.Label(top, text="🔍").pack(side="left")
        self.search = tk.StringVar()
        self.search.trace_add("write", lambda *_a: self._fill_tree())
        ttk.Entry(top, textvariable=self.search).pack(side="left", fill="x", expand=True, padx=4)
        ttk.Button(top, text="⟳", width=3, command=self.refresh_library).pack(side="left")

        self.tree = ttk.Treeview(left, columns=("key",), selectmode="browse")
        self.tree.heading("#0", text="Sons")
        self.tree.heading("key", text="Raccourci")
        self.tree.column("key", width=110, stretch=False)
        sb = ttk.Scrollbar(left, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=sb.set)
        self.tree.pack(side="left", fill="both", expand=True, pady=4)
        sb.pack(side="left", fill="y", pady=4)
        self.tree.bind("<Double-1>", lambda _e: self.play_selected())
        self.tree.bind("<Return>", lambda _e: self.play_selected())
        self.tree.bind("<Button-3>", self._context_menu)

        right = ttk.Frame(body, padding=(8, 0, 0, 0))
        body.add(right, weight=2)
        btns = ttk.Frame(right)
        btns.pack(fill="x")
        ttk.Button(btns, text="▶ Jouer", command=self.play_selected).pack(side="left", fill="x", expand=True)
        ttk.Button(btns, text="■ Stop", command=self.stop_all).pack(side="left", fill="x", expand=True, padx=4)
        ttk.Button(btns, text="🎲 Au hasard", command=self.play_random).pack(side="left", fill="x", expand=True)
        ttk.Label(right, text="Favoris (clic droit sur un son pour en ajouter)").pack(anchor="w", pady=(10, 2))
        self.fav_frame = ttk.Frame(right)
        self.fav_frame.pack(fill="both", expand=True)

        self.status = tk.StringVar(value="Prêt.")
        ttk.Label(self.root, textvariable=self.status, relief="sunken", anchor="w",
                  padding=(6, 2)).pack(fill="x", side="bottom")

    def _apply_mix(self) -> None:
        self.mixer.sound_gain = self.vol_sounds.get()
        self.mixer.mic_gain = self.vol_mic.get()
        self.mixer.monitor_gain = self.vol_monitor.get()
        self.mixer.mic_enabled = self.mic_on.get()
        self.mixer.exclusive = self.exclusive.get()

    # --- périphériques -------------------------------------------------------------------
    def _refresh_apis(self) -> None:
        try:
            apis = AudioEngine.host_apis()
        except Exception as e:
            self.status.set(f"Audio indisponible : {e}")
            return
        self.combos["Pilote audio"]["values"] = apis
        wanted = self.cfg.get("host_api")
        if wanted not in apis:
            wanted = next((a for a in ("Windows DirectSound", "MME") if a in apis), apis[0] if apis else "")
        self.api_var.set(wanted)
        self._refresh_devices()

    def _refresh_devices(self) -> None:
        api = self.api_var.get()
        self.inputs = AudioEngine.devices(api, "input")
        self.outputs = AudioEngine.devices(api, "output")
        in_names = [n for _i, n in self.inputs]
        out_names = [n for _i, n in self.outputs]
        self.combos["Micro réel"]["values"] = [NO_DEVICE] + in_names
        self.combos["Sortie vers le câble"]["values"] = out_names
        self.combos["Retour casque"]["values"] = [NO_DEVICE] + out_names

        def pick(var, names, saved, prefer=None, avoid=None, allow_none=True):
            if saved in names or (allow_none and saved == NO_DEVICE):
                var.set(saved)
                return
            for n in names:
                if prefer and prefer.lower() in n.lower():
                    var.set(n)
                    return
            others = [n for n in names if not (avoid and avoid.lower() in n.lower())]
            var.set(others[0] if others else (NO_DEVICE if allow_none else ""))

        pick(self.mic_var, in_names, self.cfg.get("mic"), avoid="cable")
        pick(self.cable_var, out_names, self.cfg.get("cable"), prefer="cable input", allow_none=False)
        pick(self.mon_var, out_names, self.cfg.get("monitor"), avoid="cable")
        if not any("cable" in n.lower() for n in out_names):
            self.status.set("« CABLE Input » introuvable : installe VB-Audio Virtual Cable (voir Aide).")

    @staticmethod
    def _index(devices, name):
        return next((i for i, n in devices if n == name), None)

    def toggle_engine(self) -> None:
        if self.engine.running:
            self.engine.stop()
            self.start_btn.config(text="▶ Démarrer")
            self.status.set("Arrêté.")
            return
        cable = self._index(self.outputs, self.cable_var.get())
        if cable is None:
            messagebox.showwarning(APP, "Choisis une sortie vers le câble (« CABLE Input »).")
            return
        mic = self._index(self.inputs, self.mic_var.get())
        mon = self._index(self.outputs, self.mon_var.get())
        try:
            self.engine.start(mic, cable, mon)
        except Exception as e:
            messagebox.showerror(APP, f"Impossible d'ouvrir les périphériques :\n{e}\n\n"
                                      "Essaie un autre « Pilote audio » (MME marche presque toujours).")
            return
        self.cfg.update(host_api=self.api_var.get(), mic=self.mic_var.get(),
                        cable=self.cable_var.get(), monitor=self.mon_var.get())
        self._save_cfg()
        self.start_btn.config(text="■ Arrêter")
        self.status.set(f"En marche → {self.cable_var.get()}. Choisis « CABLE Output » comme micro dans tes applis.")

    # --- bibliothèque --------------------------------------------------------------------
    def refresh_library(self) -> None:
        self.tree_data = library.scan(self.sounds_root)
        self.all_files = []

        def collect(node):
            self.all_files.extend(node["files"])
            for sub in node["dirs"].values():
                collect(sub)
        collect(self.tree_data)
        self._fill_tree()
        self._fill_favorites()
        self.status.set(f"{len(self.all_files)} sons dans {self.sounds_root}")

    def _fill_tree(self) -> None:
        q = self.search.get().strip().lower()
        self.tree.delete(*self.tree.get_children())

        def add(parent, node, path):
            shown = 0
            for name, sub in node["dirs"].items():
                iid = self.tree.insert(parent, "end", iid=f"dir:{path / name}", text=f"📁 {name}", open=bool(q))
                n = add(iid, sub, path / name)
                if q and n == 0:
                    self.tree.delete(iid)
                shown += n
            for f in node["files"]:
                if q and q not in str(f.relative_to(self.sounds_root)).lower():
                    continue
                self.tree.insert(parent, "end", iid=str(f), text=f.stem, values=(self.hotkeys.get(str(f), ""),))
                shown += 1
            return shown
        add("", self.tree_data, self.sounds_root)

    def _fill_favorites(self) -> None:
        for w in self.fav_frame.winfo_children():
            w.destroy()
        self.favorites = [f for f in self.favorites if Path(f).exists()]
        for i, f in enumerate(self.favorites):
            b = ttk.Button(self.fav_frame, text=Path(f).stem[:22], command=lambda p=f: self.play(p))
            b.grid(row=i // 3, column=i % 3, sticky="ew", padx=2, pady=2)
            b.bind("<Button-3>", lambda _e, p=f: self._toggle_favorite(p))
        for c in range(3):
            self.fav_frame.columnconfigure(c, weight=1)

    # --- lecture ------------------------------------------------------------------------
    def play(self, path: str) -> None:
        try:
            data = self.cache.get(path)
        except Exception as e:
            self.root.after(0, self.status.set, f"Lecture impossible de {Path(path).name} : {e}")
            return
        if not self.engine.running:
            self.root.after(0, self.status.set, "Clique d'abord sur « Démarrer » pour envoyer les sons.")
        self.mixer.play(data)

    def play_selected(self) -> None:
        sel = self.tree.selection()
        if sel and not sel[0].startswith("dir:"):
            self.play(sel[0])

    def play_random(self) -> None:
        sel = self.tree.selection()
        pool = self.all_files
        if sel and sel[0].startswith("dir:"):
            folder = sel[0][4:]
            pool = [f for f in self.all_files if str(f).startswith(folder + os.sep)] or pool
        if pool:
            self.play(str(random.choice(pool)))

    def stop_all(self) -> None:
        self.mixer.stop_all()

    # --- clic droit, raccourcis, favoris -----------------------------------------------------
    def _context_menu(self, event) -> None:
        iid = self.tree.identify_row(event.y)
        if not iid or iid.startswith("dir:"):
            return
        self.tree.selection_set(iid)
        m = tk.Menu(self.root, tearoff=False)
        m.add_command(label="Jouer", command=lambda: self.play(iid))
        fav = "Retirer des favoris" if iid in self.favorites else "Ajouter aux favoris"
        m.add_command(label=fav, command=lambda: self._toggle_favorite(iid))
        m.add_command(label="Définir un raccourci clavier…", command=lambda: self._capture_hotkey(iid))
        if iid in self.hotkeys:
            m.add_command(label="Supprimer le raccourci", command=lambda: self._set_hotkey(iid, None))
        m.tk_popup(event.x_root, event.y_root)

    def _toggle_favorite(self, path: str) -> None:
        if path in self.favorites:
            self.favorites.remove(path)
        else:
            self.favorites.append(path)
        self._fill_favorites()
        self._save_cfg()

    def _read_hotkey(self, on_done) -> None:
        try:
            import keyboard
        except Exception:
            messagebox.showerror(APP, "Raccourcis globaux indisponibles (module keyboard manquant).")
            return
        self.status.set("Appuie sur la combinaison de touches voulue…")

        def worker():
            combo = keyboard.read_hotkey(suppress=False)
            self.root.after(0, on_done, combo)
        threading.Thread(target=worker, daemon=True).start()

    def _capture_hotkey(self, path: str) -> None:
        self._read_hotkey(lambda combo: self._set_hotkey(path, combo))

    def _set_hotkey(self, path: str, combo: str | None) -> None:
        if combo:
            for p, c in list(self.hotkeys.items()):
                if c == combo:
                    del self.hotkeys[p]
            self.hotkeys[path] = combo
            self.status.set(f"Raccourci « {combo} » → {Path(path).stem}")
        else:
            self.hotkeys.pop(path, None)
        self._register_hotkeys()
        self._fill_tree()
        self._save_cfg()

    def set_stop_hotkey(self) -> None:
        def done(combo):
            self.stop_hotkey = combo
            self.status.set(f"Raccourci « tout arrêter » : {combo}")
            self._register_hotkeys()
            self._save_cfg()
        self._read_hotkey(done)

    def _register_hotkeys(self) -> None:
        try:
            import keyboard
            keyboard.unhook_all_hotkeys()
        except Exception:
            return
        for path, combo in self.hotkeys.items():
            if Path(path).exists():
                try:
                    keyboard.add_hotkey(combo, self.play, args=(path,))
                except Exception:
                    pass
        if self.stop_hotkey:
            try:
                keyboard.add_hotkey(self.stop_hotkey, self.stop_all)
            except Exception:
                pass

    # --- imports -----------------------------------------------------------------------
    def _run_import(self, label: str, fn, *args) -> None:
        self.status.set(f"Import {label}…")

        def progress(msg):
            self.root.after(0, self.status.set, f"{label} : {msg}")

        def worker():
            try:
                n = fn(*args, progress=progress)
                self.root.after(0, self._import_done, label, n, None)
            except Exception as e:
                self.root.after(0, self._import_done, label, 0, e)
        threading.Thread(target=worker, daemon=True).start()

    def _import_done(self, label, n, err) -> None:
        if err:
            messagebox.showerror(APP, f"Import {label} impossible :\n{err}")
            self.status.set("Import échoué.")
            return
        self.refresh_library()
        messagebox.showinfo(APP, f"{label} : {n} sons importés.")

    def _ask_voices(self, what: str) -> bool | None:
        return messagebox.askyesnocancel(
            APP, f"Importer seulement {what} (recommandé) ?\n\n"
                 "Oui = seulement ces sons\nNon = TOUS les sons du jeu (armes, bruitages… beaucoup de fichiers)")

    def import_et(self) -> None:
        cands = importers.et_candidates()
        folder = filedialog.askdirectory(
            title="Dossier d'installation de Wolfenstein: Enemy Territory (ou son dossier etmain)",
            initialdir=str(cands[0]) if cands else None)
        if not folder:
            return
        voices = self._ask_voices("les voix (Medic!, Need backup…)")
        if voices is None:
            return
        self._run_import("Wolfenstein ET", importers.import_et, Path(folder),
                         self.sounds_root / "Wolfenstein Enemy Territory", voices)

    def import_css(self) -> None:
        cands = importers.css_candidates()
        f = filedialog.askopenfilename(
            title="Fichier cstrike_pak_dir.vpk de Counter-Strike: Source",
            initialdir=str(cands[0].parent) if cands else None,
            initialfile="cstrike_pak_dir.vpk", filetypes=[("Archive VPK", "*_dir.vpk"), ("Tous", "*.*")])
        if not f:
            return
        voices = self._ask_voices("la radio et les voix des bots (Go go go!, Fire in the hole…)")
        if voices is None:
            return
        self._run_import("Counter-Strike Source", importers.import_css, Path(f),
                         self.sounds_root / "Counter-Strike Source", voices)

    def import_wc3(self) -> None:
        messagebox.showinfo(APP, (
            "Warcraft III range ses sons dans une archive qu'il faut d'abord extraire :\n\n"
            "• Reforged : ouvre le dossier du jeu avec CascView, puis extrais le dossier « units ».\n"
            "• Version classique (1.26 ou avant) : ouvre War3.mpq avec MPQ Editor, puis extrais « Units ».\n\n"
            "Ensuite, choisis le dossier extrait : MicBoard garde seulement les voix des ouvriers "
            "(Paysan, Péon, Acolyte, Feu follet)."))
        folder = filedialog.askdirectory(title="Dossier extrait de Warcraft III")
        if folder:
            self._run_import("Warcraft III", importers.import_wc3, Path(folder),
                             self.sounds_root / "Warcraft III - Paysans", True)

    def import_files(self) -> None:
        files = filedialog.askopenfilenames(
            title="Sons à ajouter", filetypes=[("Sons", "*.wav *.ogg *.flac *.mp3"), ("Tous", "*.*")])
        if not files:
            return
        cats = [d.name for d in sorted(self.sounds_root.iterdir()) if d.is_dir()]
        sel = self.tree.selection()
        dest = self.sounds_root / "Mes sons"
        if sel and sel[0].startswith("dir:"):
            dest = Path(sel[0][4:])
        n = importers.import_files(files, dest)
        self.refresh_library()
        self.status.set(f"{n} sons ajoutés dans « {dest.name} » (catégories : {', '.join(cats)}).")

    # --- divers ------------------------------------------------------------------------
    def open_folder(self) -> None:
        if sys.platform == "win32":
            os.startfile(self.sounds_root)  # type: ignore[attr-defined]
        else:
            subprocess.Popen(["xdg-open", str(self.sounds_root)])

    def change_root(self) -> None:
        folder = filedialog.askdirectory(title="Nouveau dossier des sons", initialdir=str(self.sounds_root))
        if folder:
            self.sounds_root = Path(folder)
            library.ensure_root(self.sounds_root)
            self._save_cfg()
            self.refresh_library()

    def show_help(self) -> None:
        messagebox.showinfo(f"{APP} — Aide", HELP)

    def on_close(self) -> None:
        try:
            self._save_cfg()
        finally:
            self.engine.stop()
            self.root.destroy()


def main() -> None:
    root = tk.Tk()
    try:
        ttk.Style(root).theme_use("vista")
    except tk.TclError:
        pass
    App(root)
    root.mainloop()
