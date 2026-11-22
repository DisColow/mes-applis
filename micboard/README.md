# MicBoard — des sons de jeux dans ton micro (Windows)

MicBoard envoie des sons dans ton micro, mélangés à ta voix, pour Discord, les jeux, OBS…
Il est prévu pour les sons de **Wolfenstein: Enemy Territory**, des **ouvriers de Warcraft III**
(Paysan, Péon, Acolyte, Feu follet) et de **Counter-Strike: Source**, mais il lit n'importe quel
`.wav`, `.ogg`, `.flac` ou `.mp3`.

> Les sons des jeux appartiennent à leurs éditeurs : ils ne sont **pas** fournis avec l'appli.
> MicBoard les copie depuis **tes propres jeux installés** (*Importer depuis mes jeux*).

## Installation

1. Installe **VB-Audio Virtual Cable** (gratuit) : <https://vb-audio.com/Cable/>, puis redémarre le PC.
   Il ajoute une sortie « CABLE Input » et un micro « CABLE Output ».
2. Télécharge **MicBoard.exe** depuis les *Releases* du dépôt (compilé automatiquement par
   GitHub Actions) et lance-le.

## Utilisation

1. Ouvre **Réglages** (en bas à gauche) et choisis :
   - **Mon vrai micro** : ta voix passe toujours, mélangée aux sons ;
   - **Sortie vers le câble** : `CABLE Input` ;
   - **Retour casque** : ton casque, pour entendre les sons toi aussi et pour la pré-écoute.
2. Clique sur **Micro hors ligne** (en haut à gauche) : il passe à **En direct**.
3. Dans Discord / le jeu / OBS, choisis **CABLE Output** comme micro.

Le bouton micro du lecteur (en bas) coupe ou rétablit ta voix ; son vumètre montre qu'elle passe.
Les curseurs règlent séparément le volume des sons, de ta voix et du casque.

### Naviguer dans les dossiers

La bibliothèque est rangée en dossiers, comme sur le disque. Dans la barre latérale, l'arbre se
déplie avec les petites flèches ; un clic sur un dossier l'ouvre. Dans la page, les sous-dossiers
apparaissent en tuiles au-dessus des sons, et le fil d'Ariane (Bibliothèque › … › dossier) permet
de remonter. **Retour arrière** remonte d'un dossier.

### Rechercher un son

Tape dans la barre de recherche (touche **/** pour y aller) : les résultats s'affichent pendant la
frappe. La recherche ignore les majuscules et les accents, accepte les mots dans le désordre
(« yes peasant »), comprend les noms collés (« PeasantYes1 ») et pardonne les petites fautes
(« peasnat »). Elle cherche aussi dans les noms de dossiers (« radio », « orc »…).
Elle porte sur le dossier ouvert et ses sous-dossiers (« chercher partout » pour élargir) ; un clic
sur le dossier affiché sous un résultat y emmène.

| Touche | Action |
|---|---|
| **Entrée** | jouer le premier résultat (ou celui sélectionné) dans le micro |
| **Maj + Entrée** | l'écouter dans le casque seulement |
| **↑ ↓ ← →** | se déplacer entre les sons |
| **Échap** | effacer la recherche, puis tout arrêter |

Au survol d'un son : pré-écoute 🎧, favori ⭐ (gros bouton en haut de la bibliothèque),
**raccourci clavier global** ⌨ (marche même en plein jeu ; si le jeu est lancé en administrateur,
lance MicBoard en administrateur aussi) et ouverture du dossier.

### Importer un dossier

**Importer un dossier** (en haut de chaque dossier, ou dans *Importer depuis mes jeux*) copie un dossier
du PC dans le dossier ouvert, **avec tous ses sous-dossiers** : l'arborescence d'origine est gardée telle
quelle et reste navigable. Seuls les fichiers audio sont copiés ; réimporter le même dossier ne crée
pas de doublons. Depuis la racine de la bibliothèque, le dossier importé devient une nouvelle catégorie.

### Chercher des sons sur le PC

**Chercher sur mon PC** fouille ton dossier utilisateur, tes disques ou les dossiers de ton choix à la
recherche de fichiers audio. Écoute-les au casque, coche ceux qui te plaisent et ajoute-les au dossier
choisi de ta bibliothèque, ou importe d'un clic tout le dossier d'un résultat avec ses sous-dossiers.

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
sont gardés, dans l'arborescence du jeu (par exemple `Human/Peasant`, `Orc/Peon`).

Pour Wolfenstein ET et CS:S aussi, les sons gardent les dossiers du jeu (`chat/allies`, `radio`, `bot`…).

Les sons sont copiés dans `Documents\MicBoard\sons\<catégorie>\…` : tu peux aussi y ranger tes fichiers
et dossiers à la main, ils apparaissent au prochain lancement.

## Développement

```bash
pip install -r requirements.txt pytest
python -m pytest -q tests
python main.py            # python main.py --debug pour ouvrir les outils de développement
```

- `micboard/audio.py` : mélangeur (micro + sons, pré-écoute, niveaux) et flux audio (sounddevice / PortAudio) ;
- `micboard/search.py` : moteur de recherche de la bibliothèque et recherche de fichiers sur le disque ;
- `micboard/library.py` : dossiers de sons, chargement et rééchantillonnage à 48 kHz ;
- `micboard/importers.py` : extraction depuis les `.pk3` d'ET, le `.vpk` de CS:S et un dossier WC3 ;
- `micboard/api.py` : fonctions appelées par l'interface ;
- `micboard/ui/` : interface (HTML, CSS, JavaScript), affichée par pywebview avec le moteur d'Edge (WebView2) ;
- `micboard/app.py` : création de la fenêtre.
