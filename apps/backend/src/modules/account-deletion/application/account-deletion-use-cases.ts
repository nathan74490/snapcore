import type { Clock } from "../../../shared/domain/clock";
import type { AppLogger } from "../../../shared/domain/logger";
import type { IdentityProvider } from "../../identity/application/ports/identity-provider.port";
import type { LocationStore } from "../../location/application/ports";
import type { UserAccount } from "../../identity/domain/user";
import type { ObjectStorage, StoredObject } from "../../media/application/ports/object-storage.port";
import type { PublicMediaUrls } from "../../media/domain/public-media-urls";
import type { RealtimeChannel } from "../../notifications/application/ports";
import type {
  AccountDeletionRepository,
  CollectedMedia,
  DeletionJobContext,
  DeletionJobQueue,
  SubscriptionCanceller,
} from "./ports";

const SWEEP_BATCH_SIZE = 100;

export type DeletionOutcome =
  | { kind: "deleted"; removedObjects: number }
  | { kind: "aborted"; reason: "not-requested" };

// Suppression de compte (ADR-019) : requête synchrone qui bloque le compte, puis job
// asynchrone, idempotent et rejouable : abonnement → Firebase → PostgreSQL → R2.
export class AccountDeletionUseCases {
  constructor(
    private readonly accounts: AccountDeletionRepository,
    private readonly queue: DeletionJobQueue,
    private readonly identity: IdentityProvider,
    private readonly subscriptions: SubscriptionCanceller,
    private readonly storage: ObjectStorage,
    private readonly urls: PublicMediaUrls,
    private readonly realtime: RealtimeChannel,
    private readonly clock: Clock,
    private readonly logger: AppLogger,
    private readonly locations: LocationStore,
  ) {}

  // Étape 1 : dès cet instant, le compte ne peut plus se connecter ni agir, et il est traité
  // comme inexistant par les autres utilisateurs (filtre centralisé de UserRepository).
  async request(me: UserAccount): Promise<void> {
    await this.accounts.markDeletionRequested(me.id, this.clock.now());
    // Si l'ajout du job échoue, le balayage de rattrapage le reprendra : le compte est déjà bloqué.
    await this.queue.enqueue(me.id, me.firebaseUid);
    try {
      await this.realtime.disconnectUser(me.id);
    } catch (error) {
      this.logger.warn(`Connexions temps réel de ${me.id} non fermées : ${(error as Error).message}`);
    }
    await this.forgetLocation(me.id);
  }

  // Étape 2 : exécution du job. Toute erreur fait échouer la tentative, qui sera rejouée.
  async runJob(context: DeletionJobContext): Promise<DeletionOutcome> {
    const { userId, firebaseUid } = context.data;
    const state = await this.accounts.getDeletionState(userId);

    // Garde-fou : jamais de suppression d'un compte dont la suppression n'a pas été demandée.
    if (state === "active") return { kind: "aborted", reason: "not-requested" };

    let objects = context.data.collected ?? [];
    if (state === "deletion-requested") {
      // (a) et (b) : rien d'irréversible côté données tant qu'ils n'ont pas réussi.
      await this.subscriptions.cancelFor(userId);
      await this.identity.deleteAccount(firebaseUid);
      // (c) : relevé des médias et suppression de la ligne dans la même transaction ; les clés
      // sont persistées dans le job AVANT la suppression, à l'intérieur de la transaction.
      await this.accounts.deleteUserCollectingMedia(userId, async (media) => {
        objects = this.toStoredObjects(media);
        await context.persistCollected(objects);
      });
    }
    // state "absent" : l'étape (c) a déjà réussi lors d'une tentative précédente.

    // (d) : suppression idempotente des objets R2 relevés.
    for (const object of objects) await this.storage.delete(object);
    // Rejouée ici au cas où l'effacement de la demande aurait échoué.
    await this.forgetLocation(userId);
    return { kind: "deleted", removedObjects: objects.length };
  }

  // Rattrapage : comptes marqués en suppression dont le job aurait été perdu.
  async sweepPending(): Promise<{ requeued: number }> {
    const pending = await this.accounts.listPendingDeletions(SWEEP_BATCH_SIZE);
    for (const { userId, firebaseUid } of pending) await this.queue.enqueue(userId, firebaseUid);
    return { requeued: pending.length };
  }

  // Position de la Snap Map (Redis, ADR-031) : de toute façon invisible d'un compte inactif et expirée
  // sous 8 h, son effacement ne bloque jamais la suppression.
  private async forgetLocation(userId: string): Promise<void> {
    try {
      await this.locations.remove(userId);
    } catch (error) {
      this.logger.warn(`Position de ${userId} non effacée : ${(error as Error).message}`);
    }
  }

  private toStoredObjects(media: CollectedMedia): StoredObject[] {
    const objects: StoredObject[] = media.privateKeys.map((key) => ({ area: "private", key }));
    // Clé de la photo déduite de son URL publique, sans champ dédié (ADR-016).
    const profileKey = media.profilePhotoUrl ? this.urls.keyFromUrl(media.profilePhotoUrl) : null;
    if (profileKey) objects.push({ area: "public", key: profileKey });
    return objects;
  }
}
