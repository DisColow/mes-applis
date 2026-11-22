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


# --- métadonnées (titre, commentaire, durée) ---------------------------------------------------
TAG_MAX = 120  # au-delà, un commentaire est coupé pour l'affichage

# Encodages proposés pour les balises qui ne sont pas en UTF-8 (fréquent dans les WAV et les vieux MP3).
TAG_ENCODINGS = {
    "auto": "Automatique (UTF-8, sinon Windows-1252)",
    "cp1252": "Windows-1252 (Europe de l'Ouest)",
    "latin-1": "ISO-8859-1 (Latin-1)",
    "cp850": "DOS / IBM 850",
    "cp1250": "Windows-1250 (Europe centrale)",
    "cp1251": "Windows-1251 (cyrillique)",
    "shift_jis": "Shift-JIS (japonais)",
    "gbk": "GBK (chinois simplifié)",
    "utf-8": "UTF-8",
}


def decode_text(raw: bytes, encoding: str = "auto") -> str:
    """Décode le texte brut d'une balise.

    Automatique : marque d'ordre des octets (BOM) si présente, sinon UTF-8 strict, sinon
    Windows-1252 (ce qu'écrivent la plupart des logiciels Windows), sinon Latin-1 (jamais d'échec).
    Un encodage forcé est appliqué tel quel, les octets invalides devenant « � ».
    """
    if not raw:
        return ""
    for bom, enc in ((b"\xef\xbb\xbf", "utf-8"), (b"\xff\xfe", "utf-16-le"), (b"\xfe\xff", "utf-16-be")):
        if raw.startswith(bom):
            return raw[len(bom):].decode(enc, "replace")
    if encoding and encoding != "auto":
        try:
            return raw.decode(encoding, "replace")
        except LookupError:
            pass
    for enc in ("utf-8", "cp1252"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("latin-1")


def _clean(value) -> str:
    """Texte de balise lisible : 1re ligne, sans caractères nuls ni espaces superflus."""
    if isinstance(value, (list, tuple)):
        value = next((v for v in value if str(v).strip()), "")
    text = str(value or "").replace("\x00", " ").strip()
    text = " ".join(text.splitlines()[0].split()) if text else ""
    return text[:TAG_MAX - 1] + "…" if len(text) > TAG_MAX else text


def _id3_text(frames, encoding: str) -> str:
    """Texte des cadres ID3. Ceux marqués « Latin-1 » contiennent souvent autre chose (UTF-8,
    Windows-1252, cyrillique…) : on reprend leurs octets et on les décode nous-mêmes."""
    for frame in frames:
        for text in getattr(frame, "text", []):
            text = str(text)
            if int(getattr(frame, "encoding", 3)) == 0:
                text = decode_text(text.encode("latin-1", "replace"), encoding)
            text = _clean(text)
            if text:
                return text
    return ""


def _mutagen_tags(path: str, encoding: str = "auto") -> tuple[str, str, float | None]:
    try:
        import mutagen
        f = mutagen.File(path)
    except Exception:
        return "", "", None
    if f is None:
        return "", "", None
    duration = getattr(getattr(f, "info", None), "length", None)
    tags = getattr(f, "tags", None)
    title = comment = ""
    if tags is not None:
        if hasattr(tags, "getall"):  # ID3 (MP3, et WAV/AIFF avec un bloc ID3)
            title = _id3_text(tags.getall("TIT2"), encoding)
            comment = _id3_text(tags.getall("COMM"), encoding)
        else:  # Vorbis (OGG/FLAC), MP4…
            def get(*keys):
                for k in keys:
                    try:
                        v = tags.get(k)
                    except Exception:
                        v = None
                    if v:
                        return _clean(v)
                return ""
            title = get("title", "TITLE", "\xa9nam")
            comment = get("comment", "COMMENT", "description", "DESCRIPTION", "\xa9cmt")
    return title, comment, duration


def _sf_string(f, name: str, encoding: str) -> str:
    """Balise texte lue par libsndfile. soundfile la décode en UTF-8 et remplace le reste par « � » :
    on relit donc ses octets bruts pour les décoder correctement."""
    try:
        import soundfile as sf
        ptr = sf._snd.sf_get_string(f._file, sf._str_types[name])
        return _clean(decode_text(sf._ffi.string(ptr), encoding)) if ptr else ""
    except Exception:  # fonctions internes absentes dans une autre version de soundfile
        try:
            return _clean(getattr(f, name))
        except Exception:
            return ""


def read_tags(path: str | os.PathLike, encoding: str = "auto") -> dict:
    """{'title', 'comment', 'duration'} d'un fichier audio ; chaînes vides si absentes.
    `encoding` sert aux balises qui ne sont pas en UTF-8 (voir TAG_ENCODINGS)."""
    path = str(path)
    title = comment = ""
    duration = None
    try:
        import soundfile as sf
        with sf.SoundFile(path) as f:  # WAV (bloc INFO), FLAC, OGG…
            title, comment = _sf_string(f, "title", encoding), _sf_string(f, "comment", encoding)
            duration = f.frames / f.samplerate if f.samplerate else None
    except Exception:
        pass
    if not title or not comment or duration is None:
        m_title, m_comment, m_duration = _mutagen_tags(path, encoding)
        title = title or m_title
        comment = comment or m_comment
        duration = duration if duration is not None else m_duration
    return {"title": title, "comment": comment, "duration": duration or 0.0}


def display_name(stem: str, tags: dict | None) -> tuple[str, str]:
    """Nom à afficher et sa source : le titre, sinon le commentaire, sinon le nom du fichier."""
    from .search import pretty_name
    if tags:
        for key in ("title", "comment"):
            value = (tags.get(key) or "").strip()
            if value and value.lower() != stem.lower():
                return value, key
    return pretty_name(stem), "file"


class TagCache:
    """Cache des métadonnées sur disque (clé : chemin ; valide tant que taille et date n'ont pas changé)."""

    VERSION = 2  # la version 1 gardait des titres mal décodés (« � ») : elle est ignorée

    def __init__(self, file: Path | None, encoding: str = "auto"):
        self.file = file
        self.encoding = encoding
        self._data: dict[str, list] = {}
        self.dirty = False
        if file:
            try:
                import json
                saved = json.loads(Path(file).read_text(encoding="utf-8"))
                if saved.get("version") == self.VERSION and saved.get("encoding") == encoding:
                    self._data = saved["items"]
            except Exception:
                self._data = {}

    @staticmethod
    def _stamp(path: str) -> tuple[int, int] | None:
        try:
            st = os.stat(path)
            return st.st_size, int(st.st_mtime)
        except OSError:
            return None

    def get(self, path: str) -> dict | None:
        item = self._data.get(path)
        stamp = self._stamp(path)
        if item and stamp and tuple(item[:2]) == stamp:
            return {"title": item[2], "comment": item[3], "duration": item[4]}
        return None

    def put(self, path: str, tags: dict) -> None:
        stamp = self._stamp(path)
        if stamp:
            self._data[path] = [*stamp, tags["title"], tags["comment"], tags["duration"]]
            self.dirty = True

    def save(self, keep: set[str] | None = None) -> None:
        if not self.file or not self.dirty:
            return
        if keep is not None:
            self._data = {k: v for k, v in self._data.items() if k in keep}
        try:
            import json
            Path(self.file).parent.mkdir(parents=True, exist_ok=True)
            Path(self.file).write_text(json.dumps({"version": self.VERSION, "encoding": self.encoding,
                                                   "items": self._data}, ensure_ascii=False), encoding="utf-8")
            self.dirty = False
        except OSError:
            pass
