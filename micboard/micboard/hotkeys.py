"""Raccourcis clavier indépendants de la disposition du clavier (AZERTY, QWERTY, QWERTZ…).

L'interface identifie une touche par sa *position physique* (KeyboardEvent.code), nommée d'après un
clavier QWERTY : sur un clavier AZERTY, la touche A s'appelle « KeyQ ». Si on enregistrait « q », le
module `keyboard` chercherait la touche marquée Q sur le clavier de l'utilisateur, qui n'est pas la
même : le raccourci ne se déclencherait jamais.

On enregistre donc le *scan code* de la touche (jeu 1, celui de Windows), qui ne dépend pas de la
disposition, sous la forme « #30 ». Exemple : « ctrl+#30 » = Ctrl + la touche à gauche de Z en
AZERTY (A), ou à gauche de S en QWERTY (A aussi). Le nom affiché est calculé d'après la disposition
réelle du clavier, sous Windows.
"""

from __future__ import annotations

import sys

# Position physique (KeyboardEvent.code) -> scan code Windows, pour les touches dont le caractère
# change selon la disposition. Les autres (F1, Espace, flèches…) gardent leur nom.
SCAN_CODES = {
    "Backquote": 41, "Digit1": 2, "Digit2": 3, "Digit3": 4, "Digit4": 5, "Digit5": 6, "Digit6": 7,
    "Digit7": 8, "Digit8": 9, "Digit9": 10, "Digit0": 11, "Minus": 12, "Equal": 13,
    "KeyQ": 16, "KeyW": 17, "KeyE": 18, "KeyR": 19, "KeyT": 20, "KeyY": 21, "KeyU": 22, "KeyI": 23,
    "KeyO": 24, "KeyP": 25, "BracketLeft": 26, "BracketRight": 27,
    "KeyA": 30, "KeyS": 31, "KeyD": 32, "KeyF": 33, "KeyG": 34, "KeyH": 35, "KeyJ": 36, "KeyK": 37,
    "KeyL": 38, "Semicolon": 39, "Quote": 40, "Backslash": 43,
    "KeyZ": 44, "KeyX": 45, "KeyC": 46, "KeyV": 47, "KeyB": 48, "KeyN": 49, "KeyM": 50,
    "Comma": 51, "Period": 52, "Slash": 53, "IntlBackslash": 86,
}
# Pavé numérique : son nom (« num 1 ») n'est pas reconnu par le module `keyboard`, on passe donc
# aussi par le scan code. (Sous Windows, les flèches partagent ces codes avec le pavé numérique.)
NUMPAD = {"Numpad0": 82, "Numpad1": 79, "Numpad2": 80, "Numpad3": 81, "Numpad4": 75, "Numpad5": 76,
          "Numpad6": 77, "Numpad7": 71, "Numpad8": 72, "Numpad9": 73, "NumpadAdd": 78,
          "NumpadSubtract": 74, "NumpadMultiply": 55, "NumpadDecimal": 83}
NUMPAD_LABELS = {sc: "Num " + {"Add": "+", "Subtract": "-", "Multiply": "*", "Decimal": ","}.get(code[6:], code[6:])
                 for code, sc in NUMPAD.items()}
DIGIT_ROW = {SCAN_CODES[f"Digit{d}"]: str(d) for d in range(10)}  # « 1 » même si AZERTY donne « & »

# Caractère de chaque scan code sur un clavier QWERTY (repli hors Windows, et anciens raccourcis).
QWERTY = {sc: (code[3:].lower() if code.startswith("Key") else code[5:] if code.startswith("Digit") else
               {"Backquote": "`", "Minus": "-", "Equal": "=", "BracketLeft": "[", "BracketRight": "]",
                "Semicolon": ";", "Quote": "'", "Backslash": "\\", "Comma": ",", "Period": ".",
                "Slash": "/", "IntlBackslash": "<"}[code])
          for code, sc in SCAN_CODES.items()}
LEGACY = {ch: sc for sc, ch in QWERTY.items() if ch.isalnum()}
LEGACY.update({f"num {code[6:]}": sc for code, sc in NUMPAD.items() if code[6:].isdigit()})
SCAN_CODES_ALL = {**SCAN_CODES, **NUMPAD}

