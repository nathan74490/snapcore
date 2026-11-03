import type { Analytics } from "../../../shared/domain/analytics";
import type { SubscriptionEvent } from "../domain/subscription-event";
import type { SubscriptionRepository } from "./ports";
import { SubscriptionUseCases } from "./subscription-use-cases";

const logger = { log: () => undefined, warn: () => undefined };

function setup(applies: boolean) {
  const captured: { userId: string; event: string }[] = [];
  const analytics: Analytics = { capture: (userId, event) => void captured.push({ userId, event }) };
  const repository: SubscriptionRepository = { findUserId: async () => "u1", applyIfNewer: async () => applies };
  const useCases = new SubscriptionUseCases(repository, { entitlementId: "snapcore_plus", purchaseLink: null }, logger, analytics);
  return { useCases, captured };
}

const event = (type: string): SubscriptionEvent => ({
  type,
  appUserIds: ["u1"],
  entitlementIds: ["snapcore_plus"],
  expiresAt: new Date("2026-11-15T10:00:00Z"),
  occurredAt: new Date("2026-10-15T10:00:00Z"),
});

describe("SubscriptionUseCases — analytics (ADR-008)", () => {
  it("un changement appliqué est envoyé sous le nom du cycle de vie de l'abonnement", async () => {
    const { useCases, captured } = setup(true);
    await useCases.handleEvent(event("INITIAL_PURCHASE"));
    await useCases.handleEvent(event("EXPIRATION"));
    expect(captured).toEqual([
      { userId: "u1", event: "snapcoreplus_started" },
      { userId: "u1", event: "snapcoreplus_expired" },
    ]);
  });

  it("un événement périmé ou sans effet n'envoie rien", async () => {
    const { useCases, captured } = setup(false);
    await useCases.handleEvent(event("RENEWAL"));
    await useCases.handleEvent(event("BILLING_ISSUE"));
    expect(captured).toEqual([]);
  });
});
