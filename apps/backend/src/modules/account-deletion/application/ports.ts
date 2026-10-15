import type { StoredObject } from "../../media/application/ports/object-storage.port";

export const ACCOUNT_DELETION_REPOSITORY = Symbol("AccountDeletionRepository");
export const DELETION_JOB_QUEUE = Symbol("DeletionJobQueue");
export const SUBSCRIPTION_CANCELLER = Symbol("SubscriptionCanceller");
export const OPS_ALERTER = Symbol("OpsAlerter");

export type DeletionState = "absent" | "active" | "deletion-requested";

// Médias relevés sous verrou, juste avant la suppression de la ligne User (ADR-019).
export interface CollectedMedia {
  profilePhotoUrl: string | null;
  /** mediaKey des Messages envoyés ET reçus, et des StoryItem de l'utilisateur. */
  privateKeys: string[];
}

export interface AccountDeletionRepository {
  /** Renseigne deletionRequestedAt s'il est encore vide. @returns false si déjà demandé ou compte absent. */
  markDeletionRequested(userId: string, at: Date): Promise<boolean>;
  getDeletionState(userId: string): Promise<DeletionState>;

  /**
   * Dans UNE transaction : `SELECT ... FOR UPDATE` sur la ligne User, relevé des médias,
   * appel de `onCollected` (qui persiste les clés dans le job), PUIS suppression de la ligne
   * (cascade Message/Friendship/StoryItem/DeviceToken). Le verrou empêche qu'un Message soit
   * inséré entre le relevé et la suppression sans que sa clé ait été relevée.
   * @returns false si la ligne n'existait déjà plus.
   */
  deleteUserCollectingMedia(userId: string, onCollected: (media: CollectedMedia) => Promise<void>): Promise<boolean>;

  /** Comptes marqués en suppression dont la ligne existe encore (rattrapage). */
  listPendingDeletions(limit: number): Promise<Array<{ userId: string; firebaseUid: string }>>;
}

export interface DeletionJobData {
  userId: string;
  firebaseUid: string;
  /** Renseigné à l'étape (c), avant la suppression de la ligne : survit à un échec de l'étape (d). */
  collected?: StoredObject[];
}

export interface DeletionJobQueue {
  /** Identifiant déterministe `delete-account-<userId>` : un second ajout est sans effet. */
  enqueue(userId: string, firebaseUid: string): Promise<void>;
}

// Contexte d'exécution fourni par le worker : les données du job et le moyen de les compléter.
export interface DeletionJobContext {
  data: DeletionJobData;
  persistCollected(objects: StoredObject[]): Promise<void>;
}

// Annulation de l'abonnement SnapCore+ (RevenueCat/Stripe, ADR-007). Idempotente.
export interface SubscriptionCanceller {
  cancelFor(userId: string): Promise<void>;
}

// Alerte opérationnelle : un compte bloqué en suppression ne doit jamais passer inaperçu.
export interface OpsAlerter {
  alert(message: string): void;
}
