import type { UserEvent } from "../domain/user-event";

export const PRESENCE_TRACKER = Symbol("PresenceTracker");
export const REALTIME_CHANNEL = Symbol("RealtimeChannel");
export const PUSH_SENDER = Symbol("PushSender");
export const DEVICE_TOKEN_REPOSITORY = Symbol("DeviceTokenRepository");

// Présence multi-instance partagée via Redis (ADR-018) : n'importe quelle instance sait si
// l'utilisateur est connecté, quelle que soit celle qui porte sa connexion Socket.io.
export interface PresenceTracker {
  /** Enregistre ou rafraîchit une connexion (heartbeat). */
  touch(userId: string, connectionId: string): Promise<void>;
  disconnect(userId: string, connectionId: string): Promise<void>;
  isOnline(userId: string): Promise<boolean>;
}

// Diffusion temps réel vers toutes les connexions de l'utilisateur, sur toutes les instances.
export interface RealtimeChannel {
  emitToUser(userId: string, event: UserEvent): Promise<void>;
  /** Ferme toutes les connexions de l'utilisateur, sur toutes les instances (suppression de compte). */
  disconnectUser(userId: string): Promise<void>;
}

export interface PushSender {
  /** @returns les tokens rejetés définitivement par FCM (app désinstallée, token invalide). */
  send(tokens: string[], data: Record<string, string>): Promise<{ invalidTokens: string[] }>;
}

export interface DeviceTokenRepository {
  /** Upsert sur `token` : réaffecte l'appareil si un autre compte s'y connecte (ADR-018). */
  register(userId: string, token: string): Promise<void>;
  /** Ne supprime que si le token appartient bien à `userId`. */
  unregister(userId: string, token: string): Promise<boolean>;
  listTokens(userId: string): Promise<string[]>;
  deleteTokens(tokens: string[]): Promise<void>;
}
