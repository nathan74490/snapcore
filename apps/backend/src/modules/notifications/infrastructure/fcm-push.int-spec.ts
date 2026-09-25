import { cert, initializeApp } from "firebase-admin/app";
import { readFileSync } from "node:fs";
import { FcmPushSender } from "./fcm-push.sender";

// Contre l'API FCM réelle du projet Firebase, en mode "dry run" (rien n'est délivré).
// Vérifie que le compte de service peut envoyer, et que les tokens morts sont bien détectés.
describe("FcmPushSender (FCM réel, dry run)", () => {
  const app = initializeApp(
    { credential: cert(JSON.parse(readFileSync(process.env.FIREBASE_SERVICE_ACCOUNT_PATH!, "utf8"))) },
    "fcm-int-spec",
  );

  it("identifie un token invalide comme à supprimer", async () => {
    const sender = new FcmPushSender(app, true);

    const { invalidTokens } = await sender.send(["token-bidon-invalide"], { type: "message.received", messageId: "m1" });

    expect(invalidTokens).toEqual(["token-bidon-invalide"]);
  });
});
