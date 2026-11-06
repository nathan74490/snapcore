# Dashboard PostHog « Onboarding — de l'accueil au premier snap »

Suivi du parcours d'arrivée, de l'écran d'accueil du fantôme jusqu'au premier snap (ADR-008, ADR-029, ADR-034). Les événements utilisés sont décrits dans le [plan de marquage](plan-de-marquage.md).

Le dashboard est **généré par un script** : `apps/backend/scripts/posthog-onboarding-dashboard.ts`. Pour le changer, on modifie le script puis on le relance. Une modification faite à la main dans PostHog est écrasée au prochain lancement.

## Le créer ou le mettre à jour
1. Dans PostHog, aller dans *Settings → Personal API keys* et créer une clé `phx_` avec les droits `dashboard:write`, `insight:write` et `project:read`. Ce n'est pas la clé de projet `phc_` de l'app.
2. Lancer :
   ```sh
   POSTHOG_PERSONAL_API_KEY=phx_… npm run posthog:dashboard -w @snapcore/backend
   ```
   - Hôte par défaut : `https://eu.posthog.com` (`POSTHOG_APP_HOST`).
   - Projet par défaut : le projet courant de la clé (`POSTHOG_PROJECT_ID` pour en viser un autre).
   - `-- --dry-run` affiche les requêtes sans rien envoyer.
3. Relancer le script :
   - retrouve le dashboard par son nom ;
   - remplace ses graphiques ;
   - garde son URL.

Les comptes de test sont exclus partout (`filterTestAccounts`). Les graphiques couvrent 30 jours, et la rétention 8 semaines.

## Ce qu'il montre
| Bloc | Graphiques | Question |
|---|---|---|
| Chiffres clés | Nouveaux comptes · Accueil → compte créé · Premiers pas terminés · Durée médiane de l'inscription, chacun comparé à la période précédente | Est-ce que ça va mieux que le mois dernier ? |
| Parcours entier | Entonnoir sur 7 jours : Je m'inscris → Prénom → Code SMS → Compte créé → Premiers pas → Terminés → Premier snap | Où perd-on le plus de monde ? |
| Inscription | Entonnoir des 5 écrans · Temps médian par étape · Échecs par type d'erreur · Retours en arrière · Codes SMS (envois, renvois, remplissage auto) · Origine du pseudo · Pays des numéros | Quel écran freine, et pourquoi ? |
| Premiers pas | Entonnoir écran par écran · Taux de « Passer » · Contacts et notifications acceptés · A/B `onboarding-contacts-copy` · Amis proposés, ajoutés, invitations · Durée médiane · Fin normale ou par la touche retour | Le fantôme fait-il ajouter des amis ? |
| Après | SnapCore+ en fin d'onboarding (offre → paiement → abonné) · Activation à 7 jours (compte → premier ami → premier snap envoyé) · Rétention hebdomadaire des nouveaux comptes | Les nouveaux restent-ils ? |
| Accueil et connexion | S'inscrire ou se connecter · Connexions par méthode · Connexions refusées | Les anciens comptes retrouvent-ils le chemin ? |

## À savoir pour lire les chiffres
- **Avant la connexion, les événements sont anonymes.** C'est le cas de l'accueil, de l'inscription et du code SMS. PostHog les rattache au compte à l'`identify` qui suit l'inscription : l'entonnoir du parcours entier suit donc une même personne.
- **`signup_finished` et `user_registered` ne comptent pas la même chose.**
  - `signup_finished` (app) marque la fin du parcours sur le téléphone. Avec `outcome = existing_account`, le numéro avait déjà un compte.
  - `user_registered` (backend) compte les comptes réellement créés.
- **Les durées sont des médianes**, pour qu'un téléphone resté ouvert une nuit ne fausse pas la moyenne.
- **Les nouveaux événements n'ont pas d'historique.** Ceux de ce marquage n'existent qu'à partir de la version de l'app qui les envoie : les comparaisons à la période précédente se remplissent au bout de 30 jours.
