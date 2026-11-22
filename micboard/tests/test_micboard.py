import io
import wave
import zipfile

import numpy as np
import pytest

from micboard import importers, library
from micboard.audio import SINK_CABLE, SINK_MONITOR, Mixer


def test_sound_is_heard_on_both_sinks_then_removed():
    m = Mixer()
    m.add_sink(SINK_CABLE)
    m.add_sink(SINK_MONITOR)
    m.sound_gain = 1.0
    m.monitor_gain = 1.0
    m.play(np.full(700, 0.5, dtype=np.float32))
    a = m.cable_block(480)
    assert np.allclose(a, 0.5)
    assert m.playing == 1
    assert np.allclose(m.monitor_block(480), 0.5)
    tail = m.cable_block(480)
    assert np.allclose(tail[:220], 0.5) and np.allclose(tail[220:], 0)
    m.monitor_block(480)
    assert m.playing == 0


def test_mic_is_mixed_and_clipped():
    m = Mixer()
    m.add_sink(SINK_CABLE)
    m.sound_gain = 1.0
    m.push_mic(np.full(480, 0.7, dtype=np.float32))
    m.play(np.full(480, 0.7, dtype=np.float32))
    assert np.allclose(m.cable_block(480), 1.0)
    m.mic_enabled = False
    m.push_mic(np.full(480, 0.7, dtype=np.float32))
    assert np.allclose(m.cable_block(480), 0)


def test_mic_latency_is_bounded():
    m = Mixer(max_mic_latency=0.02)  # 960 trames
    for _ in range(10):
        m.push_mic(np.ones(480, dtype=np.float32))
    assert m._mic_frames <= 960


def test_partial_mic_pull():
    m = Mixer()
    m.push_mic(np.arange(10, dtype=np.float32))
    assert list(m.pull_mic(4)) == [0, 1, 2, 3]
    assert list(m.pull_mic(8)) == [4, 5, 6, 7, 8, 9, 0, 0]


def test_exclusive_mode():
    m = Mixer()
    m.add_sink(SINK_CABLE)
    m.exclusive = True
    m.play(np.ones(1000))
    m.play(np.ones(1000))
    assert m.playing == 1


def test_resample_length():
    out = library.resample(np.zeros(22050, dtype=np.float32), 22050, 48000)
    assert len(out) == 48000


def _wav_bytes(rate=22050, n=2205):
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(np.zeros(n, dtype=np.int16).tobytes())
    return buf.getvalue()


def test_load_sound(tmp_path):
    p = tmp_path / "a.wav"
    p.write_bytes(_wav_bytes())
    assert len(library.load_sound(p)) == 4800


def test_import_et(tmp_path):
    etmain = tmp_path / "ET" / "etmain"
    etmain.mkdir(parents=True)
    with zipfile.ZipFile(etmain / "pak0.pk3", "w") as z:
        z.writestr("sound/chat/allies/medic.wav", _wav_bytes())
        z.writestr("sound/weapons/gun.wav", _wav_bytes())
        z.writestr("scripts/x.shader", "x")
    dest = tmp_path / "out"
    assert importers.import_et(tmp_path / "ET", dest) == 1
    assert (dest / "chat" / "allies" / "medic.wav").exists()
    assert importers.import_et(tmp_path / "ET", dest, voices_only=False) == 1  # gun.wav en plus


def test_import_css(tmp_path):
    vpk = pytest.importorskip("vpk")
    src = tmp_path / "src"
    (src / "sound" / "radio").mkdir(parents=True)
    (src / "sound" / "radio" / "go.wav").write_bytes(_wav_bytes())
    (src / "sound" / "weapons").mkdir()
    (src / "sound" / "weapons" / "ak.wav").write_bytes(_wav_bytes())
    out = tmp_path / "cstrike_pak_dir.vpk"
    vpk.new(str(src)).save(str(out))
    dest = tmp_path / "css"
    assert importers.import_css(out, dest) == 1
    assert (dest / "radio" / "go.wav").exists()


def test_import_wc3_keeps_game_tree(tmp_path):
    src = tmp_path / "extrait" / "war3.w3mod" / "units"
    for rel in ["Human/Peasant/PeasantYes1.wav", "Orc/Peon/PeonWhat2.wav",
                "Human/Footman/FootmanYes1.wav", "undead/acolyte/acolytepissed1.flac"]:
        p = src / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(b"x")
    dest = tmp_path / "wc3"
    assert importers.import_wc3(tmp_path / "extrait", dest) == 3
    assert (dest / "Human" / "Peasant" / "PeasantYes1.wav").exists()
    assert (dest / "Orc" / "Peon" / "PeonWhat2.wav").exists()
    assert (dest / "undead" / "acolyte" / "acolytepissed1.flac").exists()
    assert not any("Footman" in str(p) for p in dest.rglob("*"))


def test_import_folder_keeps_tree(tmp_path):
    src = tmp_path / "Bruitages"
    for rel in ["a.wav", "Portes/grince.ogg", "Portes/Lourdes/claque.mp3", "lisez-moi.txt"]:
        p = src / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(b"x")
    (src / "Vide").mkdir()
    lib = tmp_path / "lib" / "Mes sons"
    n, created = importers.import_folder(src, lib)
    assert n == 3 and created == lib / "Bruitages"
    assert sorted(p.relative_to(created).as_posix() for p in created.rglob("*") if p.is_file()) == \
        ["Portes/Lourdes/claque.mp3", "Portes/grince.ogg", "a.wav"]
    assert importers.import_folder(src, lib)[0] == 0  # déjà importé : rien en double
    with pytest.raises(ValueError):
        importers.import_folder(src, src / "Portes")
