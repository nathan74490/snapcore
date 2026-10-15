import type { Logger } from "@nestjs/common";
import { ConflictError } from "../../../shared/domain/errors";
import type { UserAccount } from "../../identity/domain/user";
import { checkoutUrl, subscriptionChange, type SubscriptionEvent } from "../domain/subscription-event";
import type { SubscriptionConfig, SubscriptionRepository } from "./ports";

export type WebhookOutcome = "applied" | "ignored" | "stale" | "unknown-user";

// SnapCore+ (ADR-025) : paiement par Web Purchase Link RevenueCat, état de l'abonnement reçu par webhook.
export class SubscriptionUseCases {
  constructor(
    private readonly subscriptions: SubscriptionRepository,
    private readonly config: SubscriptionConfig,
    private readonly logger: Pick<Logger, "log" | "warn">,
  ) {}

  /** Lien de paiement hébergé pour ce compte. */
  checkout(me: UserAccount): { url: string } {
    if (!this.config.purchaseLink) throw new ConflictError("SnapCore+ n'est pas encore disponible");
    return { url: checkoutUrl(this.config.purchaseLink, me.id, me.email) };
  }

  /**
   * Un événement sans effet ou visant un compte inconnu est acquitté sans erreur : RevenueCat ne le
   * renverra pas indéfiniment. Seule une erreur technique (base indisponible) le fait réessayer.
   */
  async handleEvent(event: SubscriptionEvent): Promise<WebhookOutcome> {
    const change = subscriptionChange(event, this.config.entitlementId);
    if (!change) return "ignored";

    const userId = await this.subscriptions.findUserId(event.appUserIds);
    if (!userId) {
      this.logger.warn(`Événement RevenueCat ${event.type} pour un compte inconnu, ignoré`);
      return "unknown-user";
    }

    const applied = await this.subscriptions.applyIfNewer(userId, change, event.occurredAt);
    if (!applied) return "stale";
    this.logger.log(`SnapCore+ ${change.tier} jusqu'au ${change.premiumUntil?.toISOString() ?? "—"} pour ${userId} (${event.type})`);
    return "applied";
  }
}
