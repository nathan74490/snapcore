import { workersEnabled } from "./workers";

const config = (values: Record<string, string>) => ({ get: <T>(key: string) => values[key] as T });

describe("workersEnabled (BUG-006)", () => {
  it("actifs par défaut, désactivables par WORKERS_ENABLED=false", () => {
    expect(workersEnabled(config({}))).toBe(true);
    expect(workersEnabled(config({ WORKERS_ENABLED: "false" }))).toBe(false);
  });

  it("lit encore l'ancien PURGE_WORKER_ENABLED, mais WORKERS_ENABLED l'emporte", () => {
    expect(workersEnabled(config({ PURGE_WORKER_ENABLED: "false" }))).toBe(false);
    expect(workersEnabled(config({ PURGE_WORKER_ENABLED: "false", WORKERS_ENABLED: "true" }))).toBe(true);
  });
});
