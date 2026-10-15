import type { Prisma } from "@snapcore/db";

// Unique définition Prisma d'un compte "actif" (ADR-019, ADR-021) : ni en cours de suppression, ni
// suspendu par la modération. Un compte hors de ce filtre est traité comme inexistant pour les autres
// utilisateurs. Toute requête qui expose ou cible un autre utilisateur doit l'utiliser, plutôt que de
// réécrire les deux conditions (un oubli de l'une romprait la garantie).
export const ACTIVE_USER_WHERE = {
  deletionRequestedAt: null,
  moderationStatus: "ACTIVE",
} as const satisfies Prisma.UserWhereInput;
