# Mes applis

Les applis de ce dépôt, une par dossier (et une branche par appli).

## Les applis

| Appli | Plateforme | Description | Télécharger |
|---|---|---|---|
| **Relance** | Android | Après chaque événement de l'agenda, demande si c'est fait (et coche l'événement) ; relance sur les tâches Google Tasks en retard ou à venir, uniquement en journée quand l'agenda est libre. | [Relance.apk](https://github.com/DisColow/mes-applis/releases?q=relance&expanded=true) |
| **VoxTune** | Android | Auto-tune en temps réel : on chante dans le micro et on entend sa voix corrigée dans le casque, avec un mode Studio pour enregistrer puis réécouter corrigé. | [VoxTune.apk](https://github.com/DisColow/mes-applis/releases?q=voxtune&expanded=true) |
| **MicBoard** | Windows | Envoie des sons (jeux, fichiers audio) dans le micro, mélangés à la voix, pour Discord, les jeux ou OBS. | [MicBoard.exe](https://github.com/DisColow/mes-applis/releases?q=micboard&expanded=true) |

Chaque lien ouvre les releases de l'appli : la plus récente est en haut, le fichier est dans « Assets ».
Les explications détaillées de chaque appli sont dans le README de son dossier, sur sa branche.

## Règle pour les branches

**Chaque branche doit porter un nom explicite** : en lisant son nom, on doit comprendre
de quelle appli elle parle et ce qu'elle change, sans avoir à l'ouvrir.

Forme conseillée : `<appli>/<ce-que-ça-fait>`, en minuscules, avec des tirets.

| ✅ Explicite | ❌ Pas explicite |
|---|---|
| `micboard/premiere-version` | `test` |
| `voxtune/mode-studio` | `claude/zealous-galileo-05acwz` |
| `voxtune/corrige-compilation-apk` | `ccr-5bace72c-s0n1bl` |

Les noms générés automatiquement (suite de lettres et de chiffres, noms aléatoires…) sont à
renommer avant de travailler dessus.

## Règle pour les applis Android : une release avec l'APK

**Chaque appli Android doit avoir son workflow GitHub Actions** (`.github/workflows/<appli>-android.yml`)
qui, à chaque modification du dossier de l'appli :

1. lance les tests et compile l'APK ;
2. **publie une release GitHub** contenant l'APK (`<Appli>.apk`), pour pouvoir l'installer
   directement depuis le téléphone (page *Releases* du dépôt).

**La release doit indiquer brièvement ce qu'elle contient** : quelques lignes sur les nouveautés
ou corrections de cette version (par exemple le message du dernier commit), en plus de la version
et de la branche d'où elle a été compilée.

Pour que les mises à jour s'installent par-dessus l'ancienne version, l'APK doit toujours être
signé avec la même clé (clé de signature versionnée dans le dossier de l'appli).

## Règle pour ce README : tenir la liste des applis à jour

**Ce README doit être mis à jour régulièrement** pour recenser toutes les applis du dépôt :
à chaque nouvelle appli (ou quand une appli change de nom, de rôle ou de plateforme),
ajouter ou corriger sa ligne dans le tableau « Les applis » ci-dessus, avec :

- son **nom** et sa **plateforme** ;
- une **courte description** (une ou deux phrases : ce qu'elle fait, pour qui) ;
- un **lien de téléchargement** vers ses releases
  (`https://github.com/DisColow/mes-applis/releases?q=<appli>&expanded=true`).

## Règle pour chaque appli : un README détaillé, avec captures d'écran

**Chaque branche doit contenir un README détaillé de son appli** (`<appli>/README.md`), qui explique :

- à quoi sert l'appli et ce qu'elle fait, fonction par fonction ;
- comment l'installer et la configurer, étape par étape (autorisations, comptes, réglages…) ;
- comment l'utiliser au quotidien, et ses limites connues ;
- comment la compiler et la tester (pour le développement).

Le README doit comporter des **captures d'écran** des écrans principaux (et des notifications, s'il y en a),
rangées dans le dossier de l'appli (par exemple `<appli>/docs/`) et affichées dans le README.
Il est mis à jour en même temps que l'appli, dès qu'une fonction change.
