import type { AvatarOptions } from "@snapcore/types";
import type { UserAccount } from "../../domain/user";

export const USER_REPOSITORY = Symbol("UserRepository");

export interface NewUser {
  username: string;
  firstName: string;
  lastName: string | null;
  birthday: Date | null; // toujours renseignée par RegisterUser (ADR-029) ; null admis pour les jeux de test
  firebaseUid: string;
  email: string | null;
  phoneNumber: string | null;
}

export interface ProfilePatch {
  username?: string;
  firstName?: string;
  lastName?: string | null;
  phoneNumber?: string | null;
  email?: string | null;
}

// Filtre centralisé (ADR-019, ADR-021) : toutes les méthodes `findActive*` ignorent un compte en cours
// de suppression ou suspendu par la modération (ACTIVE_USER_WHERE). Seules les méthodes
// explicitement nommées `...IncludingDeleted` le renvoient.
export interface UserRepository {
  findActiveById(id: string): Promise<UserAccount | null>;
  findActiveByFirebaseUid(firebaseUid: string): Promise<UserAccount | null>;
  findActiveByUsername(username: string): Promise<UserAccount | null>;
  /** Pseudo déjà porté par une ligne, quel que soit l'état du compte (supprimé ou suspendu compris). */
  isUsernameTaken(username: string): Promise<boolean>;
  /**
   * Comptes actifs dont le numéro stocké se termine par l'un de ces suffixes de chiffres, tous de même
   * longueur (correspondance des contacts, ADR-029).
   */
  findActiveByPhoneNumberSuffixes(suffixes: string[]): Promise<UserAccount[]>;
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

  /** Remplace (ou retire, null) l'avatar dessiné, déjà validé (ADR-028). */
  updateAvatar(id: string, avatar: AvatarOptions | null): Promise<UserAccount>;
}
