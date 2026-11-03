import { Injectable } from "@nestjs/common";
import type { LocationSharingMode, LocationSharingSettings } from "@snapcore/types";
import { PrismaService } from "../../../shared/infrastructure/prisma/prisma.service";
import type { LocationSharingRepository } from "../application/ports";
import type { LocationOwner } from "../domain/location-sharing";

@Injectable()
export class PrismaLocationSharingRepository implements LocationSharingRepository {
  constructor(private readonly prisma: PrismaService) {}

  async findSettings(userId: string): Promise<LocationSharingSettings> {
    const [user, rules] = await Promise.all([
      this.prisma.user.findUnique({ where: { id: userId }, select: { locationSharing: true } }),
      this.prisma.locationShareRule.findMany({ where: { ownerId: userId }, select: { friendId: true }, orderBy: { friendId: "asc" } }),
    ]);
    return { mode: user?.locationSharing ?? "GHOST", friendIds: rules.map((rule) => rule.friendId) };
  }

  async findMode(userId: string): Promise<LocationSharingMode> {
    const user = await this.prisma.user.findUnique({ where: { id: userId }, select: { locationSharing: true } });
    return user?.locationSharing ?? "GHOST";
  }

  async replaceSettings(userId: string, settings: LocationSharingSettings): Promise<LocationSharingSettings> {
    await this.prisma.$transaction([
      this.prisma.user.update({ where: { id: userId }, data: { locationSharing: settings.mode } }),
      this.prisma.locationShareRule.deleteMany({ where: { ownerId: userId } }),
      this.prisma.locationShareRule.createMany({
        data: settings.friendIds.map((friendId) => ({ ownerId: userId, friendId })),
        skipDuplicates: true,
      }),
    ]);
    return this.findSettings(userId);
  }

  async findOwners(viewerId: string, ownerIds: string[]): Promise<Map<string, LocationOwner>> {
    if (ownerIds.length === 0) return new Map();
    const rows = await this.prisma.user.findMany({
      where: { id: { in: ownerIds } },
      select: {
        id: true,
        deletionRequestedAt: true,
        moderationStatus: true,
        locationSharing: true,
        // Uniquement la règle éventuelle visant le viewer : 0 ou 1 ligne par propriétaire.
        locationShareRules: { where: { friendId: viewerId }, select: { friendId: true } },
      },
    });
    return new Map(
      rows.map((row) => [
        row.id,
        {
          deletionRequestedAt: row.deletionRequestedAt,
          moderationStatus: row.moderationStatus,
          locationSharing: row.locationSharing,
          viewerListed: row.locationShareRules.length > 0,
        },
      ]),
    );
  }
}
