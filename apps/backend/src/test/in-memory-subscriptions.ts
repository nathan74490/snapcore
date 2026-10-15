import type { SubscriptionRepository } from "../modules/subscriptions/application/ports";
import type { SubscriptionChange } from "../modules/subscriptions/domain/subscription-event";
import type { InMemoryUserRepository } from "./in-memory-user.repository";

// Même règle d'ordre que la mise à jour conditionnelle Prisma (ADR-025).
export class InMemorySubscriptionRepository implements SubscriptionRepository {
  readonly lastEventAt = new Map<string, Date>();

  constructor(private readonly users: InMemoryUserRepository) {}

  async findUserId(candidates: string[]): Promise<string | null> {
    return candidates.find((id) => this.users.rows.has(id)) ?? null;
  }

  async applyIfNewer(userId: string, change: SubscriptionChange, occurredAt: Date): Promise<boolean> {
    const user = this.users.rows.get(userId);
    const last = this.lastEventAt.get(userId);
    if (!user || (last && last >= occurredAt)) return false;
    this.users.rows.set(userId, { ...user, subscriptionTier: change.tier, premiumUntil: change.premiumUntil });
    this.lastEventAt.set(userId, occurredAt);
    return true;
  }
}
