// Préparation des tests d'intégration (npm run test:integration) : charge apps/backend/.env, puis
// bascule sur la base de test (voir integration-database.ts) : les tests vident les tables, ils ne
// doivent jamais toucher la base de dev.
//
// Le script lance Jest avec `node --experimental-vm-modules` : firebase-admin obtient son jeton
// OAuth Google via gaxios 7, qui charge `node-fetch` (ESM uniquement) par un import() dynamique ;
// sans ce drapeau, Jest le refuse en CommonJS ("Unexpected Gaxios Error"). Hors de Jest, Node
// exécute cet import nativement : l'application n'est pas concernée.
import "dotenv/config";
import { integrationDatabaseUrl } from "./integration-database";

process.env.DATABASE_URL = integrationDatabaseUrl();
