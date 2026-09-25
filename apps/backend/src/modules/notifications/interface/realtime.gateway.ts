import { Inject, Logger } from "@nestjs/common";
import {
  type OnGatewayConnection,
  type OnGatewayDisconnect,
  WebSocketGateway,
  WebSocketServer,
} from "@nestjs/websockets";
import type { Server, Socket } from "socket.io";
import { UnauthenticatedError } from "../../../shared/domain/errors";
import { IDENTITY_PROVIDER, type IdentityProvider } from "../../identity/application/ports/identity-provider.port";
import { ResolveCurrentUser } from "../../identity/application/use-cases/resolve-current-user";
import { PRESENCE_TRACKER, type PresenceTracker, type RealtimeChannel } from "../application/ports";
import type { UserEvent } from "../domain/user-event";
import { HEARTBEAT_INTERVAL_MS } from "../infrastructure/redis-presence.tracker";

const userRoom = (userId: string) => `user:${userId}`;

// Passerelle temps réel (ADR-006). Le client se connecte avec `auth: { token: <ID token Firebase> }`,
// reçoit "ready" une fois authentifié, puis les UserEvent sous leur type comme nom d'événement.
// Best-effort : à chaque (re)connexion, le client doit resynchroniser son état via l'API REST.
@WebSocketGateway({ cors: { origin: true } })
export class RealtimeGateway implements OnGatewayConnection, OnGatewayDisconnect, RealtimeChannel {
  @WebSocketServer() private server: Server;
  private readonly logger = new Logger(RealtimeGateway.name);
  private readonly heartbeats = new Map<string, NodeJS.Timeout>();

  constructor(
    @Inject(IDENTITY_PROVIDER) private readonly identity: IdentityProvider,
    private readonly resolveCurrentUser: ResolveCurrentUser,
    @Inject(PRESENCE_TRACKER) private readonly presence: PresenceTracker,
  ) {}

  async handleConnection(socket: Socket): Promise<void> {
    try {
      const token: unknown = socket.handshake.auth?.token;
      if (typeof token !== "string") throw new UnauthenticatedError("ID token manquant");
      const { firebaseUid } = await this.identity.verifyIdToken(token, { checkRevoked: false });
      // Même règle que l'API REST : compte actif requis (ADR-015, ADR-019).
      const user = await this.resolveCurrentUser.execute(firebaseUid);

      socket.data.userId = user.id;
      await socket.join(userRoom(user.id));
      await this.presence.touch(user.id, socket.id);
      // Déconnecté pendant l'authentification : handleDisconnect est déjà passé, on nettoie ici.
      if (socket.disconnected) {
        await this.presence.disconnect(user.id, socket.id);
        return;
      }
      this.heartbeats.set(
        socket.id,
        setInterval(() => {
          this.presence.touch(user.id, socket.id).catch((error: Error) =>
            this.logger.warn(`Heartbeat de présence en échec : ${error.message}`),
          );
        }, HEARTBEAT_INTERVAL_MS),
      );
      socket.emit("ready", { userId: user.id });
    } catch (error) {
      socket.emit("auth_error", { message: (error as Error).message });
      socket.disconnect(true);
    }
  }

  async handleDisconnect(socket: Socket): Promise<void> {
    clearInterval(this.heartbeats.get(socket.id));
    this.heartbeats.delete(socket.id);
    const userId: string | undefined = socket.data.userId;
    if (userId) await this.presence.disconnect(userId, socket.id).catch(() => undefined);
  }

  async emitToUser(userId: string, event: UserEvent): Promise<void> {
    // Via l'adaptateur Redis, atteint les connexions de l'utilisateur sur toutes les instances.
    this.server.to(userRoom(userId)).emit(event.type, event);
  }
}
