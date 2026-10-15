import { Logger, Module } from "@nestjs/common";
import { CLOCK, type Clock } from "../../shared/domain/clock";
import { FRIENDSHIP_REPOSITORY, type FriendshipRepository } from "../friendships/application/ports/friendship.repository";
import { FriendshipsModule } from "../friendships/friendships.module";
import { USER_REPOSITORY, type UserRepository } from "../identity/application/ports/user.repository";
import { IdentityModule } from "../identity/identity.module";
import { OBJECT_STORAGE, type ObjectStorage } from "../media/application/ports/object-storage.port";
import { MediaModule } from "../media/media.module";
import { MESSAGE_REPOSITORY, type MessageRepository } from "../messages/application/ports/message.repository";
import { SNAP_PURGE_SCHEDULER, type SnapPurgeScheduler } from "../messages/application/ports/snap-purge.scheduler";
import { MessagesModule } from "../messages/messages.module";
import { REALTIME_CHANNEL, type RealtimeChannel } from "../notifications/application/ports";
import { UserEventPublisher } from "../notifications/application/user-event-publisher";
import { NotificationsModule } from "../notifications/notifications.module";
import { PurgeModule } from "../purge/purge.module";
import {
  STORY_PURGE_SCHEDULER,
  STORY_REPOSITORY,
  STORY_VIEW_STORE,
  type StoryPurgeScheduler,
  type StoryRepository,
  type StoryViewStore,
} from "../stories/application/ports";
import { StoriesModule } from "../stories/stories.module";
import { ModerationUseCases } from "./application/moderation-use-cases";
import { MODERATION_REPOSITORY, type ModerationRepository } from "./application/ports";
import { ReportUseCases } from "./application/report-use-cases";
import { PrismaModerationRepository } from "./infrastructure/prisma-moderation.repository";
import { AdminSecretGuard } from "./interface/admin-secret.guard";
import { AdminController, ReportsController } from "./interface/moderation.controllers";

@Module({
  imports: [IdentityModule, FriendshipsModule, MessagesModule, StoriesModule, MediaModule, NotificationsModule, PurgeModule],
  controllers: [ReportsController, AdminController],
  providers: [
    AdminSecretGuard,
    { provide: MODERATION_REPOSITORY, useClass: PrismaModerationRepository },
    {
      provide: ModerationUseCases,
      inject: [
        MODERATION_REPOSITORY,
        MESSAGE_REPOSITORY,
        STORY_REPOSITORY,
        STORY_VIEW_STORE,
        OBJECT_STORAGE,
        SNAP_PURGE_SCHEDULER,
        STORY_PURGE_SCHEDULER,
        REALTIME_CHANNEL,
        UserEventPublisher,
        CLOCK,
      ],
      useFactory: (
        moderation: ModerationRepository,
        messages: MessageRepository,
        stories: StoryRepository,
        storyViews: StoryViewStore,
        storage: ObjectStorage,
        snapPurge: SnapPurgeScheduler,
        storyPurge: StoryPurgeScheduler,
        realtime: RealtimeChannel,
        events: UserEventPublisher,
        clock: Clock,
      ) =>
        new ModerationUseCases(
          moderation,
          messages,
          stories,
          storyViews,
          storage,
          snapPurge,
          storyPurge,
          realtime,
          events,
          clock,
          new Logger(ModerationUseCases.name),
        ),
    },
    {
      provide: ReportUseCases,
      inject: [MODERATION_REPOSITORY, USER_REPOSITORY, MESSAGE_REPOSITORY, STORY_REPOSITORY, FRIENDSHIP_REPOSITORY, CLOCK],
      useFactory: (
        moderation: ModerationRepository,
        users: UserRepository,
        messages: MessageRepository,
        stories: StoryRepository,
        friendships: FriendshipRepository,
        clock: Clock,
      ) => new ReportUseCases(moderation, users, messages, stories, friendships, clock),
    },
  ],
})
export class ModerationModule {}
