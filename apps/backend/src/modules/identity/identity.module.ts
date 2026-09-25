import { Module } from "@nestjs/common";
import { APP_GUARD } from "@nestjs/core";
import type { App } from "firebase-admin/app";
import { IDENTITY_PROVIDER, type IdentityProvider } from "./application/ports/identity-provider.port";
import { USER_REPOSITORY, type UserRepository } from "./application/ports/user.repository";
import { RegisterUser } from "./application/use-cases/register-user";
import { ResolveCurrentUser } from "./application/use-cases/resolve-current-user";
import { SyncEmailFromIdentityProvider } from "./application/use-cases/sync-email";
import { UpdateProfile } from "./application/use-cases/update-profile";
import { FIREBASE_APP, firebaseAppProvider } from "./infrastructure/firebase-app.provider";
import { FirebaseIdentityProvider } from "./infrastructure/firebase-identity.provider";
import { PrismaUserRepository } from "./infrastructure/prisma-user.repository";
import { AuthController } from "./interface/http/auth.controller";
import { AuthGuard } from "./interface/http/auth.guard";
import { MeController } from "./interface/http/me.controller";

// Les cas d'usage restent de simples classes TypeScript (aucun décorateur Nest) :
// le câblage ports → adaptateurs est fait ici, en périphérie (ADR-002).
@Module({
  controllers: [AuthController, MeController],
  providers: [
    firebaseAppProvider,
    { provide: USER_REPOSITORY, useClass: PrismaUserRepository },
    {
      provide: IDENTITY_PROVIDER,
      inject: [FIREBASE_APP],
      useFactory: (app: App) => new FirebaseIdentityProvider(app),
    },
    {
      provide: RegisterUser,
      inject: [USER_REPOSITORY, IDENTITY_PROVIDER],
      useFactory: (users: UserRepository, identity: IdentityProvider) => new RegisterUser(users, identity),
    },
    {
      provide: UpdateProfile,
      inject: [USER_REPOSITORY],
      useFactory: (users: UserRepository) => new UpdateProfile(users),
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
  exports: [USER_REPOSITORY],
})
export class IdentityModule {}
