# ADR-025 : Paywall SnapCore+ : Web Purchase Link RevenueCat, webhook vers le backend

## Statut
Accepté

## Contexte
ADR-007 a retenu RevenueCat + Stripe (mode test) pour l'abonnement SnapCore+, plutôt que Google Play Billing. Les avantages d'un abonnement existent déjà côté backend : un Snap reçu peut être revu et son média conservé tant que le destinataire est abonné, et l'expéditeur en est notifié (ADR-009, ADR-017). Mais rien ne permet de s'abonner : `User.subscriptionTier` et `User.premiumUntil` ne sont alimentés nulle part.

Il faut décider comment l'utilisateur paie depuis l'app Android, et comment le backend apprend qu'un abonnement a commencé, a été renouvelé ou a pris fin.

## Décision
**Offre** : un abonnement mensuel unique à 3,99 €, en mode test (cartes de test Stripe, aucun vrai paiement). Côté RevenueCat, il donne l'entitlement `snapcore_plus` (identifiant configurable, `REVENUECAT_ENTITLEMENT_ID`).

**Paiement : Web Purchase Link RevenueCat** (RevenueCat Web Billing, Stripe en mode test), c'est-à-dire une page de paiement hébergée par RevenueCat, ouverte dans le navigateur du téléphone.
- L'app ne connaît pas le lien : elle demande au backend `POST /subscription/checkout`, qui renvoie le lien sandbox (`REVENUECAT_PURCHASE_LINK`) suivi de l'identifiant **interne** de l'utilisateur (`User.id`) comme App User ID RevenueCat, et de son email. L'achat est ainsi rattaché au bon compte sans SDK RevenueCat dans l'app.
- RevenueCat exige sur la page de paiement un lien vers des conditions d'utilisation à une URL publique réelle : le backend sert une page statique `GET /legal/cgu` (publique), exposée par le même tunnel que le webhook.
- Après le paiement, RevenueCat redirige vers `snapcore://snapcoreplus`, lien profond qui rouvre l'app sur le paywall ; l'app relit alors son compte (`GET /me`) jusqu'à voir l'abonnement actif.

**Synchronisation : webhook RevenueCat → backend** (`POST /webhooks/revenuecat`).
- Protégé par un secret partagé : RevenueCat envoie la valeur configurée dans son tableau de bord dans l'en-tête `Authorization` ; le backend la compare à `REVENUECAT_WEBHOOK_SECRET` à temps constant, comme l'API admin (ADR-021). Sans secret configuré, le webhook est fermé.
- Le backend étant local, il est exposé à RevenueCat par un **tunnel ngrok** (domaine statique gratuit) pendant le développement.
- Effet de chaque événement sur le compte (seuls ceux qui portent l'entitlement SnapCore+ comptent) :
  - `INITIAL_PURCHASE`, `RENEWAL`, `UNCANCELLATION`, `PRODUCT_CHANGE`, `SUBSCRIPTION_EXTENDED`, `REFUND_REVERSED`, `CANCELLATION` : `subscriptionTier = premium`, `premiumUntil = expiration_at_ms`. Une annulation (`CANCELLATION`) coupe seulement le renouvellement : l'accès dure jusqu'à la fin de la période payée ; en cas de remboursement, l'expiration envoyée est déjà passée.
  - `EXPIRATION` : `subscriptionTier = free`, `premiumUntil = expiration_at_ms`.
  - Les autres événements (`TEST`, `BILLING_ISSUE`, `SUBSCRIPTION_PAUSED`…) sont acquittés sans effet.
- **Ordre des événements** : un webhook peut arriver en retard ou être rejoué. Le backend mémorise la date du dernier événement appliqué (`User.subscriptionEventAt`) et ignore tout événement plus ancien, par une mise à jour conditionnelle unique (pas de lecture puis écriture).
- Le compte est retrouvé parmi `app_user_id`, `original_app_user_id` et `aliases` ; un événement pour un compte inconnu (supprimé entre-temps) est acquitté sans effet, pour que RevenueCat ne le renvoie pas indéfiniment.
- Le backend répond 200 dès que l'événement est traité ; une erreur (base indisponible) renvoie 500, et RevenueCat réessaie.

**Paywall dans l'app** :
- accessible depuis le profil (carte SnapCore+ : l'offre pour un compte gratuit, la date de fin pour un abonné) ;
- proposé quand on touche un Snap déjà vu, au lieu du simple message « déjà vu » : c'est exactement ce que SnapCore+ débloque.

Les règles d'accès existantes ne changent pas : revoir un Snap dépend de `premiumUntil` sans marge (ADR-009), la purge garde sa marge de 24 h (ADR-017).

## Alternatives
- **Synchronisation par interrogation de l'API RevenueCat** (le backend demande l'état de l'abonnement au retour du paiement et avant chaque purge) : aucun tunnel à exposer, mais une dépendance réseau ajoutée dans le job de purge et un état en base qui peut vieillir entre deux interrogations. Écarté au profit du webhook, plus proche d'un déploiement réel.
- **SDK RevenueCat dans l'app** : nécessaire pour Google Play Billing, inutile pour un paiement web ; il ajouterait une seconde source de vérité côté client. Écarté.
- **Stripe Checkout appelé directement par le backend** (version initiale d'ADR-007) : demande de créer les sessions Stripe et de relayer les reçus à RevenueCat soi-même ; le Web Purchase Link fait les deux. Écarté.
- **Mensuel + annuel** : plus fidèle à Snapchat+, mais un seul prix suffit à illustrer le flux. Repoussé.

## Conséquences
### Positives
- SnapCore+ devient réellement souscriptible, et toute la logique d'avantages déjà en place (revoir un Snap, conservation, notification de l'expéditeur) s'active sans autre changement.
- Aucun SDK de paiement ni secret de paiement dans l'app : elle ne reçoit qu'une URL.
- Le cycle de vie complet (renouvellement, annulation, remboursement, expiration) arrive par le webhook, sans job planifié supplémentaire.

### Négatives
- **Tunnel public obligatoire en développement** : sans ngrok lancé, les événements RevenueCat n'arrivent pas (RevenueCat réessaie pendant un temps limité). Le tunnel expose tout le backend, pas seulement le webhook ; les autres routes restent protégées (token Firebase, secret admin).
- Le paiement se fait dans le navigateur, pas dans une boîte de dialogue native (déjà assumé par ADR-007).
- Délai possible entre le paiement et l'activation (livraison du webhook) : l'app relit son compte plusieurs secondes après le retour, et le badge peut apparaître avec un léger retard.
- La suppression de compte n'annule pas encore l'abonnement (`NoopSubscriptionCanceller`, ADR-019) : consigné dans le registre des anomalies (ADR-023, BUG-009).

## Liens
- ADR liés : ADR-007, ADR-009, ADR-017, ADR-019, ADR-021, ADR-023
