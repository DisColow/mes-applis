# MicBoard — des sons de jeux dans ton micro (Windows)

MicBoard envoie des sons dans ton micro, mélangés à ta voix, pour Discord, les jeux, OBS…
Il est prévu pour les sons de **Wolfenstein: Enemy Territory**, des **ouvriers de Warcraft III**
(Paysan, Péon, Acolyte, Feu follet) et de **Counter-Strike: Source**, mais il lit n'importe quel
`.wav`, `.ogg`, `.flac` ou `.mp3`.

> Les sons des jeux appartiennent à leurs éditeurs : ils ne sont **pas** fournis avec l'appli.
> MicBoard les copie depuis **tes propres jeux installés** (menu *Importer*).

## Installation

1. Installe **VB-Audio Virtual Cable** (gratuit) : <https://vb-audio.com/Cable/>, puis redémarre le PC.
   Il ajoute une sortie « CABLE Input » et un micro « CABLE Output ».
2. Télécharge **MicBoard.exe** depuis les *Releases* du dépôt (compilé automatiquement par
   GitHub Actions) et lance-le.

## Utilisation

1. Dans MicBoard, choisis :
   - **Micro réel** : ton vrai micro (ta voix passe toujours, mélangée aux sons) ;
   - **Sortie vers le câble** : `CABLE Input` ;
   - **Retour casque** : ton casque, si tu veux entendre les sons toi aussi.
2. Clique sur **▶ Démarrer**.
3. Dans Discord / le jeu / OBS, choisis **CABLE Output** comme micro.
4. Double-clic sur un son (ou Entrée) pour le jouer, **Échap** pour tout arrêter.

Clic droit sur un son : **favori** (gros bouton à droite) ou **raccourci clavier global**, qui marche
même en plein jeu. Si le jeu est lancé en administrateur, lance MicBoard en administrateur aussi.
Si un périphérique refuse de s'ouvrir, change le **Pilote audio** (MME marche presque partout).

## Importer les sons

| Jeu | Menu | Ce qu'il faut choisir |
|---|---|---|
| Wolfenstein: Enemy Territory (ou ET: Legacy) | *Importer › Wolfenstein* | le dossier du jeu (ou son `etmain`) |
| Counter-Strike: Source | *Importer › Counter-Strike* | `Steam\steamapps\common\Counter-Strike Source\cstrike\cstrike_pak_dir.vpk` |
| Warcraft III | *Importer › Warcraft III* | un dossier extrait (voir ci-dessous) |

Pour ET et CS:S, MicBoard propose d'importer seulement les voix (chat vocal d'ET, radio et bots de CS:S)
ou tous les sons du jeu.

**Warcraft III** range ses sons dans une archive qu'il faut d'abord extraire :
- *Reforged* : ouvre le dossier du jeu avec [CascView](http://www.zezula.net/en/casc/main.html) et
  extrais le dossier `units` ;
- *version classique (1.26 ou avant)* : ouvre `War3.mpq` avec
  [MPQ Editor](http://www.zezula.net/en/mpq/download.html) et extrais `Units`.

Puis choisis ce dossier dans MicBoard : seuls les sons du Paysan, du Péon, de l'Acolyte et du Feu follet
sont gardés, rangés par race.

Les sons sont copiés dans `Documents\MicBoard\sons\<catégorie>` : tu peux aussi y déposer tes fichiers à
la main puis cliquer sur ⟳.

## Développement

```bash
pip install -r requirements.txt pytest
python -m pytest -q tests
python main.py
```

- `micboard/audio.py` : mélangeur (micro + sons) et flux audio (sounddevice / PortAudio) ;
- `micboard/library.py` : dossiers de sons, chargement et rééchantillonnage à 48 kHz ;
- `micboard/importers.py` : extraction depuis les `.pk3` d'ET, le `.vpk` de CS:S et un dossier WC3 ;
- `micboard/app.py` : interface tkinter.
