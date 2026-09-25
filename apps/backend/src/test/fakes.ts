import type { Clock } from "../shared/domain/clock";
import { UnauthenticatedError } from "../shared/domain/errors";
import type { FriendshipNotifier } from "../modules/friendships/application/ports/friendship-notifier.port";
import type {
  IdentityProvider,
  VerifiedIdentity,
} from "../modules/identity/application/ports/identity-provider.port";

export class FixedClock implements Clock {
  constructor(public current = new Date("2026-09-25T10:00:00Z")) {}
  now(): Date {
    return new Date(this.current);
  }
}

// Simule Firebase : un token est de la forme "token-<uid>". Un compte supprimé côté Firebase
// garde un token à la signature valide, mais échoue dès que checkRevoked est demandé (ADR-015).
export class FakeIdentityProvider implements IdentityProvider {
  readonly accounts = new Map<string, { email: string | null }>();
  readonly deletedAccounts = new Set<string>();
  readonly calls: Array<{ checkRevoked: boolean }> = [];

  addAccount(firebaseUid: string, email: string | null = null): string {
    this.accounts.set(firebaseUid, { email });
    return `token-${firebaseUid}`;
  }

  // Le corps s'exécute immédiatement : les tests peuvent l'appeler sans await.
  async deleteAccount(firebaseUid: string): Promise<void> {
    this.deletedAccounts.add(firebaseUid);
  }

  async verifyIdToken(idToken: string, options: { checkRevoked: boolean }): Promise<VerifiedIdentity> {
    this.calls.push(options);
    const firebaseUid = idToken.startsWith("token-") ? idToken.slice("token-".length) : null;
    const account = firebaseUid ? this.accounts.get(firebaseUid) : undefined;
    if (!firebaseUid || !account) throw new UnauthenticatedError("Session invalide ou expirée");
    if (options.checkRevoked && this.deletedAccounts.has(firebaseUid)) {
      throw new UnauthenticatedError("Session invalide ou expirée");
    }
    return { firebaseUid, email: account.email };
  }

  async getCurrentEmail(firebaseUid: string): Promise<string | null> {
    return this.accounts.get(firebaseUid)?.email ?? null;
  }
}

export class RecordingFriendshipNotifier implements FriendshipNotifier {
  readonly events: Array<{ type: "received" | "accepted"; to: string; friendshipId: string }> = [];

  async friendRequestReceived(addresseeId: string, friendshipId: string): Promise<void> {
    this.events.push({ type: "received", to: addresseeId, friendshipId });
  }

  async friendRequestAccepted(requesterId: string, friendshipId: string): Promise<void> {
    this.events.push({ type: "accepted", to: requesterId, friendshipId });
  }
}
