import { Module } from "@nestjs/common";
import { ConfigModule } from "@nestjs/config";
import { HealthController } from "./health.controller";
import { FriendshipsModule } from "./modules/friendships/friendships.module";
import { IdentityModule } from "./modules/identity/identity.module";
import { MessagesModule } from "./modules/messages/messages.module";
import { RedisModule } from "./shared/infrastructure/redis/redis.module";
import { SharedModule } from "./shared/shared.module";

@Module({
  imports: [ConfigModule.forRoot({ isGlobal: true }), SharedModule, RedisModule, IdentityModule, FriendshipsModule, MessagesModule],
  controllers: [HealthController],
})
export class AppModule {}
