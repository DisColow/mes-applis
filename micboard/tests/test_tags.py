import time
from pathlib import Path

import numpy as np
import soundfile as sf

from micboard import library
from micboard.search import DiskSearch, Index

from test_search_api import make_api


def write(path: Path, fmt: str = "WAV", **tags) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with sf.SoundFile(str(path), "w", 22050, 1, format=fmt) as f:
        for k, v in tags.items():
            setattr(f, k, v)
        f.write(np.zeros(2205, dtype=np.float32))
    return path


def test_read_tags_wav_flac_and_id3(tmp_path):
    t = library.read_tags(write(tmp_path / "a.wav", title="Paysan : oui ?", comment="Réponse"))
    assert t["title"] == "Paysan : oui ?" and t["comment"] == "Réponse" and abs(t["duration"] - 0.1) < 1e-6
    t = library.read_tags(write(tmp_path / "b.flac", "FLAC", comment="Juste un commentaire"))
    assert t == {"title": "", "comment": "Juste un commentaire", "duration": t["duration"]}

    # titre en ID3, comme dans un MP3 (ici dans un WAV, que mutagen sait écrire)
    from mutagen.id3 import COMM, TIT2
    from mutagen.wave import WAVE
    p = write(tmp_path / "c.wav")
    w = WAVE(str(p))
    w.add_tags()
    w.tags.add(TIT2(encoding=3, text="Titre ID3"))
    w.tags.add(COMM(encoding=3, lang="fra", desc="", text="Commentaire ID3"))
    w.save()
    t = library.read_tags(p)
    assert t["title"] == "Titre ID3" and t["comment"] == "Commentaire ID3"

    assert library.read_tags(write(tmp_path / "d.wav")) == {"title": "", "comment": "", "duration": 0.1}
    (tmp_path / "e.wav").write_bytes(b"pas du son")
    assert library.read_tags(tmp_path / "e.wav")["title"] == ""  # fichier illisible : pas d'erreur


def test_display_name_priority_and_cleanup():
    assert library.display_name("PeasantYes1", {"title": "Oui ?", "comment": "x"}) == ("Oui ?", "title")
    assert library.display_name("PeasantYes1", {"title": "", "comment": "Réponse"}) == ("Réponse", "comment")
    assert library.display_name("PeasantYes1", {"title": "", "comment": ""}) == ("Peasant Yes 1", "file")
    assert library.display_name("go", {"title": "GO", "comment": ""}) == ("Go", "file")  # titre = nom du fichier
    assert library._clean("  ligne 1\nligne 2 ") == "ligne 1"
    assert library._clean("x" * 300).endswith("…") and len(library._clean("x" * 300)) == library.TAG_MAX


def test_tag_cache(tmp_path):
    p = str(write(tmp_path / "a.wav", title="T"))
    cache = library.TagCache(tmp_path / "tags.json")
    cache.put(p, {"title": "T", "comment": "", "duration": 0.1})
    cache.save()
    again = library.TagCache(tmp_path / "tags.json")
    assert again.get(p) == {"title": "T", "comment": "", "duration": 0.1}
    write(Path(p), title="Autre")  # fichier modifié : le cache n'est plus valable
    time.sleep(0.01)
    import os
    os.utime(p, (time.time() + 5, time.time() + 5))
    assert again.get(p) is None


def test_index_finds_by_title_and_by_file_name(tmp_path):
    idx = Index(tmp_path, [tmp_path / "Cat" / "PeasantYes1.wav", tmp_path / "Cat" / "go.wav"])
    assert idx.set_name(str(tmp_path / "Cat" / "PeasantYes1.wav"), "Oui, monseigneur", "title")
    names = lambda q: [e.name for e in idx.search(q)[0]]
    assert names("monseigneur") == ["Oui, monseigneur"]
    assert names("peasant yes") == ["Oui, monseigneur"]  # le nom du fichier marche toujours
    assert names("go") == ["Go"]


def test_api_shows_tags(tmp_path, monkeypatch):
    a, root = make_api(tmp_path, monkeypatch)
    write(root / "Mes sons" / "trompe.wav", title="Le grand klaxon")
    a.rescan()
    for _ in range(100):  # lecture des balises en tâche de fond
        if a.search("grand")["total"]:
            break
        time.sleep(0.02)
    hit = a.search("grand")["results"][0]
    assert hit["name"] == "Le grand klaxon" and hit["source"] == "title" and hit["file"] == "trompe.wav"
    assert a.search("trompe")["total"] == 1
    assert a.status()["lib_version"] >= 2
    assert (tmp_path / "tags.json").exists()

    a2, _ = make_api(tmp_path, monkeypatch)  # relancement : le titre vient du cache, tout de suite
    assert a2.search("grand")["results"][0]["name"] == "Le grand klaxon"


def test_disk_search_matches_titles(tmp_path):
    write(tmp_path / "x" / "piste01.wav", title="Explosion énorme")
    write(tmp_path / "x" / "piste02.wav")
    s = DiskSearch([str(tmp_path)], "explosion").start()
    for _ in range(100):
        if s.done:
            break
        time.sleep(0.02)
    r = s.poll(0)["results"]
    assert [(x["file"], x["name"], x["source"]) for x in r] == [("piste01.wav", "Explosion énorme", "title")]
