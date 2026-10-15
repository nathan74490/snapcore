import { Injectable } from "@nestjs/common";
import { PrismaService } from "../../../shared/infrastructure/prisma/prisma.service";
import type { SubscriptionRepository } from "../application/ports";
import type { SubscriptionChange } from "../domain/subscription-event";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

@Injectable()
export class PrismaSubscriptionRepository implements SubscriptionRepository {
  constructor(private readonly prisma: PrismaService) {}

  async findUserId(candidates: string[]): Promise<string | null> {
    // Les ids anonymes de RevenueCat ($RCAnonymousID:…) ne sont pas des comptes SnapCore.
    const ids = candidates.filter((id) => UUID.test(id));
    if (ids.length === 0) return null;
    const rows = await this.prisma.user.findMany({ where: { id: { in: ids } }, select: { id: true } });
    return ids.find((id) => rows.some((row) => row.id === id)) ?? null;
  }

  async applyIfNewer(userId: string, change: SubscriptionChange, occurredAt: Date): Promise<boolean> {
    const { count } = await this.prisma.user.updateMany({
      where: { id: userId, OR: [{ subscriptionEventAt: null }, { subscriptionEventAt: { lt: occurredAt } }] },
      data: { subscriptionTier: change.tier, premiumUntil: change.premiumUntil, subscriptionEventAt: occurredAt },
    });
    return count === 1;
  }
}
