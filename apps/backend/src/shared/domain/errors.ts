// Erreurs métier, indépendantes de tout framework (ADR-002).
// Traduites en codes HTTP uniquement par l'adaptateur REST (DomainExceptionFilter).
export abstract class DomainError extends Error {
  constructor(message: string) {
    super(message);
    this.name = new.target.name;
  }
}

export class NotFoundError extends DomainError {}
export class ConflictError extends DomainError {}
export class ForbiddenError extends DomainError {}
export class ValidationError extends DomainError {}
export class UnauthenticatedError extends DomainError {}
// La ressource a existé mais n'est plus disponible (ex. Snap purgé).
export class GoneError extends DomainError {}
// Le service n'est pas configuré sur ce serveur (ex. secret d'un service tiers absent).
export class UnavailableError extends DomainError {}

// Levée par un adaptateur de persistance quand une contrainte d'unicité est violée.
// `field` désigne le champ fautif (ex. "username", "email", "pair").
export class UniqueViolationError extends DomainError {
  constructor(readonly field: string) {
    super(`Contrainte d'unicité violée : ${field}`);
  }
}
