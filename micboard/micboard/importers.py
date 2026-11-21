"""Import des sons depuis les jeux installés sur le PC de l'utilisateur.

Aucun son n'est fourni avec l'appli : ils sont copiés depuis tes propres jeux.

- Wolfenstein: Enemy Territory : les .pk3 du dossier etmain sont des archives zip.
- Counter-Strike: Source : cstrike/cstrike_pak_dir.vpk (archive VPK de Valve).
- Warcraft III : les données sont dans une archive CASC (Reforged) ou MPQ (classique).
  On les extrait d'abord avec CascView / MPQ Editor, puis on importe le dossier extrait :
  seuls les sons des ouvriers (paysan, péon, acolyte, feu follet) sont gardés.
"""

from __future__ import annotations

import os
import re
import shutil
import zipfile
from pathlib import Path, PurePosixPath
from typing import Callable, Iterable

from .library import is_audio

Progress = Callable[[str], None]

ET_VOICE_PREFIXES = ("sound/chat/", "sound/vo/")
CSS_VOICE_PREFIXES = ("sound/radio/", "sound/bot/")

WC3_WORKERS = {
    "peasant": "Humain - Paysan",
    "peon": "Orc - Peon",
    "acolyte": "Mort-vivant - Acolyte",
    "wisp": "Elfe de la nuit - Feu follet",
}


def _write(target: Path, data: bytes) -> bool:
    if target.exists():
        return False
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    return True


def _select(names: Iterable[str], prefixes: tuple[str, ...] | None) -> list[str]:
    audio = [n for n in names if n.lower().startswith("sound/") and is_audio(n)]
    if prefixes:
        chosen = [n for n in audio if n.lower().startswith(prefixes)]
        if chosen:
            return chosen
    return audio


def _relative(name: str) -> PurePosixPath:
    # « sound/chat/allies/x.wav » -> « chat/allies/x.wav »
    return PurePosixPath(*PurePosixPath(name).parts[1:])


# --- Wolfenstein: Enemy Territory ---------------------------------------------------------
def et_candidates() -> list[Path]:
    roots = [os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)"),
             os.environ.get("ProgramFiles", r"C:\Program Files")]
    names = ["Wolfenstein - Enemy Territory", "Enemy Territory", "ET Legacy", "ETLegacy"]
    return [Path(r) / n for r in roots for n in names if (Path(r) / n).exists()]


def find_etmain(folder: Path) -> Path:
    folder = Path(folder)
    if (folder / "etmain").is_dir():
        return folder / "etmain"
    return folder


def import_et(folder: Path, dest: Path, voices_only: bool = True,
              progress: Progress = lambda _m: None) -> int:
    etmain = find_etmain(folder)
    paks = sorted(etmain.glob("*.pk3"), key=lambda p: p.name.lower())
    if not paks:
        raise FileNotFoundError(f"Aucun fichier .pk3 trouvé dans {etmain}")
    count = 0
    for pak in paks:
        progress(f"Lecture de {pak.name}…")
        with zipfile.ZipFile(pak) as z:
            names = [i.filename for i in z.infolist() if not i.is_dir()]
            for name in _select(names, ET_VOICE_PREFIXES if voices_only else None):
                if _write(dest / _relative(name), z.read(name)):
                    count += 1
    return count


# --- Counter-Strike: Source ---------------------------------------------------------------
def steam_libraries() -> list[Path]:
    steam = Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "Steam"
    try:
        import winreg  # type: ignore
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam") as k:
            steam = Path(winreg.QueryValueEx(k, "SteamPath")[0])
    except Exception:
        pass
    libs = [steam]
    vdf = steam / "steamapps" / "libraryfolders.vdf"
    if vdf.exists():
        for m in re.finditer(r'"path"\s+"([^"]+)"', vdf.read_text(errors="ignore")):
            libs.append(Path(m.group(1).replace("\\\\", "\\")))
    return libs


def css_candidates() -> list[Path]:
    found = []
    for lib in steam_libraries():
        p = lib / "steamapps" / "common" / "Counter-Strike Source" / "cstrike" / "cstrike_pak_dir.vpk"
        if p.exists() and p not in found:
            found.append(p)
    return found


def import_css(vpk_path: Path, dest: Path, voices_only: bool = True,
               progress: Progress = lambda _m: None) -> int:
    import vpk
    vpk_path = Path(vpk_path)
    if vpk_path.is_dir():
        guess = vpk_path / "cstrike" / "cstrike_pak_dir.vpk"
        vpk_path = guess if guess.exists() else vpk_path / "cstrike_pak_dir.vpk"
    progress(f"Lecture de {vpk_path.name}…")
    pak = vpk.open(str(vpk_path))
    names = _select(list(pak), CSS_VOICE_PREFIXES if voices_only else None)
    count = 0
    for i, name in enumerate(names):
        if i % 200 == 0:
            progress(f"Extraction {i}/{len(names)}…")
        if _write(dest / _relative(name), pak.get_file(name).read()):
            count += 1
    return count


# --- Warcraft III -------------------------------------------------------------------------
def wc3_worker(path: str) -> str | None:
    low = path.replace("\\", "/").lower()
    for key, label in WC3_WORKERS.items():
        if re.search(rf"(^|/){key}[^/]*\.\w+$", low) or f"/{key}/" in low:
            return label
    return None


def import_wc3(folder: Path, dest: Path, workers_only: bool = True,
               progress: Progress = lambda _m: None) -> int:
    folder = Path(folder)
    count = 0
    for root, _dirs, files in os.walk(folder):
        for f in files:
            if not is_audio(f):
                continue
            src = Path(root) / f
            label = wc3_worker(str(src.relative_to(folder)))
            if label is None:
                if workers_only:
                    continue
                label = "Autres"
            target = dest / label / f
            if not target.exists():
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, target)
                count += 1
                if count % 50 == 0:
                    progress(f"{count} sons copiés…")
    return count


# --- Fichiers quelconques -----------------------------------------------------------------
def import_files(files: Iterable[str], dest: Path) -> int:
    count = 0
    dest.mkdir(parents=True, exist_ok=True)
    for f in files:
        src = Path(f)
        if is_audio(src) and not (dest / src.name).exists():
            shutil.copy2(src, dest / src.name)
            count += 1
    return count
