import type { MessageContentType } from "@snapcore/types";

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
  // Un ami écrit dans la conversation : simple indication d'affichage, jamais de push (ADR-027).
  | { type: "typing"; userId: string }
  // Contenu retiré par un modérateur (ADR-021) : mise à jour d'affichage, jamais de push.
  | { type: "message.moderated"; messageId: string }
  // Groupes (ADR-032), un événement par membre concerné. Seul le message reçu mérite un push : il porte
  // le prénom de l'expéditeur et le nom du groupe (absent sans nom) pour le texte de la notification.
  | {
      type: "group.message.received";
      groupId: string;
      messageId: string;
      senderId: string;
      senderName: string;
      groupName?: string;
      contentType: MessageContentType;
    }
  | { type: "group.updated"; groupId: string } // nom ou membres modifiés
  | { type: "group.typing"; groupId: string; userId: string }
  | { type: "group.message.seen"; groupId: string; messageId: string; userId: string };

export type UserEventType = UserEvent["type"];

// Événements qui justifient de réveiller le téléphone par un push quand l'utilisateur n'est pas
// connecté : demandes d'ami, messages reçus, à deux (ADR-018) ou en groupe (ADR-032), et Snap conservé (ADR-009).
// Les autres ne sont que des mises à jour d'affichage, rattrapées à la resynchronisation.
const PUSH_WORTHY: ReadonlySet<UserEventType> = new Set([
  "friend_request.received",
  "friend_request.accepted",
  "message.received",
  "snap.retained",
  "group.message.received",
]);

export function deservesPush(event: UserEvent): boolean {
  return PUSH_WORTHY.has(event.type);
}

// Charge utile d'un push FCM : uniquement des chaînes (contrainte des messages "data") ; un champ
// facultatif absent n'est pas envoyé.
export function toPushData(event: UserEvent): Record<string, string> {
  return Object.fromEntries(
    Object.entries(event)
      .filter(([, value]) => value !== undefined)
      .map(([key, value]) => [key, String(value)]),
  );
}
