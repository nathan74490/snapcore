import type { Message } from "../../domain/message";

export const MESSAGE_REPOSITORY = Symbol("MessageRepository");

export type NewMessage = Omit<Message, "openedAt" | "retainedNotifiedAt">;

export interface MessageRepository {
  /** L'identifiant est fourni par l'appelant : la clé R2 d'un Snap en dépend (messages/<id>). */
  create(message: NewMessage): Promise<Message>;
  findById(id: string): Promise<Message | null>;

  /**
   * Marque la 1re ouverture de façon atomique
   * (`UPDATE ... SET openedAt = at WHERE id = ? AND openedAt IS NULL`, ADR-009).
   * @returns true si CET appel a effectué l'ouverture, false si le message était déjà ouvert.
   */
  markOpenedIfUnopened(id: string, at: Date): Promise<boolean>;

  setScheduledPurgeAt(id: string, at: Date): Promise<void>;

  /** Marque comme lus les Chats texte non lus reçus de `senderId`. @returns le nombre marqué. */
  markTextsRead(recipientId: string, senderId: string, at: Date): Promise<number>;

  /** Messages échangés entre deux utilisateurs, du plus récent au plus ancien. */
  listBetween(userA: string, userB: string, options: { before?: Date; limit: number }): Promise<Message[]>;

  /** Dernier message échangé avec chacun des `otherUserIds` (absent si aucun message). */
  findLastMessagesWith(userId: string, otherUserIds: string[]): Promise<Map<string, Message>>;

  /** Vide `mediaKey` après suppression du média : la ligne Message n'est jamais supprimée (ADR-017). */
  clearMedia(id: string): Promise<void>;

  /**
   * Renseigne `retainedNotifiedAt` s'il est encore vide (mise à jour conditionnelle).
   * @returns true si CET appel l'a renseigné : la notification "Snap conservé" ne part qu'une fois (ADR-009).
   */
  markRetainedNotified(id: string, at: Date): Promise<boolean>;

  /** Snaps dont la purge est échue mais dont le média existe encore (balayage de rattrapage, ADR-017). */
  findOverdueSnapIds(now: Date, limit: number): Promise<string[]>;
}
