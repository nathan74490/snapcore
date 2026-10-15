import { Body, Controller, HttpCode, Post, UseGuards } from "@nestjs/common";
import { IsObject, IsOptional, IsString } from "class-validator";
import { ValidationError } from "../../../shared/domain/errors";
import type { UserAccount } from "../../identity/domain/user";
import { CurrentUser, Public } from "../../identity/interface/http/auth.decorators";
import { SubscriptionUseCases } from "../application/subscription-use-cases";
import { parseRevenueCatEvent } from "../domain/subscription-event";
import { RevenueCatWebhookGuard } from "./revenuecat-webhook.guard";

// --- Depuis l'app : lien de paiement SnapCore+ --------------------------------------------------------

@Controller("subscription")
export class SubscriptionController {
  constructor(private readonly subscriptions: SubscriptionUseCases) {}

  @Post("checkout")
  @HttpCode(200)
  checkout(@CurrentUser() me: UserAccount): { url: string } {
    return this.subscriptions.checkout(me);
  }
}

// --- Webhook RevenueCat (ADR-025) ------------------------------------------------------------------

class RevenueCatWebhookDto {
  @IsOptional()
  @IsString()
  api_version?: string;

  // Contenu détaillé lu par parseRevenueCatEvent : le format évolue avec RevenueCat, seuls les
  // champs utiles sont exigés.
  @IsObject()
  event: Record<string, unknown>;
}

@Controller("webhooks")
export class RevenueCatWebhookController {
  constructor(private readonly subscriptions: SubscriptionUseCases) {}

  @Public()
  @UseGuards(RevenueCatWebhookGuard)
  @Post("revenuecat")
  @HttpCode(200)
  async receive(@Body() body: RevenueCatWebhookDto): Promise<{ outcome: string }> {
    const event = parseRevenueCatEvent(body.event);
    if (!event) throw new ValidationError("Événement RevenueCat illisible");
    return { outcome: await this.subscriptions.handleEvent(event) };
  }
}
