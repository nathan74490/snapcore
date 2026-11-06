import { ArgumentsHost, Catch, ExceptionFilter, HttpStatus } from "@nestjs/common";
import type { Response } from "express";
import {
  ConflictError,
  DomainError,
  ForbiddenError,
  GoneError,
  NotFoundError,
  UnauthenticatedError,
  UniqueViolationError,
  UnavailableError,
  ValidationError,
} from "../../domain/errors";

const STATUS_BY_ERROR: Array<[new (...args: never[]) => DomainError, HttpStatus]> = [
  [NotFoundError, HttpStatus.NOT_FOUND],
  [ConflictError, HttpStatus.CONFLICT],
  [UniqueViolationError, HttpStatus.CONFLICT],
  [ForbiddenError, HttpStatus.FORBIDDEN],
  [ValidationError, HttpStatus.BAD_REQUEST],
  [UnauthenticatedError, HttpStatus.UNAUTHORIZED],
  [GoneError, HttpStatus.GONE],
  [UnavailableError, HttpStatus.SERVICE_UNAVAILABLE],
];

@Catch(DomainError)
export class DomainExceptionFilter implements ExceptionFilter {
  catch(error: DomainError, host: ArgumentsHost): void {
    const response = host.switchToHttp().getResponse<Response>();
    const status =
      STATUS_BY_ERROR.find(([type]) => error instanceof type)?.[1] ?? HttpStatus.INTERNAL_SERVER_ERROR;
    response.status(status).json({ statusCode: status, error: error.name, message: error.message });
  }
}
