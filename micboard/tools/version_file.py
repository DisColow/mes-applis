"""Écrit la fiche « Propriétés › Détails » de MicBoard.exe pour PyInstaller (--version-file).

    python tools/version_file.py build/version_info.txt [numéro de compilation]

Un exe avec nom, description, éditeur et version renseignés est moins souvent pris pour un logiciel
malveillant par les antivirus qu'un exe anonyme.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from micboard import __version__  # noqa: E402

TEMPLATE = """# Généré par tools/version_file.py
VSVersionInfo(
  ffi=FixedFileInfo(filevers={vt}, prodvers={vt}, mask=0x3f, flags=0x0, OS=0x40004,
                    fileType=0x1, subtype=0x0, date=(0, 0)),
  kids=[
    StringFileInfo([StringTable('040C04B0', [
      StringStruct('CompanyName', 'MicBoard'),
      StringStruct('FileDescription', 'MicBoard - des sons dans ton micro'),
      StringStruct('FileVersion', '{vs}'),
      StringStruct('InternalName', 'MicBoard'),
      StringStruct('LegalCopyright', 'MicBoard'),
      StringStruct('OriginalFilename', 'MicBoard.exe'),
      StringStruct('ProductName', 'MicBoard'),
      StringStruct('ProductVersion', '{vs}')])]),
    VarFileInfo([VarStruct('Translation', [0x040C, 1200])])
  ]
)
"""


def main() -> None:
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "version_info.txt")
    build = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    parts = [int(p) for p in __version__.split(".")][:3] + [build]
    vt = "(" + ", ".join(str(p) for p in parts) + ")"
    vs = ".".join(str(p) for p in parts)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(TEMPLATE.format(vt=vt, vs=vs), encoding="utf-8")
    print(f"{out} : version {vs}")


if __name__ == "__main__":
    main()
