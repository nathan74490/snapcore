import type { FriendshipListItem, UserSearchResult } from "@snapcore/types";
import type { Clock } from "../../../shared/domain/clock";
import {
  ConflictError,
  NotFoundError,
  UniqueViolationError,
  ValidationError,
} from "../../../shared/domain/errors";
import type { UserRepository } from "../../identity/application/ports/user.repository";
import { toPublicProfile, type UserAccount } from "../../identity/domain/user";
import { normalizeUsername } from "../../identity/domain/username";
import { planBlock, planFriendRequest, relationFor, type Friendship } from "../domain/friendship";
import type { FriendshipNotifier } from "./ports/friendship-notifier.port";
import type { FriendshipRepository, FriendshipWithUser } from "./ports/friendship.repository";

// Même message pour "pseudo introuvable" et "paire BLOCKED" : un utilisateur bloqué
// ne doit pas pouvoir déduire qu'il l'est (ADR-012).
const USER_NOT_FOUND = "Utilisateur introuvable";
const REQUEST_NOT_FOUND = "Demande d'ami introuvable";

// Nombre de relectures en cas de course (création concurrente de la même paire,
// ou ligne modifiée entre la lecture et la mise à jour conditionnelle).
const MAX_ATTEMPTS = 3;

export class FriendshipUseCases {
  constructor(
    private readonly friendships: FriendshipRepository,
    private readonly users: UserRepository,
    private readonly notifier: FriendshipNotifier,
    private readonly clock: Clock,
  ) {}

  async searchByUsername(me: UserAccount, rawUsername: string): Promise<UserSearchResult> {
    const target = await this.users.findActiveByUsername(normalizeUsername(rawUsername));
    if (!target) throw new NotFoundError(USER_NOT_FOUND);
    const friendship = target.id === me.id ? null : await this.friendships.findByPair(me.id, target.id);
    if (friendship?.status === "BLOCKED") throw new NotFoundError(USER_NOT_FOUND);
    return { ...toPublicProfile(target), relation: relationFor(friendship, me.id) };
  }

