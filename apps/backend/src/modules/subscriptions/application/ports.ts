import type { SubscriptionChange } from "../domain/subscription-event";

export const SUBSCRIPTION_REPOSITORY = Symbol("SubscriptionRepository");

export interface SubscriptionRepository {
  /** Premier id de la liste qui correspond à un compte existant (même en cours de suppression). */
  findUserId(candidates: string[]): Promise<string | null>;

  /**
   * Applique le changement seulement si aucun événement plus récent n'a déjà été appliqué au compte
   * (mise à jour conditionnelle unique, ADR-025).
   * @returns false si l'événement était plus ancien que le dernier appliqué.
   */
  applyIfNewer(userId: string, change: SubscriptionChange, occurredAt: Date): Promise<boolean>;
}

export interface SubscriptionConfig {
  entitlementId: string;
  /** Web Purchase Link sandbox, sans identifiant de compte ; null si non configuré. */
  purchaseLink: string | null;
}
