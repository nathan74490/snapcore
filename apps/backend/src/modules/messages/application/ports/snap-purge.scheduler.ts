export const SNAP_PURGE_SCHEDULER = Symbol("SnapPurgeScheduler");

// Programmation de la purge d'un Snap (ADR-017). L'adaptateur BullMQ applique la recette
// de docs/impl/purge-bullmq.md : identifiant déterministe `purge-message-<id>`, jamais de
// "supprimer puis réajouter", opération choisie selon l'état du job.
// Best-effort vu des cas d'usage : scheduledPurgeAt en base reste la source de vérité, et
// le balayage de rattrapage retrouve tout Snap dont le job aurait été perdu.
export interface SnapPurgeScheduler {
  schedule(messageId: string, at: Date): Promise<void>;
  /** Rapproche l'échéance d'un job en attente (ouverture du Snap). */
  reschedule(messageId: string, at: Date): Promise<void>;
  /**
   * Rattrapage d'une purge en retard : relance le job s'il est en échec, le réajoute s'il a
   * disparu, ne fait rien s'il est en attente ou en cours. Ne purge jamais directement.
   */
  recover(messageId: string): Promise<"retried" | "re-added" | "untouched">;
  /**
   * Retire le job devenu inutile (contenu déjà traité par la modération, ADR-021). Sans effet s'il
   * n'existe pas ; laissé en place s'il est en cours d'exécution (le handler ne trouvera rien à faire).
   */
  cancel(targetId: string): Promise<void>;
}
