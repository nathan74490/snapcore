import type { MessageContentType, PublicUserProfile } from "@snapcore/types";
import type { Group, GroupMember, GroupMessage, GroupMessageOpening } from "../domain/group";

export const GROUP_REPOSITORY = Symbol("GroupRepository");
export const GROUP_NOTIFIER = Symbol("GroupNotifier");
export const GROUP_MESSAGE_PURGE_SCHEDULER = Symbol("GroupMessagePurgeScheduler");

/** Membre actuel dont le compte est actif (ni en suppression ni suspendu, ADR-019, ADR-021). */
export interface ActiveGroupMember extends GroupMember {
  profile: PublicUserProfile;
}

export type AddMembersResult =
  | { kind: "added"; userIds: string[] } // nouveaux membres réellement ajoutés (déjà membres ignorés)
  | { kind: "full" } // la limite serait dépassée : rien n'est ajouté
  | { kind: "missing" }; // groupe supprimé ou auteur de l'ajout plus membre entre-temps

export interface GroupRepository {
  // --- Groupes et membres ---

  /** Dans une transaction : le groupe et ses membres, tous arrivés à `group.createdAt`. */
  create(group: Group, memberIds: string[]): Promise<void>;
  findById(groupId: string): Promise<Group | null>;
  /** Appartenance actuelle (null si `userId` n'est pas membre ou si le groupe n'existe pas). */
  findMember(groupId: string, userId: string): Promise<GroupMember | null>;
  /** Groupes dont `userId` est membre, avec son appartenance. */
  listMembershipsOf(userId: string): Promise<Array<{ group: Group; member: GroupMember }>>;
  /**
   * Membres actuels aux comptes actifs de chaque groupe, par ordre d'arrivée (une requête pour toute la
   * liste). Un compte suspendu ou en suppression est traité comme inexistant.
   */
  listActiveMembers(groupIds: string[]): Promise<Map<string, ActiveGroupMember[]>>;
  /** Anciens membres (comptes actifs) auteurs d'au moins un message encore présent dans chaque groupe. */
  listFormerSenders(groupIds: string[]): Promise<Map<string, PublicUserProfile[]>>;

  rename(groupId: string, name: string | null): Promise<Group | null>;
  /**
   * Dans une transaction, sous verrou de la ligne Group (deux ajouts concurrents ne dépassent pas la
   * limite) : vérifie que `byUserId` est toujours membre, ignore ceux qui le sont déjà, puis ajoute les
   * autres si le total reste <= `maxMembers`.
   */
  addMembers(groupId: string, byUserId: string, userIds: string[], joinedAt: Date, maxMembers: number): Promise<AddMembersResult>;
  /** @returns si la ligne existait, et le nombre de membres restants (tous comptes confondus). */
  removeMember(groupId: string, userId: string): Promise<{ removed: boolean; remaining: number }>;
  /** Supprime le groupe (cascade messages et ouvertures) seulement s'il n'a plus aucun membre. */
  deleteIfEmpty(groupId: string): Promise<boolean>;
  /** mediaKey encore présentes des messages du groupe. */
  listMediaKeys(groupId: string): Promise<string[]>;
  markRead(groupId: string, userId: string, at: Date): Promise<void>;

  // --- Messages ---

  /** L'identifiant est fourni par l'appelant : la clé R2 d'un snap en dépend (group-messages/<id>). */
  createMessage(message: GroupMessage): Promise<GroupMessage>;
  findMessageById(id: string): Promise<GroupMessage | null>;
  /** Messages envoyés depuis `since` (inclus), du plus récent au plus ancien. */
  listMessages(groupId: string, options: { since: Date; before?: Date; limit: number }): Promise<GroupMessage[]>;
  /** Dernier message de chaque groupe envoyé depuis l'arrivée de `userId` (absent si aucun). */
  findLastMessages(userId: string, groupIds: string[]): Promise<Map<string, GroupMessage>>;
  /**
   * Plus ancien snap d'un autre membre de chaque groupe, média présent, envoyé depuis l'arrivée de
   * `userId` et qu'il n'a pas ouvert (absent sinon).
   */
  findOldestUnopenedSnaps(userId: string, groupIds: string[]): Promise<Map<string, GroupMessage>>;
  /** Groupes où un autre membre a envoyé un message (chat ou snap) après le `lastReadAt` de `userId`. */
  findGroupsWithUnread(userId: string, groupIds: string[]): Promise<Set<string>>;
  /** Dernier message d'un autre membre non encore lu par `userId` (null s'il n'y en a pas). */
  findLastUnreadFromOthers(groupId: string, userId: string): Promise<GroupMessage | null>;

  listOpenings(messageIds: string[]): Promise<GroupMessageOpening[]>;
  findOpening(messageId: string, userId: string): Promise<GroupMessageOpening | null>;
  /**
   * Première ouverture, atomique (`INSERT ... ON CONFLICT DO NOTHING`).
   * @returns true si CET appel a enregistré l'ouverture, false si le membre l'avait déjà ouvert.
   */
  recordOpening(messageId: string, userId: string, at: Date): Promise<boolean>;

  /** Snaps du groupe dont le média existe encore (réévaluation de la purge au départ d'un membre). */
  listSnapsWithMedia(groupId: string): Promise<GroupMessage[]>;
  setScheduledPurgeAt(id: string, at: Date): Promise<void>;
  /** Vide `mediaKey` après suppression du média : la ligne n'est jamais supprimée par la purge. */
  clearMedia(id: string): Promise<void>;
  /** Snaps dont la purge est échue mais dont le média existe encore (balayage de rattrapage, ADR-017). */
  findOverdueSnapIds(now: Date, limit: number): Promise<string[]>;
}

export interface GroupMessageReceived {
  groupId: string;
  messageId: string;
  senderId: string;
  /** Prénom de l'expéditeur (pseudo à défaut), pour le texte du push. */
  senderName: string;
  groupName: string | null;
  contentType: MessageContentType;
}

// Temps réel (Socket.io) ou push (FCM) selon la présence de chaque membre (ADR-006, ADR-018).
// Best-effort : le client resynchronise via l'API REST.
export interface GroupNotifier {
  messageReceived(recipientIds: string[], event: GroupMessageReceived): Promise<void>;
  /** Nom ou membres modifiés (création, renommage, ajout, départ). */
  updated(memberIds: string[], groupId: string): Promise<void>;
  typing(recipientIds: string[], groupId: string, typistId: string): Promise<void>;
  /** `viewerId` a lu le chat ou ouvert le snap `messageId`. */
  messageSeen(recipientIds: string[], groupId: string, messageId: string, viewerId: string): Promise<void>;
}
