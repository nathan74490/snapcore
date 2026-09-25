import { createParamDecorator, ExecutionContext, SetMetadata } from "@nestjs/common";
import type { UserAccount } from "../../domain/user";

export const AUTH_LEVEL = "authLevel";

/** Route accessible sans ID token (ex. health check). */
export const Public = () => SetMetadata(AUTH_LEVEL, "public");

/** ID token valide requis, mais pas de ligne User (uniquement POST /auth/register, ADR-015). */
export const IdentityOnly = () => SetMetadata(AUTH_LEVEL, "identity");

export interface AuthenticatedRequest {
  headers: Record<string, string | string[] | undefined>;
  idToken?: string;
  currentUser?: UserAccount;
}

/** Compte actif résolu par AuthGuard (jamais un compte en cours de suppression, ADR-019). */
export const CurrentUser = createParamDecorator(
  (_data: unknown, context: ExecutionContext): UserAccount =>
    context.switchToHttp().getRequest<AuthenticatedRequest>().currentUser!,
);

export const IdToken = createParamDecorator(
  (_data: unknown, context: ExecutionContext): string =>
    context.switchToHttp().getRequest<AuthenticatedRequest>().idToken!,
);
