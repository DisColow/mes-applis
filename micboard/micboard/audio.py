"""Moteur audio : mélange le micro réel et les sons, puis les envoie vers le câble virtuel.

Le chemin du son :
    micro réel ──► InputStream ──► file d'attente ─┐
                                                    ├─► OutputStream « CABLE Input » ─► Discord / jeu
    sons lancés ───────────────────────────────────┘
                 └────────────────────────────────────► OutputStream casque (retour facultatif)

Chaque sortie (« sink ») garde sa propre position de lecture dans chaque son, pour que
le câble et le casque jouent la même chose sans se gêner.
"""

from __future__ import annotations

import collections
import threading

import numpy as np

SAMPLE_RATE = 48000
BLOCK = 480  # 10 ms
SINK_CABLE = "cable"
SINK_MONITOR = "monitor"
SINK_PREVIEW = "preview"  # sortie par défaut, quand le moteur est arrêté


class Voice:
    __slots__ = ("data", "pos", "tag", "preview")

    def __init__(self, data: np.ndarray, tag: str = "", preview: bool = False):
        self.data = data
        self.tag = tag
        self.preview = preview
        self.pos: dict[str, int] = {}


class Mixer:
    """Mélangeur sans dépendance matérielle (testable sans carte son)."""

    def __init__(self, max_mic_latency: float = 0.12, samplerate: int = SAMPLE_RATE):
        self._lock = threading.Lock()
        self._voices: list[Voice] = []
        self._sinks: set[str] = set()
        self._mic = collections.deque()
        self._mic_frames = 0
        self._max_mic_frames = int(max_mic_latency * samplerate)
        self.sound_gain = 0.8
        self.monitor_gain = 0.6
        self.mic_gain = 1.0
        self.mic_enabled = True
        self.exclusive = False  # un seul son à la fois
        self.mic_level = 0.0    # crête du micro, pour le vumètre
        self.out_level = 0.0    # crête envoyée dans le câble

    # --- sorties ---------------------------------------------------------------------
    def add_sink(self, name: str) -> None:
        with self._lock:
            self._sinks.add(name)

    def remove_sink(self, name: str) -> None:
        with self._lock:
            self._sinks.discard(name)
            for v in self._voices:
                v.pos.pop(name, None)
            self._prune()

    # --- sons ------------------------------------------------------------------------
    def play(self, data: np.ndarray, tag: str = "", sinks: set[str] | None = None) -> bool:
        """Lance un son. `sinks` limite les sorties (ex. pré-écoute : casque seulement).
        Renvoie False si aucune sortie active ne peut le jouer."""
        preview = sinks is not None
        voice = Voice(np.asarray(data, dtype=np.float32), tag, preview)
        with self._lock:
            targets = self._sinks if sinks is None else self._sinks & set(sinks)
            if not targets:
                return False
            if self.exclusive and not preview:
                self._voices = [v for v in self._voices if v.preview]
            voice.pos = {s: 0 for s in targets}
            self._voices.append(voice)
        return True

    def stop(self, tag: str) -> None:
        with self._lock:
            self._voices = [v for v in self._voices if v.tag != tag]

    def active(self) -> list[dict]:
        """Sons en cours : [{tag, progress (0..1), duration (s), preview}]."""
        with self._lock:
            out = []
            for v in self._voices:
                n = max(1, len(v.data))
                pos = max(v.pos.values()) if v.pos else 0
                out.append({"tag": v.tag, "progress": min(1.0, pos / n),
                            "duration": n / SAMPLE_RATE, "preview": v.preview})
            return out

    def stop_all(self) -> None:
        with self._lock:
            self._voices.clear()

    @property
    def playing(self) -> int:
        with self._lock:
            return len(self._voices)

    def _prune(self) -> None:
        self._voices = [
            v for v in self._voices
            if any(v.pos.get(s, len(v.data)) < len(v.data) for s in self._sinks)
        ]

    def levels(self) -> tuple[float, float]:
        """Crêtes (micro, sortie) avec retombée progressive à chaque lecture."""
        mic, out = self.mic_level, self.out_level
        self.mic_level *= 0.6
        self.out_level *= 0.6
        return mic, out

    def render_sounds(self, sink: str, frames: int) -> np.ndarray:
        out = np.zeros(frames, dtype=np.float32)
        with self._lock:
            for v in self._voices:
                pos = v.pos.get(sink)
                if pos is None or pos >= len(v.data):
                    continue
                chunk = v.data[pos:pos + frames]
                out[:len(chunk)] += chunk
                v.pos[sink] = pos + len(chunk)
            self._prune()
        return out

    # --- micro -----------------------------------------------------------------------
    def push_mic(self, block: np.ndarray) -> None:
        block = np.asarray(block, dtype=np.float32).reshape(-1)
        if len(block):
            self.mic_level = max(self.mic_level, float(np.abs(block).max()) * self.mic_gain)
        with self._lock:
            self._mic.append(block)
            self._mic_frames += len(block)
            # On jette le plus ancien si le retard s'accumule (horloges légèrement différentes).
            while self._mic_frames > self._max_mic_frames and len(self._mic) > 1:
                self._mic_frames -= len(self._mic.popleft())

    def pull_mic(self, frames: int) -> np.ndarray:
        out = np.zeros(frames, dtype=np.float32)
        filled = 0
        with self._lock:
            while filled < frames and self._mic:
                head = self._mic[0]
                take = min(frames - filled, len(head))
                out[filled:filled + take] = head[:take]
                filled += take
                if take == len(head):
                    self._mic.popleft()
                else:
                    self._mic[0] = head[take:]
                self._mic_frames -= take
        return out

    # --- blocs finaux ------------------------------------------------------------------
    def cable_block(self, frames: int) -> np.ndarray:
        mix = self.render_sounds(SINK_CABLE, frames) * self.sound_gain
        mic = self.pull_mic(frames)
        if self.mic_enabled:
            mix += mic * self.mic_gain
        mix = np.clip(mix, -1.0, 1.0)
        if len(mix):
            self.out_level = max(self.out_level, float(np.abs(mix).max()))
        return mix

    def monitor_block(self, frames: int) -> np.ndarray:
        mix = self.render_sounds(SINK_MONITOR, frames) * (self.sound_gain * self.monitor_gain)
        return np.clip(mix, -1.0, 1.0)

    def preview_block(self, frames: int) -> np.ndarray:
        """Pré-écoute quand le moteur est arrêté : seulement les sons, au volume casque."""
        return np.clip(self.render_sounds(SINK_PREVIEW, frames) * self.monitor_gain, -1.0, 1.0)


