import { Logger } from "@nestjs/common";
import type { OpsAlerter, SubscriptionCanceller } from "../application/ports";

// SnapCore+ (RevenueCat + Stripe, ADR-007) n'est pas encore implémenté : aucun abonnement réel
// à annuler. À remplacer par l'adaptateur RevenueCat quand le paiement existera.
export class NoopSubscriptionCanceller implements SubscriptionCanceller {
  private readonly logger = new Logger("SubscriptionCanceller");

  async cancelFor(userId: string): Promise<void> {
    this.logger.log(`Aucun abonnement à annuler pour ${userId} (SnapCore+ non implémenté)`);
  }
}

// Alerte dans les logs en niveau ERROR ; à brancher sur un vrai canal (email, Slack…) en production.
export class LoggingOpsAlerter implements OpsAlerter {
  private readonly logger = new Logger("OpsAlert");

  alert(message: string): void {
    this.logger.error(`ALERTE : ${message}`);
  }
}
