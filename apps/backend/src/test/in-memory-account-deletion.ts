import type {
  AccountDeletionRepository,
  CollectedMedia,
  DeletionJobQueue,
  DeletionState,
} from "../modules/account-deletion/application/ports";
import type { InMemoryFriendshipRepository } from "./in-memory-friendship.repository";
import type { InMemoryMessageRepository } from "./in-memory-messages";
import type { InMemoryStoryRepository } from "./in-memory-stories";
import type { InMemoryUserRepository } from "./in-memory-user.repository";

// Reproduit la cascade du schéma Prisma (onDelete: Cascade) sur les dépôts en mémoire.
export class InMemoryAccountDeletionRepository implements AccountDeletionRepository {
  failNextDelete = false;

  constructor(
    private readonly users: InMemoryUserRepository,
    private readonly messages: InMemoryMessageRepository,
    private readonly stories: InMemoryStoryRepository,
    private readonly friendships: InMemoryFriendshipRepository,
  ) {}

  async markDeletionRequested(userId: string, at: Date) {
    const user = this.users.rows.get(userId);
    if (!user || user.deletionRequestedAt) return false;
    user.deletionRequestedAt = at;
    return true;
  }

  async getDeletionState(userId: string): Promise<DeletionState> {
    const user = this.users.rows.get(userId);
    if (!user) return "absent";
    return user.deletionRequestedAt ? "deletion-requested" : "active";
  }

  async deleteUserCollectingMedia(userId: string, onCollected: (media: CollectedMedia) => Promise<void>) {
    const user = this.users.rows.get(userId);
    if (!user) return false;
    const messages = [...this.messages.rows.values()].filter((m) => m.senderId === userId || m.recipientId === userId);
    const stories = [...this.stories.rows.values()].filter((s) => s.authorId === userId);
    await onCollected({
      profilePhotoUrl: user.profilePhotoUrl,
      privateKeys: [
        ...messages.flatMap((m) => (m.mediaKey ? [m.mediaKey] : [])),
        ...stories.flatMap((s) => (s.thumbnailKey ? [s.mediaKey, s.thumbnailKey] : [s.mediaKey])),
      ],
    });
    // Simule un échec de la transaction APRÈS la persistance des clés : rien n'est supprimé.
    if (this.failNextDelete) {
      this.failNextDelete = false;
      throw new Error("transaction annulée");
    }
    messages.forEach((m) => this.messages.rows.delete(m.id));
    stories.forEach((s) => this.stories.rows.delete(s.id));
    for (const f of [...this.friendships.rows.values()]) {
      if (f.requesterId === userId || f.addresseeId === userId) this.friendships.rows.delete(f.id);
    }
    this.users.rows.delete(userId);
    return true;
  }

  async listPendingDeletions(limit: number) {
    return [...this.users.rows.values()]
      .filter((u) => u.deletionRequestedAt)
      .slice(0, limit)
      .map((u) => ({ userId: u.id, firebaseUid: u.firebaseUid }));
  }
}

export class RecordingDeletionQueue implements DeletionJobQueue {
  readonly enqueued: Array<{ userId: string; firebaseUid: string }> = [];
  async enqueue(userId: string, firebaseUid: string) {
    this.enqueued.push({ userId, firebaseUid });
  }
}
