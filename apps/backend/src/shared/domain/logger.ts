// Journalisation minimale dont ont besoin les cas d'usage (échecs best-effort non bloquants).
// Le Logger de Nest satisfait cette interface structurellement.
export interface AppLogger {
  warn(message: string): void;
}
