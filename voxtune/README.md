# VoxTune — auto-tune temps réel pour Android

Chantez dans le micro, entendez votre voix corrigée en direct dans le casque.

## Fonctions
- **Correction de hauteur en temps réel** (détection YIN + transposition à faible latence, ~15 ms de traitement)
- **Tonalité et gamme** : 12 tonalités × 8 gammes (majeure, mineures, pentatoniques, blues, dorien, chromatique)
- **Vitesse de correction** : de « Instantanée » (effet robot à la T-Pain) à 200 ms (naturel)
- **Intensité**, **transposition** (±12 demi-tons), gain micro, volume
- **Préréglages** : Robot, Pop, Naturel
- **Graphe en direct** : votre voix (gris) et la voix corrigée (rose) sur la grille de la gamme
- **Deux modes** :
  - 🎙 **Direct** : on entend sa voix corrigée en temps réel dans le casque (+ enregistrement WAV)
  - 🎧 **Studio** : on enregistre d'abord sa voix brute (pas de casque nécessaire), puis on la réécoute
    corrigée. Les réglages (tonalité, vitesse, intensité…) changent l'effet **pendant l'écoute**,
    on peut comparer avec l'original, se déplacer dans la prise, écouter en boucle, puis exporter
    la version corrigée (prise de 3 min max)
- Les fichiers sont sauvegardés en WAV dans `Musique/VoxTune`

## Installation
1. Téléchargez `VoxTune.apk` (onglet **Releases** du dépôt, ou artefact de l'action GitHub « VoxTune APK »).
2. Ouvrez-le sur le téléphone et autorisez l'installation d'applications inconnues.
3. Android 10 minimum.

> 🎧 Utilisez un **casque filaire** : sans casque, le haut-parleur provoque un larsen ; en Bluetooth le retard est gênant.

## Compiler soi-même
```
cd voxtune
./gradlew assembleRelease   # → app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest # tests du moteur audio
```

## Structure
- `dsp/PitchDetector.kt` — détection de la fondamentale (YIN)
- `dsp/PitchShifter.kt` — transposition par ligne à retard à deux têtes, synchronisée sur la période de la voix
- `dsp/Music.kt` — notes, gammes, quantification avec hystérésis
- `dsp/Take.kt` — prise en mémoire et rendu hors-ligne (mode Studio)
- `dsp/AutoTuneProcessor.kt` — chaîne complète (indépendante d'Android, testée en JVM)
- `AudioEngine.kt` — boucle micro → traitement → casque (AudioRecord/AudioTrack basse latence)
- `MainActivity.kt`, `PitchGraphView.kt` — interface
