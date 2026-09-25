// Une fois avant les tests d'intégration : crée la base de test si besoin et y applique les
// migrations (prisma migrate deploy crée la base absente).
import "dotenv/config";
import { execSync } from "node:child_process";
import { resolve } from "node:path";
import { integrationDatabaseUrl } from "./integration-database";

export default function globalSetup(): void {
  execSync("npx prisma migrate deploy", {
    cwd: resolve(__dirname, "../../../../packages/db"),
    env: { ...process.env, DATABASE_URL: integrationDatabaseUrl() },
    stdio: "ignore",
  });
}