MODIFIERS = ("ctrl", "alt", "shift", "windows")
NAMES = {"ctrl": "Ctrl", "alt": "Alt", "shift": "Maj", "windows": "Win", "space": "Espace",
         "up": "↑", "down": "↓", "left": "←", "right": "→", "esc": "Échap", "enter": "Entrée",
         "tab": "Tab", "backspace": "Retour arrière", "delete": "Suppr", "insert": "Inser",
         "home": "Début", "end": "Fin", "page up": "Page préc.", "page down": "Page suiv.",
         "menu": "Menu", "caps lock": "Verr. maj", "print screen": "Impr. écran", "pause": "Pause"}


def normalize(combo: str) -> str:
    """Combinaison venant de l'interface (« ctrl+KeyQ », « f5 ») -> forme enregistrée (« ctrl+#16 »)."""
    out = []
    for token in [t.strip() for t in (combo or "").split("+") if t.strip()]:
        if token in SCAN_CODES_ALL:
            token = f"#{SCAN_CODES_ALL[token]}"
        elif token.startswith("#") and token[1:].isdigit():
            pass
        else:
            token = token.lower()
        if token not in out:
            out.append(token)
    mods = [m for m in MODIFIERS if m in out]
    return "+".join(mods + [t for t in out if t not in MODIFIERS])


def migrate(combo: str) -> str:
    """Anciens raccourcis (avant la version 1.4.1) : les lettres et chiffres seuls avaient été
    enregistrés d'après leur position QWERTY ; on retrouve la touche physique correspondante."""
    tokens = [t for t in (combo or "").split("+") if t]
    return normalize("+".join(f"#{LEGACY[t]}" if t in LEGACY else t for t in tokens))


def for_keyboard(combo: str) -> tuple:
    """Forme acceptée par keyboard.add_hotkey : ("ctrl", 16), les scan codes en entiers.

    Un tuple et surtout pas une liste : add_hotkey range le raccourci dans un dictionnaire dont il est
    la clé, et une liste y est refusée (« unhashable type: 'list' »)."""
    return tuple(int(t[1:]) if t.startswith("#") else t for t in combo.split("+") if t)


def _char_win32(scan_code: int) -> str:
    """Caractère produit par ce scan code avec la disposition de clavier active (Windows)."""
    import ctypes
    u = ctypes.windll.user32
    layout = u.GetKeyboardLayout(0)
    vk = u.MapVirtualKeyExW(scan_code, 1, layout)  # MAPVK_VSC_TO_VK
    ch = u.MapVirtualKeyExW(vk, 2, layout) & 0xFFFF  # MAPVK_VK_TO_CHAR (sans touche morte)
    return chr(ch) if ch else ""


def key_char(scan_code: int) -> str:
    """Caractère de la touche selon la disposition du clavier (Windows) ; « » si inconnu."""
    if sys.platform == "win32":
        try:
            ch = _char_win32(scan_code)
            if ch.strip():
                return ch
        except Exception:
            pass
    return ""


def label(combo: str, captured: str = "") -> str:
    """Nom lisible : « Ctrl + A » (d'après la disposition du clavier), « F5 », « Maj + Num 1 ».

    `captured` est le nom vu par l'interface au moment de la capture : il sert quand Windows ne peut
    pas donner le caractère d'une touche (sinon on retomberait sur la lettre d'un clavier QWERTY)."""
    tokens = [t for t in (combo or "").split("+") if t]
    if captured and any(t.startswith("#") and not key_char(int(t[1:]) if t[1:].isdigit() else 0)
                        and int(t[1:] or 0) not in NUMPAD_LABELS and int(t[1:] or 0) not in DIGIT_ROW
                        for t in tokens):
        return captured
    parts = []
    for t in tokens:
        if t.startswith("#") and t[1:].isdigit():
            sc = int(t[1:])
            parts.append(NUMPAD_LABELS.get(sc) or DIGIT_ROW.get(sc) or
                         (key_char(sc) or QWERTY.get(sc, f"#{sc}")).upper())
        elif t in NAMES:
            parts.append(NAMES[t])
        elif len(t) <= 3 and t[:1] == "f" and t[1:].isdigit():
            parts.append(t.upper())
        elif t.startswith("num "):
            parts.append("Num " + t[4:])
        else:
            parts.append(t.upper() if len(t) == 1 else t.capitalize())
    return " + ".join(parts)
