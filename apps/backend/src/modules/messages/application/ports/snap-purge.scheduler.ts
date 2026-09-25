export const SNAP_PURGE_SCHEDULER = Symbol("SnapPurgeScheduler");

// Programmation de la purge d'un Snap à une échéance donnée (ADR-017). L'implémentation
// BullMQ (identifiants déterministes, reprogrammation selon l'état du job) vit dans
// l'adaptateur : voir docs/impl/purge-bullmq.md.
// Best-effort vu d'ici : un job perdu est rattrapé par le balayage périodique sur scheduledPurgeAt.
export interface SnapPurgeScheduler {
  schedule(messageId: string, at: Date): Promise<void>;
  /** Rapproche l'échéance d'un job existant (ouverture du Snap). */
  reschedule(messageId: string, at: Date): Promise<void>;
}