def _to_channels(mono: np.ndarray, channels: int) -> np.ndarray:
    return np.repeat(mono[:, None], channels, axis=1)


class AudioEngine:
    """Relie le Mixer aux vrais périphériques via sounddevice (PortAudio)."""

    def __init__(self, mixer: Mixer):
        self.mixer = mixer
        self._streams = []
        self._preview_stream = None
        self.running = False

    @staticmethod
    def refresh_devices() -> None:
        """Relit la liste des périphériques (PortAudio la garde en mémoire depuis le lancement) :
        utile juste après l'installation de VB-Cable. À n'appeler que moteur arrêté."""
        import sounddevice as sd
        sd._terminate()
        sd._initialize()

    @staticmethod
    def host_apis() -> list[str]:
        import sounddevice as sd
        return [api["name"] for api in sd.query_hostapis()]

    @staticmethod
    def devices(host_api: str, kind: str) -> list[tuple[int, str]]:
        """kind = 'input' ou 'output'. Renvoie [(index, nom)]."""
        import sounddevice as sd
        apis = sd.query_hostapis()
        result = []
        for idx, dev in enumerate(sd.query_devices()):
            if apis[dev["hostapi"]]["name"] != host_api:
                continue
            if dev[f"max_{kind}_channels"] > 0:
                result.append((idx, dev["name"]))
        return result

    def start(self, mic: int | None, cable: int, monitor: int | None) -> None:
        import sounddevice as sd
        self.stop()
        self.close_preview()
        m = self.mixer

        def out_cb(sink, block_fn):
            def cb(outdata, frames, _time, _status):
                outdata[:] = _to_channels(block_fn(frames), outdata.shape[1])
            return cb

        def channels_for(dev):
            return max(1, min(2, sd.query_devices(dev)["max_output_channels"]))

        try:
            m.add_sink(SINK_CABLE)
            self._streams.append(sd.OutputStream(
                device=cable, samplerate=SAMPLE_RATE, blocksize=BLOCK, channels=channels_for(cable),
                dtype="float32", latency="low", callback=out_cb(SINK_CABLE, m.cable_block)))
            if monitor is not None:
                m.add_sink(SINK_MONITOR)
                self._streams.append(sd.OutputStream(
                    device=monitor, samplerate=SAMPLE_RATE, blocksize=BLOCK,
                    channels=channels_for(monitor), dtype="float32", latency="low",
                    callback=out_cb(SINK_MONITOR, m.monitor_block)))
            if mic is not None:
                def in_cb(indata, _frames, _time, _status):
                    m.push_mic(indata[:, 0].copy())
                self._streams.append(sd.InputStream(
                    device=mic, samplerate=SAMPLE_RATE, blocksize=BLOCK, channels=1,
                    dtype="float32", latency="low", callback=in_cb))
            for s in self._streams:
                s.start()
            self.running = True
        except Exception:
            self.stop()
            raise

    def stop(self) -> None:
        for s in self._streams:
            try:
                s.stop()
                s.close()
            except Exception:
                pass
        self._streams.clear()
        self.mixer.remove_sink(SINK_CABLE)
        self.mixer.remove_sink(SINK_MONITOR)
        self.running = False

    # --- pré-écoute ----------------------------------------------------------------------
    def preview_sinks(self, device: int | None) -> set[str]:
        """Sortie à utiliser pour écouter un son sans l'envoyer dans le micro."""
        if self.running:
            return {SINK_MONITOR}  # sans retour casque, play() renvoie False
        if self._preview_stream is None:
            import sounddevice as sd
            m = self.mixer
            dev = device if device is not None else sd.default.device[1]
            ch = max(1, min(2, sd.query_devices(dev)["max_output_channels"]))

            def cb(outdata, frames, _time, _status):
                outdata[:] = _to_channels(m.preview_block(frames), outdata.shape[1])
            m.add_sink(SINK_PREVIEW)
            self._preview_stream = sd.OutputStream(
                device=dev, samplerate=SAMPLE_RATE, blocksize=BLOCK, channels=ch,
                dtype="float32", callback=cb)
            self._preview_stream.start()
        return {SINK_PREVIEW}

    def close_preview(self) -> None:
        if self._preview_stream is not None:
            try:
                self._preview_stream.stop()
                self._preview_stream.close()
            except Exception:
                pass
            self._preview_stream = None
        self.mixer.remove_sink(SINK_PREVIEW)
