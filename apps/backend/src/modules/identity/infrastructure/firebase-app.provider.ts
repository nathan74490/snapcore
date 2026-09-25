import type { Provider } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import { applicationDefault, cert, getApps, initializeApp, type App } from "firebase-admin/app";
import { readFileSync } from "node:fs";

export const FIREBASE_APP = Symbol("FirebaseApp");

// Une seule application Firebase Admin, partagée par l'authentification (ADR-015)
// et, plus tard, par les notifications push FCM (ADR-018).
export const firebaseAppProvider: Provider = {
  provide: FIREBASE_APP,
  inject: [ConfigService],
  useFactory: (config: ConfigService): App => {
    const existing = getApps()[0];
    if (existing) return existing;
    const serviceAccountPath = config.get<string>("FIREBASE_SERVICE_ACCOUNT_PATH");
    const credential = serviceAccountPath
      ? cert(JSON.parse(readFileSync(serviceAccountPath, "utf8")))
      : applicationDefault();
    return initializeApp({ credential });
  },
};
