# Relance — Règles de confidentialité

*Dernière mise à jour : 23 novembre 2026*

Relance est une appli Android personnelle, sans serveur, sans publicité et sans statistiques d'usage.

## Données utilisées

- **Agenda du téléphone** (autorisations « lire » et « modifier l'agenda ») : Relance lit les événements
  pour savoir lesquels viennent de se terminer et si un événement est en cours. Quand on répond « C'est fait »,
  elle ajoute la coche ✅ au titre de l'événement. Rien d'autre n'est modifié.
- **Google Tasks** (autorisation `https://www.googleapis.com/auth/tasks`) : Relance lit les tâches non
  terminées pour envoyer des rappels, et, à la demande de l'utilisateur, termine une tâche, change son
  échéance ou crée une tâche (après un événement « pas fait »).
- **Réglages et historique des réponses** : enregistrés uniquement sur le téléphone.

## Ce que Relance ne fait pas

- Aucune donnée n'est envoyée ailleurs qu'aux services Google de l'utilisateur (Google Agenda via le
  téléphone, l'API Google Tasks). Il n'y a aucun serveur Relance.
- Aucune donnée n'est vendue, partagée, ni utilisée à des fins publicitaires ou pour entraîner des modèles.

L'utilisation et le transfert des informations reçues des API Google respectent les
[règles relatives aux données utilisateur des services d'API Google](https://developers.google.com/terms/api-services-user-data-policy),
y compris les exigences d'utilisation limitée (*Limited Use*).

## Supprimer ses données

- Désinstaller l'appli efface ses réglages et son historique.
- L'accès à Google Tasks se retire à tout moment sur <https://myaccount.google.com/permissions>.

## Contact

Via les *issues* du dépôt : <https://github.com/DisColow/mes-applis/issues>
