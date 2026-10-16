import type { ConfigService } from "@nestjs/config";

/**
 * Workers BullMQ de cette instance (purge des médias, suppression de compte) : désactivables ensemble
 * pour une instance qui ne sert que l'API. WORKERS_ENABLED remplace PURGE_WORKER_ENABLED, dont le nom
 * laissait croire qu'il ne concernait que la purge (BUG-006 d'ADR-023) ; l'ancien nom reste lu en secours.
 */
export function workersEnabled(config: Pick<ConfigService, "get">): boolean {
  const value = config.get<string>("WORKERS_ENABLED") ?? config.get<string>("PURGE_WORKER_ENABLED");
  return value !== "false";
}
