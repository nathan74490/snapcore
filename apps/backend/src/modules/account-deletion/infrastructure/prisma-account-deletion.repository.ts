import { Injectable } from "@nestjs/common";
import { PrismaService } from "../../../shared/infrastructure/prisma/prisma.service";
import type { AccountDeletionRepository, CollectedMedia, DeletionState } from "../application/ports";

@Injectable()
export class PrismaAccountDeletionRepository implements AccountDeletionRepository {
  constructor(private readonly prisma: PrismaService) {}

  async markDeletionRequested(userId: string, at: Date): Promise<boolean> {
    const { count } = await this.prisma.user.updateMany({
      where: { id: userId, deletionRequestedAt: null },
      data: { deletionRequestedAt: at },
    });
    return count === 1;
  }

  async getDeletionState(userId: string): Promise<DeletionState> {
    const user = await this.prisma.user.findUnique({ where: { id: userId }, select: { deletionRequestedAt: true } });
    if (!user) return "absent";
    return user.deletionRequestedAt ? "deletion-requested" : "active";
  }

  async deleteUserCollectingMedia(
    userId: string,
    onCollected: (media: CollectedMedia) => Promise<void>,
  ): Promise<boolean> {
    return this.prisma.$transaction(
      async (tx) => {
        // FOR UPDATE : en READ COMMITTED, une insertion de Message vers ce compte doit prendre un
        // verrou de clé étrangère sur cette ligne ; elle attend donc la fin de la transaction,
        // puis échoue sur la clé étrangère une fois la ligne supprimée. Aucune clé ne peut
        // échapper au relevé (ADR-019).
        const [user] = await tx.$queryRaw<Array<{ profilePhotoUrl: string | null }>>`
          SELECT "profilePhotoUrl" FROM "User" WHERE id = ${userId} FOR UPDATE`;
        if (!user) return false;

        const [messages, stories] = await Promise.all([
          tx.message.findMany({
            where: { OR: [{ senderId: userId }, { recipientId: userId }], mediaKey: { not: null } },
            select: { mediaKey: true },
          }),
          tx.storyItem.findMany({ where: { authorId: userId }, select: { mediaKey: true, thumbnailKey: true } }),
        ]);
        await onCollected({
          profilePhotoUrl: user.profilePhotoUrl,
          // Vignettes des stories comprises (ADR-026).
          privateKeys: [
            ...messages.map((m) => m.mediaKey!),
            ...stories.flatMap((s) => (s.thumbnailKey ? [s.mediaKey, s.thumbnailKey] : [s.mediaKey])),
          ],
        });

        await tx.user.delete({ where: { id: userId } });
        return true;
      },
      { timeout: 30_000 },
    );
  }

  async listPendingDeletions(limit: number): Promise<Array<{ userId: string; firebaseUid: string }>> {
    const rows = await this.prisma.user.findMany({
      where: { deletionRequestedAt: { not: null } },
      orderBy: { deletionRequestedAt: "asc" },
      take: limit,
      select: { id: true, firebaseUid: true },
    });
    return rows.map((row) => ({ userId: row.id, firebaseUid: row.firebaseUid }));
  }
}
