import { Logger, Module } from "@nestjs/common";
import { ANALYTICS, type Analytics } from "../../shared/domain/analytics";
import { CLOCK, type Clock } from "../../shared/domain/clock";
import { FRIENDSHIP_REPOSITORY, type FriendshipRepository } from "../friendships/application/ports/friendship.repository";
import { FriendshipsModule } from "../friendships/friendships.module";
import { OBJECT_STORAGE, type ObjectStorage } from "../media/application/ports/object-storage.port";
import { UploadPromoter } from "../media/application/upload-promoter";
import { MediaModule } from "../media/media.module";
import type { SnapPurgeScheduler } from "../messages/application/ports/snap-purge.scheduler";
import { UserEventPublisher } from "../notifications/application/user-event-publisher";
import { NotificationsModule } from "../notifications/notifications.module";
import { PurgeModule } from "../purge/purge.module";
import { GroupMessagingUseCases } from "./application/group-messaging-use-cases";
import { GroupSnapPurge } from "./application/group-snap-purge";
import { GroupUseCases } from "./application/group-use-cases";
import {
  GROUP_MESSAGE_PURGE_SCHEDULER,
  GROUP_NOTIFIER,
  GROUP_REPOSITORY,
  type GroupNotifier,
  type GroupRepository,
} from "./application/ports";
import { EventGroupNotifier } from "./infrastructure/event-group.notifier";
import { PrismaGroupRepository } from "./infrastructure/prisma-group.repository";
import { GroupsController } from "./interface/groups.controller";

// Groupes de discussion (ADR-032). Exporte la purge des snaps de groupe pour le worker de purge.
@Module({
  imports: [FriendshipsModule, MediaModule, NotificationsModule, PurgeModule],
  controllers: [GroupsController],
  providers: [
    { provide: GROUP_REPOSITORY, useClass: PrismaGroupRepository },
    {
      provide: GROUP_NOTIFIER,
      inject: [UserEventPublisher],
      useFactory: (events: UserEventPublisher) => new EventGroupNotifier(events),
    },
    {
      provide: GroupSnapPurge,
      inject: [GROUP_REPOSITORY, OBJECT_STORAGE, GROUP_MESSAGE_PURGE_SCHEDULER, CLOCK],
      useFactory: (groups: GroupRepository, storage: ObjectStorage, scheduler: SnapPurgeScheduler, clock: Clock) =>
        new GroupSnapPurge(groups, storage, scheduler, clock, new Logger(GroupSnapPurge.name)),
    },
    {
      provide: GroupUseCases,
      inject: [GROUP_REPOSITORY, FRIENDSHIP_REPOSITORY, OBJECT_STORAGE, GroupSnapPurge, GROUP_NOTIFIER, CLOCK, ANALYTICS],
      useFactory: (
        groups: GroupRepository,
        friendships: FriendshipRepository,
        storage: ObjectStorage,
        purge: GroupSnapPurge,
        notifier: GroupNotifier,
        clock: Clock,
        analytics: Analytics,
      ) => new GroupUseCases(groups, friendships, storage, purge, notifier, clock, new Logger(GroupUseCases.name), analytics),
    },
    {
      provide: GroupMessagingUseCases,
      inject: [GROUP_REPOSITORY, UploadPromoter, OBJECT_STORAGE, GroupSnapPurge, GROUP_NOTIFIER, CLOCK, ANALYTICS],
      useFactory: (
        groups: GroupRepository,
        promoter: UploadPromoter,
        storage: ObjectStorage,
        purge: GroupSnapPurge,
        notifier: GroupNotifier,
        clock: Clock,
        analytics: Analytics,
      ) =>
        new GroupMessagingUseCases(
          groups,
          promoter,
          storage,
          purge,
          notifier,
          clock,
          new Logger(GroupMessagingUseCases.name),
          analytics,
        ),
    },
  ],
  exports: [GROUP_REPOSITORY, GroupSnapPurge],
})
export class GroupsModule {}
