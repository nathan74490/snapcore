import { FriendshipUseCases } from "../modules/friendships/application/friendship-use-cases";
import { GroupMessagingUseCases } from "../modules/groups/application/group-messaging-use-cases";
import { GroupSnapPurge } from "../modules/groups/application/group-snap-purge";
import { GroupUseCases } from "../modules/groups/application/group-use-cases";
import type { UserAccount } from "../modules/identity/domain/user";
import { RequestUpload } from "../modules/media/application/request-upload";
import { UploadPromoter } from "../modules/media/application/upload-promoter";
import { FixedClock, RecordingAnalytics, RecordingFriendshipNotifier } from "./fakes";
import { InMemoryFriendshipRepository } from "./in-memory-friendship.repository";
import { InMemoryGroupRepository, RecordingGroupNotifier } from "./in-memory-groups";
import { InMemoryObjectStorage, InMemoryUploadTicketStore, silentLogger } from "./in-memory-media";
import { RecordingPurgeScheduler } from "./in-memory-messages";
import { InMemoryUserRepository } from "./in-memory-user.repository";

export const MINUTE = 60 * 1000;
export const HOUR = 60 * MINUTE;
export const DAY = 24 * HOUR;

// Cas d'usage des groupes (ADR-032) branchés sur des dépôts en mémoire, pour les tests unitaires.
export class GroupWorld {
  readonly clock = new FixedClock();
  readonly users = new InMemoryUserRepository();
  readonly friendshipRepo = new InMemoryFriendshipRepository(this.users);
  readonly friendships = new FriendshipUseCases(this.friendshipRepo, this.users, new RecordingFriendshipNotifier(), this.clock);
  readonly groupRepo = new InMemoryGroupRepository(this.users);
  readonly storage = new InMemoryObjectStorage();
  readonly tickets = new InMemoryUploadTicketStore();
  readonly requestUpload = new RequestUpload(this.storage, this.tickets, this.clock);
  readonly scheduler = new RecordingPurgeScheduler();
  readonly notifier = new RecordingGroupNotifier();
  readonly analytics = new RecordingAnalytics();
  readonly purge = new GroupSnapPurge(this.groupRepo, this.storage, this.scheduler, this.clock, silentLogger);
  readonly groups = new GroupUseCases(
    this.groupRepo,
    this.friendshipRepo,
    this.storage,
    this.purge,
    this.notifier,
    this.clock,
    silentLogger,
    this.analytics,
  );
  readonly messaging = new GroupMessagingUseCases(
    this.groupRepo,
    new UploadPromoter(this.storage, this.tickets, silentLogger),
    this.storage,
    this.purge,
    this.notifier,
    this.clock,
    silentLogger,
    this.analytics,
  );

  async user(username: string, overrides: Partial<UserAccount> = {}): Promise<UserAccount> {
    return this.users.seed(username, overrides);
  }

  async befriend(a: UserAccount, ...others: UserAccount[]): Promise<void> {
    for (const b of others) {
      const { friendshipId } = await this.friendships.sendRequest(a, b.username);
      await this.friendships.accept(b, friendshipId);
    }
  }

  async sendSnap(from: UserAccount, groupId: string, contentType: "SNAP_PHOTO" | "SNAP_PHOTO_VIDEO" = "SNAP_PHOTO") {
    const video = contentType === "SNAP_PHOTO_VIDEO";
    const mime = video ? "video/mp4" : "image/jpeg";
    const { uploadId } = await this.requestUpload.execute(from, {
      purpose: video ? "SNAP_VIDEO" : "SNAP_PHOTO",
      contentType: mime,
      contentLength: 5000,
    });
    this.storage.simulateUpload(`tmp/${uploadId}`, 5000, mime);
    return this.messaging.sendSnap(from, groupId, { uploadId, contentType });
  }

  advance(ms: number): void {
    this.clock.current = new Date(this.clock.current.getTime() + ms);
  }
}
