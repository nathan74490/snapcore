import { Logger } from "@nestjs/common";
import type { OpsAlerter } from "../application/ports";

// Alerte en niveau ERROR dans les logs. Le backoffice liste en plus les comptes dont la suppression
// est bloquée depuis plus de 24 h, sans notification active en V1 (ADR-019).
export class LoggingOpsAlerter implements OpsAlerter {
  private readonly logger = new Logger("OpsAlert");

  alert(message: string): void {
    this.logger.error(`ALERTE : ${message}`);
  }
}
