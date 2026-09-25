import type { UserAccount } from "../../domain/user";

export const USER_REPOSITORY = Symbol("UserRepository");

export interface NewUser {
  username: string;
  firebaseUid: string;
  email: string | null;
  phoneNumber: string | null;
}

export interface ProfilePatch {
  username?: string;
  phoneNumber?: string | null;
  email?: string | null;
}

// Filtre de suppression centralisé (ADR-019) : toutes les méthodes `findActive*`
// ignorent un compte dont `deletionRequestedAt` est renseigné. Seules les méthodes
// explicitement nommées `...IncludingDeleted` le renvoient.
export interface UserRepository {
  findActiveById(id: string): Promise<UserAccount | null>;
  findActiveByFirebaseUid(firebaseUid: string): Promise<UserAccount | null>;
  findActiveByUsername(username: string): Promise<UserAccount | null>;
  findByFirebaseUidIncludingDeleted(firebaseUid: string): Promise<UserAccount | null>;

  /** @throws UniqueViolationError si username, firebaseUid, email ou phoneNumber est déjà pris. */
  create(user: NewUser): Promise<UserAccount>;
  /** @throws UniqueViolationError si username, email ou phoneNumber est déjà pris. */
  updateProfile(id: string, patch: ProfilePatch): Promise<UserAccount>;

  /**
   * Remplace la photo seulement si elle vaut toujours `expectedUrl` (null = pas de photo) :
   * deux changements concurrents ne peuvent pas s'écraser en laissant une image orpheline.
   * @returns le compte mis à jour, ou null si la photo a changé entre-temps.
   */
  replaceProfilePhoto(id: string, expectedUrl: string | null, newUrl: string | null): Promise<UserAccount | null>;
}
