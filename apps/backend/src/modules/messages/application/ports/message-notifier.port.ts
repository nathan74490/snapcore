export const MESSAGE_NOTIFIER = Symbol("MessageNotifier");

// Notification temps réel (Socket.io) ou push (FCM) selon la présence du destinataire
// (ADR-006, ADR-018). Best-effort : le client resynchronise via l'API REST.
export interface MessageNotifier {
  messageReceived(recipientId: string, messageId: string): Promise<void>;
  /** Informe l'expéditeur que son Snap a été ouvert. */
  snapOpened(senderId: string, messageId: string): Promise<void>;
  /** Informe l'expéditeur que ses Chats texte ont été lus par `readerId`. */
  textsRead(senderId: string, readerId: string): Promise<void>;
  /** Le destinataire est abonné SnapCore+ : le Snap de l'expéditeur a été conservé (ADR-009). */
  snapRetained(senderId: string, messageId: string): Promise<void>;
  /** Le média a été purgé : l'icône passe au gris chez les deux participants (ADR-009). */
  snapExpired(participantIds: [string, string], messageId: string): Promise<void>;
}
