import { RevenueCatSubscriptionCanceller } from "./revenuecat-subscription-canceller";

// Faux RevenueCat : réponses par « MÉTHODE chemin », et journal des appels.
function fakeRevenueCat(routes: Record<string, { status: number; body?: unknown }>) {
  const calls: string[] = [];
  const http = (async (url: string | URL | Request, init?: RequestInit) => {
    const key = `${init?.method ?? "GET"} ${String(url).replace("https://api.revenuecat.com/v2", "")}`;
    calls.push(key);
    expect((init?.headers as Record<string, string>).Authorization).toBe("Bearer sk_test");
    const route = routes[key] ?? { status: 500 };
    return new Response(route.body === undefined ? null : JSON.stringify(route.body), { status: route.status });
  }) as typeof fetch;
  return { http, calls };
}

const credentials = { secretApiKey: "sk_test", projectId: "proj1" };
const list = (items: object[], next_page: string | null = null) => ({ status: 200, body: { object: "list", items, next_page } });

describe("RevenueCatSubscriptionCanceller (BUG-009)", () => {
  it("annule seulement les abonnements Web Billing qui doivent encore se renouveler", async () => {
    const { http, calls } = fakeRevenueCat({
      "GET /projects/proj1/customers/u1/subscriptions": list([
        { id: "sub_actif", store: "rc_billing", auto_renewal_status: "will_renew" },
        { id: "sub_deja_annule", store: "rc_billing", auto_renewal_status: "will_not_renew" },
        { id: "sub_play", store: "play_store", auto_renewal_status: "will_renew" },
      ]),
      "POST /projects/proj1/subscriptions/sub_actif/actions/cancel": { status: 200, body: { id: "sub_actif" } },
    });

    await new RevenueCatSubscriptionCanceller(credentials, http).cancelFor("u1");

    expect(calls).toEqual(["GET /projects/proj1/customers/u1/subscriptions", "POST /projects/proj1/subscriptions/sub_actif/actions/cancel"]);
  });

  it("suit la pagination", async () => {
    const { http, calls } = fakeRevenueCat({
      "GET /projects/proj1/customers/u1/subscriptions": list([], "/v2/projects/proj1/customers/u1/subscriptions?starting_after=x"),
      "GET /projects/proj1/customers/u1/subscriptions?starting_after=x": list([{ id: "s2", store: "rc_billing", auto_renewal_status: "will_renew" }]),
      "POST /projects/proj1/subscriptions/s2/actions/cancel": { status: 200, body: {} },
    });

    await new RevenueCatSubscriptionCanceller(credentials, http).cancelFor("u1");

    expect(calls).toHaveLength(3);
  });

  it("client inconnu de RevenueCat (jamais abonné) : rien à faire, pas d'erreur", async () => {
    const { http } = fakeRevenueCat({ "GET /projects/proj1/customers/u1/subscriptions": { status: 404 } });
    await expect(new RevenueCatSubscriptionCanceller(credentials, http).cancelFor("u1")).resolves.toBeUndefined();
  });

  it("erreur RevenueCat : levée, pour que le job de suppression réessaie (ADR-019)", async () => {
    const { http } = fakeRevenueCat({ "GET /projects/proj1/customers/u1/subscriptions": { status: 503 } });
    await expect(new RevenueCatSubscriptionCanceller(credentials, http).cancelFor("u1")).rejects.toThrow("503");
  });

  it("non configuré : aucun appel", async () => {
    const { http, calls } = fakeRevenueCat({});
    await new RevenueCatSubscriptionCanceller(null, http).cancelFor("u1");
    expect(calls).toHaveLength(0);
  });
});
