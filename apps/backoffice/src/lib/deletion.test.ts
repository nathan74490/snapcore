import assert from "node:assert/strict";
import { test } from "node:test";
import { stuckDeletionThreshold } from "./deletion.ts";

test("une suppression est bloquée au-delà de 24 h, comme l'alerte du backend (ADR-019)", () => {
  const now = new Date("2026-10-15T12:00:00Z");
  assert.deepEqual(stuckDeletionThreshold(now), new Date("2026-10-14T12:00:00Z"));
});
