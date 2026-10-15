import type { FriendshipStatus, PublicUserProfile } from "@snapcore/types";
import type { Friendship } from "../../domain/friendship";

export const FRIENDSHIP_REPOSITORY = Symbol("FriendshipRepository");

export interface NewFriendship {
  requesterId: string;
  addresseeId: string;
  status: FriendshipStatus;
  blockedBy: string | null;
  requestedAt: Date;
  respondedAt: Date | null;
}

export type FriendshipPatch = Partial<Omit<Friendship, "id">>;

export interface FriendshipWithUser {
  friendship: Friendship;
  user: PublicUserProfile;
}

export interface FriendshipRepository {
  findById(id: string): Promise<Friendship | null>;
  /** Ligne de la paire, quel que soit le sens (paire triée). */
  findByPair(userA: string, userB: string): Promise<Friendship | null>;

  /**
   * L'adaptateur calcule pairLow/pairHigh.
   * @throws UniqueViolationError("pair") si une ligne existe déjà pour la paire (création concurrente).
   */
  create(friendship: NewFriendship): Promise<Friendship>;

  /**
   * Mise à jour conditionnelle : n'applique `patch` que si la ligne est toujours dans l'état `expectedStatus`.
   * @returns la ligne mise à jour, ou null si elle a changé ou disparu entre-temps (course).
   */
  transition(id: string, expectedStatus: FriendshipStatus, patch: FriendshipPatch): Promise<Friendship | null>;

  /**
   * Suppression conditionnelle de la ligne de la paire.
   * @returns true si une ligne correspondant à tous les critères a été supprimée.
   */
  deleteForPair(
    userA: string,
    userB: string,
    criteria: { status: FriendshipStatus; blockedBy?: string },
  ): Promise<boolean>;

  // Listes vues depuis `userId`. L'autre utilisateur doit être actif : un compte en cours
  // de suppression n'apparaît dans aucune liste (ADR-019).
  listFriends(userId: string): Promise<FriendshipWithUser[]>;
  listIncomingRequests(userId: string): Promise<FriendshipWithUser[]>;
  listOutgoingRequests(userId: string): Promise<FriendshipWithUser[]>;
  listBlockedBy(userId: string): Promise<FriendshipWithUser[]>;
}
