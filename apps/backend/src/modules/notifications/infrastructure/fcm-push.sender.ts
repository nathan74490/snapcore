import type { App } from "firebase-admin/app";
import { getMessaging } from "firebase-admin/messaging";
import type { PushSender } from "../application/ports";

// Limite de l'API FCM pour un envoi multicast.
const FCM_BATCH_SIZE = 500;

// Codes FCM signifiant que le token ne sera plus jamais valide : on le supprime (ADR-018).
const DEAD_TOKEN_ERRORS = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
  "messaging/invalid-argument",
]);

// Firebase Cloud Messaging, via la même application Firebase Admin que l'authentification.
export class FcmPushSender implements PushSender {
  constructor(
    private readonly app: App,
    private readonly dryRun = false,
  ) {}

  async send(tokens: string[], data: Record<string, string>): Promise<{ invalidTokens: string[] }> {
    const invalidTokens: string[] = [];
    for (let i = 0; i < tokens.length; i += FCM_BATCH_SIZE) {
      const batch = tokens.slice(i, i + FCM_BATCH_SIZE);
      // Message "data" uniquement : l'app construit la notification affichée elle-même.
      const response = await getMessaging(this.app).sendEachForMulticast(
        { tokens: batch, data, android: { priority: "high" } },
        this.dryRun,
      );
      response.responses.forEach((result, index) => {
        if (!result.success && result.error && DEAD_TOKEN_ERRORS.has(result.error.code)) {
          invalidTokens.push(batch[index]);
        }
      });
    }
    return { invalidTokens };
  }
}
