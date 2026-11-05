import { Logger, Module } from "@nestjs/common";
import { ANALYTICS, type Analytics } from "../../shared/domain/analytics";
import { CLOCK, type Clock } from "../../shared/domain/clock";
import { APP_GUARD } from "@nestjs/core";
import type { App } from "firebase-admin/app";
import { OBJECT_STORAGE, type ObjectStorage } from "../media/application/ports/object-storage.port";
import { UploadPromoter } from "../media/application/upload-promoter";
import { PublicMediaUrls } from "../media/domain/public-media-urls";
import { MediaModule } from "../media/media.module";
import { ProfilePhotoUseCases } from "./application/use-cases/profile-photo";
import { IDENTITY_PROVIDER, type IdentityProvider } from "./application/ports/identity-provider.port";
import { USER_REPOSITORY, type UserRepository } from "./application/ports/user.repository";
import { CheckUsernameAvailability } from "./application/use-cases/check-username-availability";
import { RegisterUser } from "./application/use-cases/register-user";
import { PhoneSignIn } from "./application/use-cases/phone-sign-in";
import { PHONE_CODE_STORE, SMS_SENDER, type PhoneCodeStore, type SmsSender } from "./application/ports/phone-sign-in.ports";
import { RedisPhoneCodeStore } from "./infrastructure/redis-phone-code.store";
import { SimulatedSmsSender } from "./infrastructure/simulated-sms.sender";
import { REDIS } from "../../shared/infrastructure/redis/redis.module";
import type Redis from "ioredis";
import { ResolveCurrentUser } from "./application/use-cases/resolve-current-user";
import { SyncEmailFromIdentityProvider } from "./application/use-cases/sync-email";
import { UpdateProfile } from "./application/use-cases/update-profile";
import { UserAvatarUseCases } from "./application/use-cases/user-avatar";
import { FIREBASE_APP, firebaseAppProvider } from "./infrastructure/firebase-app.provider";
import { FirebaseIdentityProvider } from "./infrastructure/firebase-identity.provider";
import { PrismaUserRepository } from "./infrastructure/prisma-user.repository";
import { AuthController } from "./interface/http/auth.controller";
import { AuthGuard } from "./interface/http/auth.guard";
import { MeController } from "./interface/http/me.controller";
import { UsersController } from "./interface/http/users.controller";

// Les cas d'usage restent de simples classes TypeScript (aucun décorateur Nest) :
// le câblage ports → adaptateurs est fait ici, en périphérie (ADR-002).
@Module({
  imports: [MediaModule],
  controllers: [AuthController, MeController, UsersController],
  providers: [
    {
      provide: ProfilePhotoUseCases,
      inject: [USER_REPOSITORY, UploadPromoter, OBJECT_STORAGE, PublicMediaUrls, ANALYTICS],
      useFactory: (
        users: UserRepository,
        promoter: UploadPromoter,
        storage: ObjectStorage,
        urls: PublicMediaUrls,
        analytics: Analytics,
      ) => new ProfilePhotoUseCases(users, promoter, storage, urls, new Logger(ProfilePhotoUseCases.name), analytics),
    },
    firebaseAppProvider,
    { provide: USER_REPOSITORY, useClass: PrismaUserRepository },
    {
      provide: IDENTITY_PROVIDER,
      inject: [FIREBASE_APP],
      useFactory: (app: App) => new FirebaseIdentityProvider(app),
    },
    {
      provide: RegisterUser,
      inject: [USER_REPOSITORY, IDENTITY_PROVIDER, ANALYTICS, CLOCK],
      useFactory: (users: UserRepository, identity: IdentityProvider, analytics: Analytics, clock: Clock) =>
        new RegisterUser(users, identity, analytics, clock),
    },
    { provide: PHONE_CODE_STORE, inject: [REDIS], useFactory: (redis: Redis) => new RedisPhoneCodeStore(redis) },
    // Opérateur SMS fictif (ADR-034) : à remplacer par un vrai opérateur avant toute ouverture au public.
    { provide: SMS_SENDER, useFactory: () => new SimulatedSmsSender() },
    {
      provide: PhoneSignIn,
      inject: [PHONE_CODE_STORE, SMS_SENDER, IDENTITY_PROVIDER, CLOCK],
      useFactory: (codes: PhoneCodeStore, sms: SmsSender, identity: IdentityProvider, clock: Clock) =>
        new PhoneSignIn(codes, sms, identity, clock),
    },
    {
      provide: CheckUsernameAvailability,
      inject: [USER_REPOSITORY],
      useFactory: (users: UserRepository) => new CheckUsernameAvailability(users),
    },
    {
      provide: UpdateProfile,
      inject: [USER_REPOSITORY, ANALYTICS],
      useFactory: (users: UserRepository, analytics: Analytics) => new UpdateProfile(users, analytics),
    },
    {
      provide: UserAvatarUseCases,
      inject: [USER_REPOSITORY, ANALYTICS],
      useFactory: (users: UserRepository, analytics: Analytics) => new UserAvatarUseCases(users, analytics),
    },
    {
      provide: SyncEmailFromIdentityProvider,
      inject: [USER_REPOSITORY, IDENTITY_PROVIDER],
      useFactory: (users: UserRepository, identity: IdentityProvider) =>
        new SyncEmailFromIdentityProvider(users, identity),
    },
    {
      provide: ResolveCurrentUser,
      inject: [USER_REPOSITORY],
      useFactory: (users: UserRepository) => new ResolveCurrentUser(users),
    },
    { provide: APP_GUARD, useClass: AuthGuard },
  ],
  exports: [USER_REPOSITORY, IDENTITY_PROVIDER, ResolveCurrentUser, FIREBASE_APP],
})
export class IdentityModule {}
