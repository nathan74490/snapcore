// Base des tests d'intégration : TEST_DATABASE_URL, sinon DATABASE_URL avec le nom de base suffixé
// par `_test` (snapcore → snapcore_test, même serveur Postgres). Refus net si le nom ne finit pas
// par `_test` : les tests vident les tables.
export function integrationDatabaseUrl(): string {
  const source = process.env.TEST_DATABASE_URL ?? process.env.DATABASE_URL;
  if (!source) throw new Error("DATABASE_URL (ou TEST_DATABASE_URL) requis pour les tests d'intégration");
  const url = new URL(source);
  if (!process.env.TEST_DATABASE_URL) url.pathname = `${url.pathname.replace(/\/$/, "")}_test`;
  if (!url.pathname.endsWith("_test")) {
    throw new Error(`Base de test refusée (${url.pathname.slice(1)}) : son nom doit finir par _test`);
  }
  return url.toString();
}
