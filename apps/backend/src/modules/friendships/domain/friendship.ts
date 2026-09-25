import type { FriendshipRelation, FriendshipStatus } from "@snapcore/types";

export interface Friendship {
  id: string;
  requesterId: string;
  addresseeId: string;
  status: FriendshipStatus;
  blockedBy: string | null;
  requestedAt: Date;
  respondedAt: Date | null;
}

// Paire triée indépendante du sens : c'est elle qui porte la contrainte d'unicité en base (ADR-012).
// Comparaison binaire, identique à la collation "C" de la contrainte CHECK de la migration initiale.
export function sortedPair(a: string, b: string): { pairLow: string; pairHigh: string } {
  return a < b ? { pairLow: a, pairHigh: b } : { pairLow: b, pairHigh: a };
}

export function otherMemberOf(friendship: Friendship, userId: string): string {
  return friendship.requesterId === userId ? friendship.addresseeId : friendship.requesterId;
}

// Règle unique d'ADR-012 : envoyer un message, lire un snap reçu et voir la conversation
// exigent tous une FRIENDSHIP ACCEPTED — elle couvre d'un coup BLOCKED, retrait d'ami et absence de ligne.
export function allowsMessaging(friendship: Friendship | null): boolean {
  return friendship?.status === "ACCEPTED";
}

export type FriendRequestPlan =
  | { kind: "create" }
  | { kind: "reopen-declined" } // la ligne est réutilisée, requester/addressee recalculés
  | { kind: "accept-incoming" } // l'autre avait déjà demandé : répondre revient à accepter
  | { kind: "already-pending" }
  | { kind: "already-friends" }
  | { kind: "blocked" };

export function planFriendRequest(existing: Friendship | null, requesterId: string): FriendRequestPlan {
  if (!existing) return { kind: "create" };
  switch (existing.status) {
    case "PENDING":
      return existing.requesterId === requesterId ? { kind: "already-pending" } : { kind: "accept-incoming" };
    case "ACCEPTED":
      return { kind: "already-friends" };
    case "DECLINED":
      return { kind: "reopen-declined" };
    case "BLOCKED":
      return { kind: "blocked" };
  }
}

export type BlockPlan =
  | { kind: "create" } // pas encore de relation : la ligne naît directement BLOCKED
  | { kind: "block-existing" }
  | { kind: "noop" }; // déjà bloqué, par soi-même ou par l'autre (blocage mutuel, ADR-012)

export function planBlock(existing: Friendship | null): BlockPlan {
  if (!existing) return { kind: "create" };
  return existing.status === "BLOCKED" ? { kind: "noop" } : { kind: "block-existing" };
}

// Relation présentée à `viewerId` dans une recherche. Un refus n'est jamais révélé
// (DECLINED → NONE) ; une paire BLOCKED n'est jamais présentée du tout (ADR-012).
export function relationFor(friendship: Friendship | null, viewerId: string): FriendshipRelation {
  if (!friendship) return "NONE";
  switch (friendship.status) {
    case "ACCEPTED":
      return "FRIENDS";
    case "PENDING":
      return friendship.requesterId === viewerId ? "OUTGOING_REQUEST" : "INCOMING_REQUEST";
    default:
      return "NONE";
  }
}
