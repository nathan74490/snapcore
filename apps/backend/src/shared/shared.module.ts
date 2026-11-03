import { Global, Module } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import { ANALYTICS, noAnalytics } from "./domain/analytics";
import { CLOCK, systemClock } from "./domain/clock";
import { PostHogAnalytics } from "./infrastructure/posthog-analytics";
import { PrismaModule } from "./infrastructure/prisma/prisma.module";

@Global()
@Module({
  imports: [PrismaModule],
  providers: [
    { provide: CLOCK, useValue: systemClock },
    {
      // Analytics PostHog (ADR-008) ; sans POSTHOG_API_KEY, aucun envoi.
      provide: ANALYTICS,
      inject: [ConfigService],
      useFactory: (config: ConfigService) => {
        const apiKey = config.get<string>("POSTHOG_API_KEY");
        return apiKey ? new PostHogAnalytics(apiKey, config.get<string>("POSTHOG_HOST") || "https://eu.i.posthog.com") : noAnalytics;
      },
    },
  ],
  exports: [CLOCK, ANALYTICS],
})
export class SharedModule {}
