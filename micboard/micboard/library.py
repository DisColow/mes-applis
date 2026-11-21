"""Bibliothèque de sons : fichiers rangés par dossier (une catégorie par dossier)."""

from __future__ import annotations

import os
from collections import OrderedDict
from pathlib import Path

import numpy as np

from .audio import SAMPLE_RATE

AUDIO_EXT = {".wav", ".ogg", ".flac", ".mp3", ".aiff", ".aif"}

DEFAULT_CATEGORIES = [
    "Wolfenstein Enemy Territory",
    "Warcraft III - Paysans",
    "Counter-Strike Source",
    "Mes sons",
]


def default_root() -> Path:
    docs = Path.home() / "Documents"
    return (docs if docs.exists() else Path.home()) / "MicBoard" / "sons"


def ensure_root(root: Path) -> None:
    for cat in DEFAULT_CATEGORIES:
        (root / cat).mkdir(parents=True, exist_ok=True)


def is_audio(path: str | os.PathLike) -> bool:
    return Path(path).suffix.lower() in AUDIO_EXT


def scan(root: Path) -> dict:
    """Arbre {'dirs': {nom: sous-arbre}, 'files': [Path]} trié par nom."""
    node = {"dirs": {}, "files": []}
    try:
        entries = sorted(os.scandir(root), key=lambda e: e.name.lower())
    except OSError:
        return node
    for e in entries:
        if e.is_dir():
            node["dirs"][e.name] = scan(Path(e.path))
        elif is_audio(e.name):
            node["files"].append(Path(e.path))
    return node


def resample(data: np.ndarray, src_rate: int, dst_rate: int = SAMPLE_RATE) -> np.ndarray:
    if src_rate == dst_rate or len(data) == 0:
        return data.astype(np.float32)
    n_out = max(1, int(round(len(data) * dst_rate / src_rate)))
    x_out = np.arange(n_out) * (src_rate / dst_rate)
    return np.interp(x_out, np.arange(len(data)), data).astype(np.float32)


def load_sound(path: str | os.PathLike) -> np.ndarray:
    """Charge un fichier en mono float32 à SAMPLE_RATE."""
    import soundfile as sf
    data, rate = sf.read(str(path), dtype="float32", always_2d=True)
    return resample(data.mean(axis=1), rate)


class SoundCache:
    def __init__(self, max_items: int = 200):
        self._items: OrderedDict[str, np.ndarray] = OrderedDict()
        self._max = max_items

    def get(self, path: str | os.PathLike) -> np.ndarray:
        key = str(path)
        if key in self._items:
            self._items.move_to_end(key)
            return self._items[key]
        data = load_sound(key)
        self._items[key] = data
        while len(self._items) > self._max:
            self._items.popitem(last=False)
        return data
