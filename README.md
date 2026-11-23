# Mes applis

Les applis de ce dépôt, une par dossier.

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
