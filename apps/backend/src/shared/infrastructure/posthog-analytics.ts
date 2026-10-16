import { Logger, type OnApplicationShutdown } from "@nestjs/common";
import { PostHog } from "posthog-node";
import type { Analytics } from "../domain/analytics";

// Adaptateur PostHog cloud (ADR-008). Les événements partent par lots, en arrière-plan : une panne de
// PostHog ne bloque ni ne fait échouer aucune requête. Le lot en attente est envoyé à l'arrêt.
export class PostHogAnalytics implements Analytics, OnApplicationShutdown {
  private readonly logger = new Logger(PostHogAnalytics.name);
  private readonly client: PostHog;

  constructor(apiKey: string, host: string) {
    this.client = new PostHog(apiKey, { host, flushAt: 20, flushInterval: 10_000 });
    this.client.on("error", (error: unknown) => this.logger.warn(`Envoi PostHog impossible : ${String(error)}`));
  }

  capture(userId: string, event: string, properties?: Record<string, string | number | boolean>): void {
    this.client.capture({ distinctId: userId, event, properties: { ...properties, source: "backend" } });
  }

  async onApplicationShutdown(): Promise<void> {
    await this.client.shutdown();
  }
}
