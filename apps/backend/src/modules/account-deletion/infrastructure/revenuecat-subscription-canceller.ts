import { Logger } from "@nestjs/common";
import type { SubscriptionCanceller } from "../application/ports";

const API = "https://api.revenuecat.com/v2";

interface RevenueCatSubscription {
  id: string;
  store: string;
  auto_renewal_status: string;
}

interface SubscriptionList {
  items: RevenueCatSubscription[];
  next_page: string | null;
}

export interface RevenueCatCredentials {
  /** Clé secrète API v2 (permission customer_information:subscriptions:read_write). */
  secretApiKey: string;
  projectId: string;
}

/**
 * Étape (a) du job de suppression de compte (ADR-019) : coupe le renouvellement des abonnements
 * SnapCore+ du compte (Web Billing, ADR-025), pour qu'un compte supprimé ne soit plus prélevé.
 * Idempotente : un client inconnu de RevenueCat ou un abonnement déjà annulé ne fait rien, et une
 * erreur réseau ou serveur est levée pour que le job réessaie (BUG-009 d'ADR-023).
 */
export class RevenueCatSubscriptionCanceller implements SubscriptionCanceller {
  private readonly logger = new Logger(RevenueCatSubscriptionCanceller.name);

  constructor(
    private readonly credentials: RevenueCatCredentials | null,
    private readonly http: typeof fetch = fetch,
  ) {}

  async cancelFor(userId: string): Promise<void> {
    if (!this.credentials) {
      this.logger.warn(`RevenueCat non configuré : abonnement de ${userId} non annulé`);
      return;
    }
    const project = encodeURIComponent(this.credentials.projectId);
    // L'App User ID RevenueCat est l'id interne du compte (ADR-025).
    let next: string | null = `/projects/${project}/customers/${encodeURIComponent(userId)}/subscriptions`;
    while (next) {
      const response = await this.request("GET", next);
      if (response.status === 404) return; // jamais abonné : rien à annuler
      const page = (await this.json(response, `liste des abonnements de ${userId}`)) as SubscriptionList;
      for (const subscription of page.items) {
        // Seul un abonnement Web Billing qui doit encore se renouveler peut, et doit, être annulé.
        if (subscription.store !== "rc_billing" || subscription.auto_renewal_status !== "will_renew") continue;
        const cancel = await this.request("POST", `/projects/${project}/subscriptions/${encodeURIComponent(subscription.id)}/actions/cancel`);
        await this.json(cancel, `annulation de ${subscription.id}`);
        this.logger.log(`Abonnement ${subscription.id} de ${userId} annulé`);
      }
      next = page.next_page ? page.next_page.replace(/^.*\/v2/, "") : null;
    }
  }

  private request(method: "GET" | "POST", path: string): Promise<Response> {
    return this.http(`${API}${path}`, {
      method,
      headers: { Authorization: `Bearer ${this.credentials!.secretApiKey}`, "Content-Type": "application/json" },
    });
  }

  private async json(response: Response, what: string): Promise<unknown> {
    if (!response.ok) throw new Error(`RevenueCat : échec (${response.status}) pour ${what}`);
    return response.json();
  }
}
