import { Module } from "@nestjs/common";
import { ConfigModule } from "@nestjs/config";
import { HealthController } from "./health.controller";
import { AccountDeletionModule } from "./modules/account-deletion/account-deletion.module";
import { AvatarsModule } from "./modules/avatars/avatars.module";
import { FriendshipsModule } from "./modules/friendships/friendships.module";
import { GroupsModule } from "./modules/groups/groups.module";
import { IdentityModule } from "./modules/identity/identity.module";
import { LiveModule } from "./modules/live/live.module";
import { LocationModule } from "./modules/location/location.module";
import { MessagesModule } from "./modules/messages/messages.module";
import { ModerationModule } from "./modules/moderation/moderation.module";
import { PurgeWorkerModule } from "./modules/purge/purge-worker.module";
import { StoriesModule } from "./modules/stories/stories.module";
import { SubscriptionsModule } from "./modules/subscriptions/subscriptions.module";
import { RedisModule } from "./shared/infrastructure/redis/redis.module";
import { SharedModule } from "./shared/shared.module";

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true }),
    SharedModule,
    RedisModule,
    IdentityModule,
    FriendshipsModule,
    MessagesModule,
    StoriesModule,
    PurgeWorkerModule,
    AccountDeletionModule,
    ModerationModule,
    SubscriptionsModule,
    AvatarsModule,
    LocationModule,
    GroupsModule,
    LiveModule,
  ],
  controllers: [HealthController],
})
export class AppModule {}
