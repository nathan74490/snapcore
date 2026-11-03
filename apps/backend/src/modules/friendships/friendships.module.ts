import { Module } from "@nestjs/common";
import { ANALYTICS, type Analytics } from "../../shared/domain/analytics";
import { CLOCK, type Clock } from "../../shared/domain/clock";
import { USER_REPOSITORY, type UserRepository } from "../identity/application/ports/user.repository";
import { IdentityModule } from "../identity/identity.module";
import { UserEventPublisher } from "../notifications/application/user-event-publisher";
import { NotificationsModule } from "../notifications/notifications.module";
import { FriendshipUseCases } from "./application/friendship-use-cases";
import { FRIENDSHIP_NOTIFIER, type FriendshipNotifier } from "./application/ports/friendship-notifier.port";
import { FRIENDSHIP_REPOSITORY, type FriendshipRepository } from "./application/ports/friendship.repository";
import { EventFriendshipNotifier } from "./infrastructure/event-friendship.notifier";
import { PrismaFriendshipRepository } from "./infrastructure/prisma-friendship.repository";
import { FriendshipsController } from "./interface/http/friendships.controller";

@Module({
  imports: [IdentityModule, NotificationsModule],
  controllers: [FriendshipsController],
  providers: [
    { provide: FRIENDSHIP_REPOSITORY, useClass: PrismaFriendshipRepository },
    {
      provide: FRIENDSHIP_NOTIFIER,
      inject: [UserEventPublisher],
      useFactory: (events: UserEventPublisher) => new EventFriendshipNotifier(events),
    },
    {
      provide: FriendshipUseCases,
      inject: [FRIENDSHIP_REPOSITORY, USER_REPOSITORY, FRIENDSHIP_NOTIFIER, CLOCK, ANALYTICS],
      useFactory: (
        friendships: FriendshipRepository,
        users: UserRepository,
        notifier: FriendshipNotifier,
        clock: Clock,
        analytics: Analytics,
      ) => new FriendshipUseCases(friendships, users, notifier, clock, analytics),
    },
  ],
  exports: [FRIENDSHIP_REPOSITORY],
})
export class FriendshipsModule {}
