# ADR-007 — SnapCore+ : RevenueCat + Stripe (mode test) comme moyen de paiement

## Statut
Proposé

## Contexte
SnapCore+ doit proposer un abonnement premium. Le projet ne sera jamais publié sur le Google Play Store, ce qui lève l'obligation d'utiliser Google Play Billing (règle propre à la distribution via le Store). La contrainte du projet est de ne générer aucun frais réel.

## Décision
Utiliser RevenueCat pour orchestrer l'abonnement, avec Stripe (en mode test) comme moyen de paiement, plutôt que Google Play Billing.

## Alternatives
- Google Play Billing + RevenueCat : plus fidèle à ce qu'exigerait une vraie publication Android, sandbox de test gratuit, mais nécessite un compte Google Play Console (25$, paiement unique et à vie).
- Solution maison (simple indicateur `isPremium` sans passerelle de paiement) : gratuite et simple, mais n'illustre aucun vrai flux de paiement, ce qui va à l'encontre de l'objectif pédagogique du projet.

## Conséquences
### Positives
- Aucun frais réel, aucun compte payant nécessaire.
- RevenueCat gère malgré tout le cycle de vie complet de l'abonnement (essai, renouvellement, échec de paiement, remboursement) de façon réaliste.

### Négatives
- Paiement via une page web Stripe (Checkout) plutôt que la boîte de dialogue native Android qu'un vrai utilisateur Play Store verrait — moins fidèle sur ce point précis à une vraie app Android publiée.
- Le choix de basculer vers Google Play Billing reste ouvert et sera retranché plus tard si besoin.

## Liens
- ADR liés : aucun
