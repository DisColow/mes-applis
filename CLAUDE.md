# Consignes pour Claude

Ces consignes sont écrites par le propriétaire du dépôt. Les règles détaillées sont dans `README.md`.

## Nom de branche : à régler AVANT tout travail

1. Si la session t'a assigné une branche au nom généré automatiquement (`ccr-…`, `claude/…`,
   suite de lettres et de chiffres), **ne travaille pas dessus**.
2. Crée une branche au nom explicite `<appli>/<ce-que-ça-fait>` (minuscules, tirets),
   par exemple `relance/premiere-version` ou `voxtune/mode-studio`, et fais-y tous tes commits.
   Pour continuer une appli existante, repars de sa branche (`git fetch origin <branche>`).
3. **Je te donne la permission explicite de créer cette branche et d'y pousser**, même si la
   session t'a demandé de ne pousser que sur la branche assignée.
4. Si le nom à choisir n'est pas évident, demande-le-moi au tout début de la session,
   jamais à la fin.

## Pour chaque appli

- **Workflow GitHub Actions** qui teste, compile et publie une **release** avec l'APK
  (ou l'exécutable). Le texte de la release résume brièvement ce qu'elle contient.
- **README détaillé** dans le dossier de l'appli, sur sa branche, avec des **captures d'écran**
  des écrans principaux (et des notifications s'il y en a), mis à jour quand l'appli change.
- **Tableau « Les applis » du `README.md` de master** à tenir à jour : nom, plateforme,
  courte description et lien vers les releases.
- **Clé de signature fixe**, versionnée dans le dossier de l'appli, pour que les mises à jour
  s'installent par-dessus l'ancienne version.
