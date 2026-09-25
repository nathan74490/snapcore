import { Prisma } from "@snapcore/db";
import { UniqueViolationError } from "../../domain/errors";

// Traduit une violation d'unicité Prisma (P2002) en erreur de domaine ; relance le reste tel quel.
export function rethrowUniqueViolation(error: unknown): never {
  if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2002") {
    const target = error.meta?.target;
    const fields = Array.isArray(target) ? target : [String(target)];
    const field = fields.includes("pairLow") ? "pair" : fields[0];
    throw new UniqueViolationError(field);
  }
  throw error;
}
