import { checkoutUrl, parseRevenueCatEvent, subscriptionChange, type SubscriptionEvent } from "./subscription-event";

const EXPIRES = new Date("2026-11-15T10:00:00Z");
const event = (type: string, overrides: Partial<SubscriptionEvent> = {}): SubscriptionEvent => ({
  type,
  appUserIds: ["user-1"],
  entitlementIds: ["snapcore_plus"],
  expiresAt: EXPIRES,
  occurredAt: new Date("2026-10-15T10:00:00Z"),
  ...overrides,
});

describe("subscriptionChange (ADR-025)", () => {
  it.each(["INITIAL_PURCHASE", "RENEWAL", "UNCANCELLATION", "PRODUCT_CHANGE", "SUBSCRIPTION_EXTENDED", "REFUND_REVERSED"])(
    "%s donne SnapCore+ jusqu'à l'expiration",
    (type) => {
      expect(subscriptionChange(event(type), "snapcore_plus")).toEqual({ tier: "premium", premiumUntil: EXPIRES });
    },
  );

  it("une annulation garde l'accès jusqu'à la fin de la période payée", () => {
    expect(subscriptionChange(event("CANCELLATION"), "snapcore_plus")).toEqual({ tier: "premium", premiumUntil: EXPIRES });
  });

  it("une expiration repasse le compte en gratuit", () => {
    expect(subscriptionChange(event("EXPIRATION"), "snapcore_plus")).toEqual({ tier: "free", premiumUntil: EXPIRES });
  });

  it("ignore les autres entitlements, les événements sans effet et un achat sans expiration", () => {
    expect(subscriptionChange(event("INITIAL_PURCHASE", { entitlementIds: ["autre"] }), "snapcore_plus")).toBeNull();
    expect(subscriptionChange(event("BILLING_ISSUE"), "snapcore_plus")).toBeNull();
    expect(subscriptionChange(event("TEST"), "snapcore_plus")).toBeNull();
    expect(subscriptionChange(event("RENEWAL", { expiresAt: null }), "snapcore_plus")).toBeNull();
  });
});

describe("parseRevenueCatEvent", () => {
  it("réunit app_user_id, original_app_user_id et aliases sans doublon", () => {
    const parsed = parseRevenueCatEvent({
      type: "RENEWAL",
      app_user_id: "b",
      original_app_user_id: "a",
      aliases: ["a", "b", "c"],
      entitlement_ids: ["snapcore_plus"],
      event_timestamp_ms: 1_000,
      expiration_at_ms: 2_000,
    });
    expect(parsed).toEqual({
      type: "RENEWAL",
      appUserIds: ["b", "a", "c"],
      entitlementIds: ["snapcore_plus"],
      expiresAt: new Date(2_000),
      occurredAt: new Date(1_000),
    });
  });

  it("refuse un événement sans type ni date", () => {
    expect(parseRevenueCatEvent({ app_user_id: "a" })).toBeNull();
    expect(parseRevenueCatEvent({ type: "RENEWAL" })).toBeNull();
  });
});

describe("checkoutUrl", () => {
  it("ajoute l'id du compte encodé, et l'email s'il est connu", () => {
    expect(checkoutUrl("https://pay.rev.cat/tok/", "u 1", "a+b@x.fr")).toBe("https://pay.rev.cat/tok/u%201?email=a%2Bb%40x.fr");
    expect(checkoutUrl("https://pay.rev.cat/tok", "u1", null)).toBe("https://pay.rev.cat/tok/u1");
  });
});