  async sendRequest(me: UserAccount, rawUsername: string): Promise<FriendshipListItem> {
    const target = await this.users.findActiveByUsername(normalizeUsername(rawUsername));
    if (!target) throw new NotFoundError(USER_NOT_FOUND);
    if (target.id === me.id) throw new ValidationError("Impossible de s'ajouter soi-même en ami");

    for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      const existing = await this.friendships.findByPair(me.id, target.id);
      const now = this.clock.now();
      const plan = planFriendRequest(existing, me.id);

      switch (plan.kind) {
        case "create": {
          try {
            const created = await this.friendships.create({
              requesterId: me.id,
              addresseeId: target.id,
              status: "PENDING",
              blockedBy: null,
              requestedAt: now,
              respondedAt: null,
            });
            await this.notifier.friendRequestReceived(target.id, created.id);
            return present(created, target);
          } catch (error) {
            // L'autre utilisateur a créé la paire en même temps : on relit et on réapplique les règles.
            if (error instanceof UniqueViolationError) continue;
            throw error;
          }
        }
        case "reopen-declined": {
          // La ligne existante est réutilisée ; le sens suit celui de la nouvelle demande (ADR-012).
          const reopened = await this.friendships.transition(existing!.id, "DECLINED", {
            requesterId: me.id,
            addresseeId: target.id,
            status: "PENDING",
            requestedAt: now,
            respondedAt: null,
          });
          if (!reopened) continue;
          await this.notifier.friendRequestReceived(target.id, reopened.id);
          return present(reopened, target);
        }
        case "accept-incoming": {
          const accepted = await this.friendships.transition(existing!.id, "PENDING", {
            status: "ACCEPTED",
            respondedAt: now,
          });
          if (!accepted) continue;
          await this.notifier.friendRequestAccepted(target.id, accepted.id);
          return present(accepted, target);
        }
        case "already-pending":
          return present(existing!, target);
        case "already-friends":
          throw new ConflictError("Vous êtes déjà amis");
        case "blocked":
          throw new NotFoundError(USER_NOT_FOUND);
      }
    }
    throw new ConflictError("La relation a été modifiée entre-temps, réessayez");
  }

  async accept(me: UserAccount, friendshipId: string): Promise<FriendshipListItem> {
    const { friendship, requester } = await this.findIncomingRequest(me, friendshipId);
    const accepted = await this.friendships.transition(friendship.id, "PENDING", {
      status: "ACCEPTED",
      respondedAt: this.clock.now(),
    });
    if (!accepted) throw new NotFoundError(REQUEST_NOT_FOUND);
    await this.notifier.friendRequestAccepted(requester.id, accepted.id);
    return present(accepted, requester);
  }

  async decline(me: UserAccount, friendshipId: string): Promise<void> {
    const { friendship } = await this.findIncomingRequest(me, friendshipId);
    const declined = await this.friendships.transition(friendship.id, "PENDING", {
      status: "DECLINED",
      respondedAt: this.clock.now(),
    });
    if (!declined) throw new NotFoundError(REQUEST_NOT_FOUND);
  }

  // Retirer un ami supprime la ligne, comme un déblocage : les deux redeviennent inconnus
  // au sens de la messagerie, sans blocage, et l'historique de messages est conservé (ADR-012).
  async removeFriend(me: UserAccount, friendId: string): Promise<void> {
    const removed = await this.friendships.deleteForPair(me.id, friendId, { status: "ACCEPTED" });
    if (!removed) throw new NotFoundError("Ami introuvable");
  }

  // Ne renvoie rien : la réponse est identique que la paire soit déjà bloquée par l'autre
  // (blocage mutuel = no-op apparent) ou non (ADR-012).
  async block(me: UserAccount, targetId: string): Promise<void> {
    if (targetId === me.id) throw new ValidationError("Impossible de se bloquer soi-même");
    const target = await this.users.findActiveById(targetId);
    if (!target) throw new NotFoundError(USER_NOT_FOUND);

    for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      const existing = await this.friendships.findByPair(me.id, target.id);
      const now = this.clock.now();
      const plan = planBlock(existing);

      switch (plan.kind) {
        case "noop":
          return;
        case "create":
          try {
            await this.friendships.create({
              requesterId: me.id,
              addresseeId: target.id,
              status: "BLOCKED",
              blockedBy: me.id,
              requestedAt: now,
              respondedAt: now,
            });
            return;
          } catch (error) {
            if (error instanceof UniqueViolationError) continue;
            throw error;
          }
        case "block-existing": {
          const blocked = await this.friendships.transition(existing!.id, existing!.status, {
            status: "BLOCKED",
            blockedBy: me.id,
            respondedAt: now,
          });
          if (blocked) return;
          continue;
        }
      }
    }
    throw new ConflictError("La relation a été modifiée entre-temps, réessayez");
  }

  // Seul celui qui a bloqué peut débloquer ; la ligne est supprimée et une ancienne amitié
  // n'est pas restaurée (ADR-012).
  async unblock(me: UserAccount, targetId: string): Promise<void> {
    const unblocked = await this.friendships.deleteForPair(me.id, targetId, { status: "BLOCKED", blockedBy: me.id });
    if (!unblocked) throw new NotFoundError(USER_NOT_FOUND);
  }

  async listFriends(me: UserAccount): Promise<FriendshipListItem[]> {
    return (await this.friendships.listFriends(me.id)).map(presentListed);
  }

  async listIncomingRequests(me: UserAccount): Promise<FriendshipListItem[]> {
    return (await this.friendships.listIncomingRequests(me.id)).map(presentListed);
  }

  async listOutgoingRequests(me: UserAccount): Promise<FriendshipListItem[]> {
    return (await this.friendships.listOutgoingRequests(me.id)).map(presentListed);
  }

  // Seul endroit où un utilisateur bloqué reste visible : sans cette liste, le bloqueur
  // ne pourrait plus retrouver le profil qu'il a lui-même masqué pour le débloquer (ADR-012).
  async listBlocked(me: UserAccount): Promise<FriendshipListItem[]> {
    return (await this.friendships.listBlockedBy(me.id)).map(presentListed);
  }

  private async findIncomingRequest(
    me: UserAccount,
    friendshipId: string,
  ): Promise<{ friendship: Friendship; requester: UserAccount }> {
    const friendship = await this.friendships.findById(friendshipId);
    if (!friendship || friendship.status !== "PENDING" || friendship.addresseeId !== me.id) {
      throw new NotFoundError(REQUEST_NOT_FOUND);
    }
    const requester = await this.users.findActiveById(friendship.requesterId);
    if (!requester) throw new NotFoundError(REQUEST_NOT_FOUND);
    return { friendship, requester };
  }
}

function present(friendship: Friendship, other: UserAccount): FriendshipListItem {
  return presentListed({ friendship, user: toPublicProfile(other) });
}

function presentListed({ friendship, user }: FriendshipWithUser): FriendshipListItem {
  return {
    friendshipId: friendship.id,
    status: friendship.status,
    requestedAt: friendship.requestedAt.toISOString(),
    ...(friendship.respondedAt ? { respondedAt: friendship.respondedAt.toISOString() } : {}),
    user,
  };
}
