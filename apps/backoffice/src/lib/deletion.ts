// Suppression de compte bloquée (ADR-019) : la dernière étape du job supprime la ligne User. Un compte
// dont la suppression a été demandée il y a plus de 24 h et qui existe encore est donc un job qui échoue
// depuis plus de 24 h, le même seuil que l'alerte du backend.
export const STUCK_DELETION_AFTER_MS = 24 * 60 * 60 * 1000;

/** Date avant laquelle une demande de suppression encore présente est considérée comme bloquée. */
export function stuckDeletionThreshold(now: Date): Date {
  return new Date(now.getTime() - STUCK_DELETION_AFTER_MS);
}
