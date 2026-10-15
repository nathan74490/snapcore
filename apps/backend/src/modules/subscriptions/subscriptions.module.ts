import { Logger, Module } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import { IdentityModule } from "../identity/identity.module";
import { SUBSCRIPTION_REPOSITORY, type SubscriptionRepository } from "./application/ports";
import { SubscriptionUseCases } from "./application/subscription-use-cases";
import { PrismaSubscriptionRepository } from "./infrastructure/prisma-subscription.repository";
import { LegalController } from "./interface/legal.controller";
import { RevenueCatWebhookGuard } from "./interface/revenuecat-webhook.guard";
import { RevenueCatWebhookController, SubscriptionController } from "./interface/subscription.controllers";

// SnapCore+ (ADR-007, ADR-025).
@Module({
  imports: [IdentityModule],
  controllers: [SubscriptionController, RevenueCatWebhookController, LegalController],
  providers: [
    RevenueCatWebhookGuard,
    { provide: SUBSCRIPTION_REPOSITORY, useClass: PrismaSubscriptionRepository },
    {
      provide: SubscriptionUseCases,
      inject: [SUBSCRIPTION_REPOSITORY, ConfigService],
      useFactory: (subscriptions: SubscriptionRepository, config: ConfigService) =>
        new SubscriptionUseCases(
          subscriptions,
          {
            entitlementId: config.get<string>("REVENUECAT_ENTITLEMENT_ID") || "snapcore_plus",
            purchaseLink: config.get<string>("REVENUECAT_PURCHASE_LINK") || null,
          },
          new Logger(SubscriptionUseCases.name),
        ),
    },
  ],
})
export class SubscriptionsModule {}
