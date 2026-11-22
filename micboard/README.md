# MicBoard — des sons de jeux dans ton micro (Windows)

MicBoard envoie des sons dans ton micro, mélangés à ta voix, pour Discord, les jeux, OBS…
Il est prévu pour les sons de **Wolfenstein: Enemy Territory**, des **ouvriers de Warcraft III**
(Paysan, Péon, Acolyte, Feu follet) et de **Counter-Strike: Source**, mais il lit n'importe quel
`.wav`, `.ogg`, `.flac` ou `.mp3`.

> Les sons des jeux appartiennent à leurs éditeurs : ils ne sont **pas** fournis avec l'appli.
> MicBoard les copie depuis **tes propres jeux installés** (*Importer depuis mes jeux*).

## Comment ça marche (à lire en premier)

Windows ne laisse pas une appli parler à la place de ton micro. L'astuce, c'est un **câble virtuel** :
un faux micro gratuit (VB-Audio Virtual Cable). MicBoard y envoie ta voix **et** les sons, et Discord
ou ton jeu écoute ce faux micro au lieu du vrai.

```
Ton micro ──► MicBoard (+ les sons) ──► CABLE Input ═══ câble virtuel ═══► CABLE Output ──► Discord / jeu
```

**La seule chose à retenir :** MicBoard envoie dans **CABLE Input**, et Discord ou ton jeu écoute
**CABLE Output**. Les deux noms se ressemblent : c'est l'entrée et la sortie du même câble.

## Démarrage pas à pas

Au premier lancement, MicBoard ouvre un **assistant** qui refait ces étapes avec toi et vérifie
chacune : câble détecté, voix qui arrive, son de test envoyé. On le relance depuis **Aide ›
Relancer l'assistant**.

### 1. Installer le câble virtuel (une seule fois)

1. Va sur <https://vb-audio.com/Cable/> et télécharge **VBCABLE_Driver_Pack** (gratuit).
2. Clic droit sur le `.zip` téléchargé › **Extraire tout…**
3. Dans le dossier extrait, clic droit sur `VBCABLE_Setup_x64.exe` › **Exécuter en tant
   qu'administrateur**, puis **Install Driver**.
4. **Redémarre le PC.**

### 2. Télécharger et lancer MicBoard

1. Sur la page du dépôt GitHub, ouvre **Releases** (colonne de droite), prends la plus récente
   « MicBoard (build …) » et clique sur **MicBoard.exe** dans *Assets*.
2. Lance-le. Si Windows affiche « Windows a protégé votre ordinateur » : clique sur
   **Informations complémentaires**, puis **Exécuter quand même** (MicBoard n'est pas signé
   numériquement, d'où l'avertissement).

### 3. Choisir tes appareils

L'assistant les choisit pour toi ; vérifie-les (ou plus tard dans **Réglages**, en bas à gauche) :
- **Ton vrai micro** : celui dans lequel tu parles. *Pas* « CABLE Output ».
- **Sortie vers le câble** : toujours **CABLE Input**.
- **Ton casque** (facultatif) : pour entendre les sons toi aussi et les pré-écouter.

### 4. Activer et tester

Clique sur **Micro hors ligne** en haut à gauche : il passe à **En direct**. Parle : la barre du
bouton micro (en bas) doit bouger. L'étape *Tester* de l'assistant envoie aussi un son de test.

### 5. Régler Discord ou ton jeu

- **Discord** : Paramètres utilisateur › **Voix et vidéo** › Périphérique d'entrée = **CABLE Output
  (VB-Audio Virtual Cable)**. Désactive la **suppression du bruit** (Krisp), sinon Discord coupe les
  sons. En Push-to-talk, les sons ne passent que pendant que tu appuies sur ta touche.
- **Jeux** (Counter-Strike: Source…) : ils utilisent le micro par défaut de Windows. Panneau Son
  de Windows › onglet **Enregistrement** › clic droit sur **CABLE Output** › *Définir en tant que
  périphérique par défaut* (l'assistant a un bouton qui ouvre ce panneau). MicBoard continue
  d'écouter ton vrai micro.
- **OBS** : Sources › + › *Capture audio d'entrée* › **CABLE Output**, et retire ton micro habituel.

### 6. Ajouter des sons

*Importer depuis mes jeux*, *Importer un dossier* ou *Chercher sur mon PC* (détails plus bas).
Ensuite : un clic sur un son le joue dans le micro, **Échap** arrête tout.

## Dépannage

| Problème | Solution |
|---|---|
| Les autres n'entendent rien | Le bouton en haut à gauche doit afficher **En direct** ; Discord/le jeu doit utiliser **CABLE Output** ; désactive la suppression du bruit de Discord ; en Push-to-talk, appuie sur ta touche. |
| Ils entendent les sons mais pas ma voix | Réglages › **Mon vrai micro** = ton micro ; le bouton micro en bas ne doit pas être barré. |
| Je n'entends pas les sons | Réglages › **Retour casque** = ton casque, et monte le curseur casque. |
| « CABLE Input » n'apparaît pas | Installe VB-Cable puis **redémarre le PC** ; dans l'assistant, « J'ai installé le câble : vérifier ». |
| Son haché, appareil qui refuse de s'ouvrir | Réglages › **Pilote audio** = MME. |
| Raccourcis inactifs en jeu | Si le jeu tourne en administrateur, lance MicBoard en administrateur aussi. |

## Utilisation au quotidien

Le bouton micro du lecteur (en bas) coupe ou rétablit ta voix ; sa barre montre qu'elle passe.
Les curseurs règlent séparément le volume des sons, de ta voix et du casque.

### Naviguer dans les dossiers

La bibliothèque est rangée en dossiers, comme sur le disque. Dans la barre latérale, l'arbre se
déplie avec les petites flèches ; un clic sur un dossier l'ouvre. Dans la page, les sous-dossiers
apparaissent en tuiles au-dessus des sons, et le fil d'Ariane (Bibliothèque › … › dossier) permet
de remonter. **Retour arrière** remonte d'un dossier.

### Noms des sons

Si un fichier audio contient un **titre** dans ses métadonnées (balises ID3 des MP3, Vorbis des OGG/FLAC,
bloc INFO des WAV), c'est ce titre qui s'affiche ; sinon son **commentaire** ; sinon le nom du fichier,
rendu lisible (« PeasantYes1 » devient « Peasant Yes 1 »). Le nom du fichier reste écrit en petit sous le
titre, et la recherche trouve le son par l'un comme par l'autre. Les métadonnées sont lues en arrière-plan
au premier lancement, puis gardées en cache (`%APPDATA%\MicBoard\tags.json`).

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
