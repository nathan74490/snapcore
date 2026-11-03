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
      inject: [USER_REPOSITORY, UploadPromoter, OBJECT_STORAGE, PublicMediaUrls],
      useFactory: (users: UserRepository, promoter: UploadPromoter, storage: ObjectStorage, urls: PublicMediaUrls) =>
        new ProfilePhotoUseCases(users, promoter, storage, urls, new Logger(ProfilePhotoUseCases.name)),
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
    {
      provide: CheckUsernameAvailability,
      inject: [USER_REPOSITORY],
      useFactory: (users: UserRepository) => new CheckUsernameAvailability(users),
    },
    {
      provide: UpdateProfile,
      inject: [USER_REPOSITORY],
      useFactory: (users: UserRepository) => new UpdateProfile(users),
    },
    {
      provide: UserAvatarUseCases,
      inject: [USER_REPOSITORY],
      useFactory: (users: UserRepository) => new UserAvatarUseCases(users),
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
