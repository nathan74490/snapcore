import { Controller, Get, Header, Query, StreamableFile } from "@nestjs/common";
import type { AvatarCatalog } from "@snapcore/types";
import { Public } from "../../identity/interface/http/auth.decorators";
import { RenderAvatar } from "../application/render-avatar";
import { avatarCatalog } from "../domain/avatar-catalog";

// Avatars dessinés façon Bitmoji (ADR-028).
@Controller("avatars")
export class AvatarsController {
  constructor(private readonly renderAvatar: RenderAvatar) {}

  // Catalogue de l'éditeur : authentifié comme le reste de l'API.
  @Get("catalog")
  catalog(): AvatarCatalog {
    return avatarCatalog();
  }

  // Public (chargé par le client d'images de l'app, sans ID token) : le PNG ne dépend que des
  // paramètres, jamais d'un compte. Mêmes paramètres = mêmes octets, d'où un cache immuable.
  @Get("avataaars.png")
  @Public()
  @Header("Cache-Control", "public, max-age=31536000, immutable")
  png(@Query() query: Record<string, unknown>): StreamableFile {
    return new StreamableFile(this.renderAvatar.execute(query), { type: "image/png" });
  }
}
