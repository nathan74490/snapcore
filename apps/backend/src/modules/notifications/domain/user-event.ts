// Événements poussés à un utilisateur. Contenu minimal (type + identifiants) : le client va
// chercher le détail via l'API REST, qui reste la source de vérité (ADR-006, ADR-018).
export type UserEvent =
  | { type: "friend_request.received"; friendshipId: string }
  | { type: "friend_request.accepted"; friendshipId: string }
  | { type: "message.received"; messageId: string }
  | { type: "snap.opened"; messageId: string }
  | { type: "texts.read"; readerId: string }
  | { type: "snap.retained"; messageId: string }
  | { type: "snap.expired"; messageId: string }
  // Contenu retiré par un modérateur (ADR-021) : mise à jour d'affichage, jamais de push.
  | { type: "message.moderated"; messageId: string };

export type UserEventType = UserEvent["type"];

// Événements qui justifient de réveiller le téléphone par un push quand l'utilisateur n'est pas
// connecté : demandes d'ami, messages reçus (ADR-018) et Snap conservé (ADR-009).
// Les autres ne sont que des mises à jour d'affichage, rattrapées à la resynchronisation.
const PUSH_WORTHY: ReadonlySet<UserEventType> = new Set([
  "friend_request.received",
  "friend_request.accepted",
  "message.received",
  "snap.retained",
]);

export function deservesPush(event: UserEvent): boolean {
  return PUSH_WORTHY.has(event.type);
}

// Charge utile d'un push FCM : uniquement des chaînes (contrainte des messages "data").
export function toPushData(event: UserEvent): Record<string, string> {
  return Object.fromEntries(Object.entries(event).map(([key, value]) => [key, String(value)]));
}
