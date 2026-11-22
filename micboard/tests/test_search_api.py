import time
import wave
from pathlib import Path

import numpy as np

from micboard import api as apimod
from micboard.audio import SINK_CABLE, SINK_MONITOR, Mixer
from micboard.search import DiskSearch, Index, pretty_name, tokens


def touch_wav(path: Path, seconds: float = 0.5):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(22050)
        w.writeframes(np.zeros(int(22050 * seconds), dtype=np.int16).tobytes())
    return path


def make_index(tmp_path):
    names = ["Warcraft III - Paysans/Humain - Paysan/PeasantYes1.wav",
             "Warcraft III - Paysans/Humain - Paysan/PeasantWhat2.wav",
             "Warcraft III - Paysans/Orc - Peon/PeonYes1.wav",
             "Counter-Strike Source/radio/fireinhole.wav",
             "Counter-Strike Source/radio/go.wav",
             "Wolfenstein Enemy Territory/chat/allies/medic.wav",
             "Mes sons/Klaxon_géant.mp3"]
    files = [tmp_path / n for n in names]
    return Index(tmp_path, files)


def names(results):
    return [e.name for e in results[0]]


def test_tokens_split_camel_case_and_accents():
    assert tokens("PeasantYes1") == ["peasant", "yes", "1"]
    assert tokens("Klaxon_Géant") == ["klaxon", "geant"]
    assert pretty_name("a-medic_3") == "A Medic 3"


def test_search_ranks_name_matches(tmp_path):
    idx = make_index(tmp_path)
    assert names(idx.search("peasant yes"))[0] == "Peasant Yes 1"
    assert names(idx.search("yes")) == ["Peasant Yes 1", "Peon Yes 1"]
    assert names(idx.search("yes peasant")) == ["Peasant Yes 1"]  # ordre des mots libre


def test_search_folder_category_accents_and_typos(tmp_path):
    idx = make_index(tmp_path)
    assert set(names(idx.search("radio"))) == {"Fireinhole", "Go"}
    assert names(idx.search("geant")) == ["Klaxon Géant"]
    assert names(idx.search("GÉANT")) == ["Klaxon Géant"]
    assert names(idx.search("fire hole")) == ["Fireinhole"]
    assert names(idx.search("peasnat")) == ["Peasant What 2", "Peasant Yes 1"]  # faute de frappe
    assert names(idx.search("zzz")) == []


def test_search_category_filter_and_total(tmp_path):
    idx = make_index(tmp_path)
    res, total = idx.search("", "Counter-Strike Source")
    assert total == 2 and {e.name for e in res} == {"Fireinhole", "Go"}
    res, total = idx.search("", limit=3)
    assert len(res) == 3 and total == 7


def test_disk_search(tmp_path):
    touch_wav(tmp_path / "a" / "explosion_big.wav")
    touch_wav(tmp_path / "a" / "b" / "BigDoor.wav")
    (tmp_path / "a" / "notes.txt").write_text("x")
    touch_wav(tmp_path / "node_modules" / "big.wav")  # dossier ignoré
    s = DiskSearch([str(tmp_path)], "big").start()
    for _ in range(100):
        if s.done:
            break
        time.sleep(0.02)
    r = s.poll(0)
    assert r["done"]
    assert sorted(x["file"] for x in r["results"]) == ["BigDoor.wav", "explosion_big.wav"]
    assert s.poll(2)["results"] == []


def test_mixer_preview_and_progress():
    m = Mixer()
    m.add_sink(SINK_CABLE)
    assert not m.play(np.ones(100), "x", sinks={SINK_MONITOR})  # pas de casque actif
    m.add_sink(SINK_MONITOR)
    assert m.play(np.ones(960), "p", sinks={SINK_MONITOR})
    assert np.allclose(m.cable_block(480), 0)  # la pré-écoute ne part pas dans le micro
    m.monitor_block(480)
    (voice,) = m.active()
    assert voice["tag"] == "p" and voice["preview"] and abs(voice["progress"] - 0.5) < 1e-6


class FakeEngine:
    running = False
    mixer = None

    def start(self, mic, cable, monitor):
        self.mixer.add_sink(SINK_CABLE)
        self.running = True

    def stop(self):
        self.running = False

    def preview_sinks(self, device):
        return {SINK_MONITOR}

    def close_preview(self):
        pass


def make_api(tmp_path, monkeypatch):
    monkeypatch.setattr(apimod.AudioEngine, "host_apis", staticmethod(lambda: ["MME"]))
    monkeypatch.setattr(apimod.AudioEngine, "devices", staticmethod(
        lambda api, kind: [(0, "Micro"), (1, "CABLE Output")] if kind == "input"
        else [(2, "Casque"), (3, "CABLE Input (VB-Audio)")]))
    root = tmp_path / "sons"
    touch_wav(root / "Counter-Strike Source" / "radio" / "go.wav")
    touch_wav(root / "Mes sons" / "klaxon.wav")
    cfg = tmp_path / "cfg.json"
    cfg.write_text('{"sounds_root": "%s"}' % root.as_posix())
    return apimod.Api(engine=FakeEngine(), cfg_file=cfg), root


