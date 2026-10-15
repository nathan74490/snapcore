import { CanActivate, ExecutionContext, Inject, Injectable } from "@nestjs/common";
import { Reflector } from "@nestjs/core";
import { UnauthenticatedError } from "../../../../shared/domain/errors";
import { IDENTITY_PROVIDER, type IdentityProvider } from "../../application/ports/identity-provider.port";
import { ResolveCurrentUser } from "../../application/use-cases/resolve-current-user";
import { AUTH_LEVEL, type AuthenticatedRequest } from "./auth.decorators";

// Garde globale : toute route exige un ID token Firebase (ADR-015) et un compte actif,
// sauf mention contraire (@Public, @IdentityOnly).
@Injectable()
export class AuthGuard implements CanActivate {
  constructor(
    private readonly reflector: Reflector,
    @Inject(IDENTITY_PROVIDER) private readonly identity: IdentityProvider,
    private readonly resolveCurrentUser: ResolveCurrentUser,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const level = this.reflector.getAllAndOverride<string>(AUTH_LEVEL, [context.getHandler(), context.getClass()]);
    if (level === "public") return true;

    const request = context.switchToHttp().getRequest<AuthenticatedRequest>();
    const idToken = extractBearerToken(request.headers.authorization);
    if (!idToken) throw new UnauthenticatedError("ID token manquant");
    request.idToken = idToken;

    // Vérification de signature seule sur les requêtes courantes : c'est l'absence de ligne User
    // active (supprimée ou deletionRequestedAt renseigné) qui coupe l'accès d'un compte supprimé.
    const { firebaseUid } = await this.identity.verifyIdToken(idToken, { checkRevoked: false });
    if (level === "identity") return true;

    request.currentUser = await this.resolveCurrentUser.execute(firebaseUid);
    return true;
  }
}

function extractBearerToken(header: string | string[] | undefined): string | null {
  if (typeof header !== "string") return null;
  const [scheme, token] = header.split(" ");
  return scheme === "Bearer" && token ? token : null;
}
