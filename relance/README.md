# Relance

Appli Android qui :

1. **À la fin de chaque événement de ton agenda**, t'envoie une notification
   « *Réunion X ?* — As-tu fait ce que tu avais à faire ? » avec la description de l'événement et trois boutons :
   - **C'est fait**
   - **Pas fait → tâche** : crée une tâche Google Tasks pour aujourd'hui (titre de l'événement + description),
     qui sera relancée comme les autres ;
   - **Dans 1 h** : la question revient une heure plus tard.
2. **Aux heures que tu choisis** (par défaut 9h et 18h), te relance sur tes tâches Google Tasks :
   - une notification par tâche **en retard** ou **du jour**, avec les boutons *C'est fait*, *Demain*, *Dans 1 semaine* ;
   - un récapitulatif des tâches **à venir** (7 prochains jours par défaut) ;
   - quelques tâches **sans date** (3 à chaque fois, à tour de rôle), pour qu'elles te reviennent en tête.

**Ce qui est fait est coché partout** :
- « C'est fait » sur un événement ajoute **✅ devant son titre dans l'agenda** (seulement cette occurrence
  si l'événement se répète) et **termine dans Google Tasks** la tâche correspondante : celle créée après
  un « Pas fait », et toute tâche ouverte qui porte le même titre (sans tenir compte des majuscules,
  accents et ponctuation).
- Quand tu termines une tâche créée après un « Pas fait » (depuis Relance ou directement dans Google Tasks),
  l'événement d'origine reçoit sa coche ✅.
- Un événement déjà coché ✅ (par l'appli ou à la main) ne déclenche plus de question.
- La coche n'est possible que sur les événements que tu peux modifier : ceux d'un agenda à toi dont tu es
  l'organisateur (ou dont les invités peuvent modifier l'événement). Pour une invitation d'une autre personne,
  la réponse est seulement enregistrée dans l'appli.

**Jamais quand tu es occupé·e** : les questions et les relances n'arrivent qu'**en journée**
(8h – 21h par défaut, réglable) et quand **ton agenda est libre** — aucun événement en cours ni qui
commence dans les 5 minutes (les événements refusés, annulés, « journée entière » ou marqués « disponible »
ne comptent pas). Sinon elles attendent le prochain moment libre : par exemple, après deux réunions
enchaînées, les deux questions arrivent à la fin de la seconde ; une relance de 18h tombée pendant
un rendez-vous arrive quand il se termine ; ce qui tombe le soir arrive le lendemain matin.
Les boutons « Revoir les événements » et « Me relancer maintenant » de l'appli, eux, agissent tout de suite.

L'écran de l'appli montre aussi tes tâches (en retard / aujourd'hui / à venir) avec les mêmes boutons,
l'historique de tes réponses, et les réglages : agendas surveillés, heures de relance, nombre de jours « à venir ».

## Comment ça marche

- **Agenda** : lu directement sur le téléphone (l'agenda Android synchronisé avec Google Agenda),
  sans connexion supplémentaire. Sont ignorés : les événements « journée entière », ceux que tu as refusés,
  les annulés, et par défaut les agendas en lecture seule (jours fériés, anniversaires…). Tu peux cocher/décocher
  les agendas dans l'appli.
- **Google Tasks** : via l'API officielle, avec ton compte Google.
- Les vérifications tournent en arrière-plan **toutes les 15 minutes** : la question peut donc arriver
  jusqu'à ~15 min après la fin de l'événement (Android peut retarder un peu plus quand le téléphone dort).

## Installation

1. Sur ton téléphone, ouvre la page **Releases** du dépôt GitHub et télécharge le dernier `Relance.apk`
   (release `relance-v…`). Ouvre-le pour l'installer (autorise « sources inconnues » si demandé).
2. Ouvre Relance, accepte l'accès à l'agenda et aux notifications.

À ce stade, les questions après les événements fonctionnent déjà.

## Connecter Google Tasks (une seule fois, ~10 min)

Google n'autorise une appli à lire tes tâches que si elle est déclarée dans un projet Google Cloud.
C'est gratuit et ne sert qu'à toi.

1. Va sur <https://console.cloud.google.com/> avec ton compte Google, crée un projet (ex. « Relance »).
2. **API et services → Bibliothèque** : cherche **Google Tasks API** et clique sur **Activer**.
3. **API et services → Écran de consentement OAuth** (« Google Auth Platform ») :
   - type d'utilisateur **Externe**, nom de l'appli « Relance », ton adresse e-mail ;
   - dans **Audience**, clique sur **Publier l'application** (passer « En production »).
     Pas besoin de validation Google pour un usage perso : tu verras juste un écran
     « Google n'a pas validé cette application » → *Paramètres avancés* → *Accéder à Relance*.
     *(Si tu restes en mode « Test », ajoute ton adresse dans « Utilisateurs test », mais Google
     te déconnectera tous les 7 jours.)*
4. **API et services → Identifiants → Créer des identifiants → ID client OAuth** :
   - type d'application : **Android** ;
   - nom du package : `fr.mesapplis.relance`
   - empreinte SHA-1 : `1D:A0:A7:4A:97:E9:4D:FE:7D:02:FC:CA:4E:49:94:52:5A:5C:C8:54`

   (Ces deux valeurs sont aussi affichées dans l'appli, avec un bouton « Copier ».)
5. Dans Relance, touche **Se connecter à Google Tasks** et choisis ton compte.

Pas besoin de copier de clé ou d'identifiant dans l'appli : Google reconnaît l'appli grâce au package et à la SHA-1.

## Développement

- Kotlin, sans bibliothèque d'interface ; WorkManager pour l'arrière-plan, Play Services pour l'autorisation Google.
- `app/relance.keystore` est la clé de signature de l'appli, **versionnée exprès** : elle garde la même SHA-1
  à chaque compilation (nécessaire pour Google Tasks et pour installer les mises à jour par-dessus).
  Ne la change pas, sinon il faudra mettre à jour la SHA-1 dans Google Cloud et réinstaller l'appli.
- Tests : `./gradlew testDebugUnitTest` · APK : `./gradlew assembleRelease`
- L'APK est compilé et publié automatiquement par `.github/workflows/relance-android.yml`
  à chaque modification du dossier `relance/`.
