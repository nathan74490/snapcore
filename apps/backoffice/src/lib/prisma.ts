import "server-only";
import { PrismaClient } from "@snapcore/db";

// Un seul client Prisma par processus (le rechargement à chaud de `next dev` en recréerait sinon
// un à chaque modification). Le backoffice LIT directement PostgreSQL (ADR-004, ADR-021) ;
// toute écriture passe par l'API admin du backend (voir admin-api.ts).
const globalForPrisma = globalThis as unknown as { prisma?: PrismaClient };

export const prisma = globalForPrisma.prisma ?? new PrismaClient();

if (process.env.NODE_ENV !== "production") globalForPrisma.prisma = prisma;
