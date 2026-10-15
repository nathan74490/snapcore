import { Controller, Get, Header } from "@nestjs/common";
import { Public } from "../../identity/interface/http/auth.decorators";

// Conditions de SnapCore+, liées depuis la page de paiement RevenueCat, qui exige une URL publique
// (servie par le tunnel ngrok en développement, ADR-025).
const TERMS_HTML = `<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Conditions SnapCore+</title>
<style>
  body { font-family: system-ui, sans-serif; max-width: 42rem; margin: 2rem auto; padding: 0 1rem; line-height: 1.6; color: #1a1a1a; }
  h1 { color: #6b2fa0; }
</style>
</head>
<body>
<h1>Conditions d'utilisation de SnapCore+</h1>
<p><strong>SnapCore est un projet de formation, jamais publié.</strong> L'abonnement SnapCore+ fonctionne
exclusivement en mode test : aucun paiement réel n'est jamais débité, seules les cartes de test Stripe sont acceptées.</p>
<h2>Offre</h2>
<p>SnapCore+ est un abonnement mensuel de 3,99 €, renouvelé automatiquement chaque mois, sans engagement.
Il permet de revoir les snaps reçus autant de fois que souhaité et de les conserver tant que l'abonnement est actif.</p>
<h2>Résiliation</h2>
<p>L'abonnement peut être résilié à tout moment depuis le lien de gestion reçu par email après le paiement.
L'accès reste ouvert jusqu'à la fin de la période déjà payée.</p>
<h2>Données</h2>
<p>Le paiement est traité par RevenueCat et Stripe ; SnapCore ne reçoit ni ne conserve aucune donnée de carte.
SnapCore enregistre uniquement l'état de l'abonnement (actif ou non, date de fin) sur le compte.</p>
</body>
</html>`;

@Controller("legal")
export class LegalController {
  @Public()
  @Get("cgu")
  @Header("Content-Type", "text/html; charset=utf-8")
  terms(): string {
    return TERMS_HTML;
  }
}