def test_api_flow(tmp_path, monkeypatch):
    a, root = make_api(tmp_path, monkeypatch)
    cats = {c["name"]: c["count"] for c in a.categories()}
    assert cats["Counter-Strike Source"] == 1 and cats["Warcraft III - Paysans"] == 0
    hit = a.search("go")["results"][0]
    assert hit["name"] == "Go" and hit["category"] == "Counter-Strike Source"

    assert not a.play(hit["path"])["ok"]  # moteur arrêté
    dev = a.devices()
    assert dev["selected"]["cable"] == "CABLE Input (VB-Audio)" and dev["selected"]["mic"] == "Micro"
    assert a.start()["ok"]
    assert a.play(hit["path"])["ok"]
    assert a.status()["playing"][0]["tag"] == hit["path"]

    assert a.toggle_favorite(hit["path"]) is True
    assert [f["name"] for f in a.favorites()] == ["Go"]
    assert a.search("", favorites=True)["total"] == 1

    ext = touch_wav(tmp_path / "ailleurs" / "boom.wav")
    assert a.add_files([str(ext)], "Mes sons")["added"] == 1
    assert a.search("boom")["total"] == 1
    assert (root / "Mes sons" / "boom.wav").exists()

    a.set_mix("sound_volume", 0.3)
    assert a._mixer.sound_gain == 0.3
    a2 = apimod.Api(engine=FakeEngine(), cfg_file=tmp_path / "cfg.json")  # réglages conservés
    assert a2._mixer.sound_gain == 0.3 and a2.favorites()[0]["name"] == "Go"


def test_api_tree_browse_and_folder_import(tmp_path, monkeypatch):
    a, root = make_api(tmp_path, monkeypatch)
    touch_wav(root / "Counter-Strike Source" / "bot" / "clear.wav")
    touch_wav(root / "Counter-Strike Source" / "radio" / "roger.wav")
    a.rescan()

    tree = a.tree()
    assert [n["name"] for n in tree][:3] == ["Wolfenstein Enemy Territory", "Warcraft III - Paysans",
                                             "Counter-Strike Source"]
    css = next(n for n in tree if n["name"] == "Counter-Strike Source")
    assert css["count"] == 3 and [c["rel"] for c in css["children"]] == \
        ["Counter-Strike Source/bot", "Counter-Strike Source/radio"]

    b = a.browse("Counter-Strike Source")
    assert [c["name"] for c in b["crumbs"]] == ["Counter-Strike Source"]
    assert [(f["name"], f["count"]) for f in b["folders"]] == [("bot", 1), ("radio", 2)]
    assert b["files"] == [] and b["count"] == 3
    radio = a.browse("Counter-Strike Source/radio")
    assert [f["name"] for f in radio["files"]] == ["Go", "Roger"]
    assert radio["files"][0]["dir"] == "Counter-Strike Source/radio"

    # la recherche se limite au dossier et à ses sous-dossiers
    assert a.search("", "Counter-Strike Source/radio")["total"] == 2
    assert a.search("clear", "Counter-Strike Source")["total"] == 1
    assert a.search("clear", "Counter-Strike Source/radio")["total"] == 0

    src = tmp_path / "Pack"
    touch_wav(src / "Armes" / "Fusils" / "pan.wav")
    touch_wav(src / "intro.wav")
    r = a.import_folder_path(str(src), "Mes sons")
    assert r == {"added": 2, "rel": "Mes sons/Pack"}
    pack = a.browse("Mes sons/Pack")
    assert [f["name"] for f in pack["folders"]] == ["Armes"] and [f["name"] for f in pack["files"]] == ["Intro"]
    assert a.browse("Mes sons/Pack/Armes/Fusils")["files"][0]["name"] == "Pan"
    assert "error" in a.import_folder_path(str(src), "../..")  # pas de sortie de la bibliothèque


def test_setup_wizard_api(tmp_path, monkeypatch):
    a, _root = make_api(tmp_path, monkeypatch)
    assert a.first_run() is True
    st = a.setup_status()
    assert st["cable_found"] and st["cable_ok"] and st["mic"] == "Micro" and not st["running"]
    assert not a.play_test()["ok"]  # moteur arrêté
    a.start()
    a._mixer.add_sink(SINK_CABLE)
    assert a.play_test()["ok"]
    assert a.status()["playing"][0]["tag"] == "__test__"
    a.setup_done(True)
    assert a.first_run() is False
    assert apimod.Api(engine=FakeEngine(), cfg_file=tmp_path / "cfg.json").first_run() is False


def test_window_frame_outside_windows():
    from micboard.winframe import Frame

    class FakeWindow:
        x, y, width, height = 10, 20, 1280, 820
        calls = []
        def maximize(self): self.calls.append("max")
        def restore(self): self.calls.append("restore")
        def minimize(self): self.calls.append("min")
        def move(self, x, y): self.calls.append(("move", x, y))
        def resize(self, w, h): self.calls.append(("resize", w, h))

    f = Frame()
    w = FakeWindow()
    f.attach(w)
    assert f.toggle_maximize() is True and f.toggle_maximize() is False
    assert f.geometry() == {"x": 10, "y": 20, "w": 1280, "h": 820, "maximized": False}
    f.set_geometry(5, 6, 100, 100)  # taille minimale respectée
    assert w.calls[-2:] == [("move", 5, 6), ("resize", 900, 600)]
