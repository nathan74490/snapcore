import { Module } from "@nestjs/common";
import { AVATAR_RENDERER, type AvatarRenderer } from "./application/ports";
import { RenderAvatar } from "./application/render-avatar";
import { DiceBearAvatarRenderer } from "./infrastructure/dicebear-avatar.renderer";
import { AvatarsController } from "./interface/avatars.controller";

// Avatars dessinés (ADR-028) : catalogue et rendu PNG. L'enregistrement des options d'un
// utilisateur relève du profil (module identity, PUT /me/avatar).
@Module({
  controllers: [AvatarsController],
  providers: [
    { provide: AVATAR_RENDERER, useClass: DiceBearAvatarRenderer },
    {
      provide: RenderAvatar,
      inject: [AVATAR_RENDERER],
      useFactory: (renderer: AvatarRenderer) => new RenderAvatar(renderer),
    },
  ],
})
export class AvatarsModule {}
