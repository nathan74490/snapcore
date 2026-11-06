"use server";

import { revalidatePath } from "next/cache";
import { AdminApiError, adminApi } from "@/lib/admin-api";
import { requireModerator } from "@/lib/auth";

// Contrôleur : actions de modération. Chaque action vérifie la session du modérateur (une action
// serveur est un point d'entrée HTTP à part entière), puis délègue à l'API admin du backend, qui
// porte la logique et ses effets de bord : R2, jobs BullMQ, temps réel (ADR-021).

export interface ActionResult {
  ok: boolean;
  message: string;
}

async function run(action: () => Promise<void>, success: string, paths: () => string[]): Promise<ActionResult> {
  let revalidate: string[];
  try {
    revalidate = paths();
    await action();
  } catch (error) {
    return { ok: false, message: error instanceof AdminApiError ? error.message : "Action impossible" };
  }
  revalidate.forEach((path) => revalidatePath(path));
  return { ok: true, message: success };
}

function field(form: FormData, name: string): string {
  const value = form.get(name);
  return typeof value === "string" ? value : "";
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// Les identifiants sont insérés dans l'URL de l'API admin : on refuse tout ce qui n'est pas un UUID,
// sinon une valeur forgée ("../reports/…") pourrait viser une autre route admin.
function idField(form: FormData, name: string): string {
  const value = field(form, name);
  if (!UUID.test(value)) throw new AdminApiError(400, "Identifiant invalide");
  return value;
}

// Chemin de retour limité aux pages internes du backoffice.
function returnPath(form: FormData): string {
  const value = field(form, "returnTo");
  return /^\/(users|reports|messages|stories|groups)(\/[0-9a-f-]{36})?$/.test(value) ? value : "/reports";
}

export async function suspendUser(_prev: ActionResult | null, form: FormData): Promise<ActionResult> {
  const moderator = await requireModerator();
  return run(
    () => adminApi("POST", `/users/${idField(form, "userId")}/suspend`, { moderatorId: moderator.id, reason: field(form, "reason") }),
    "Compte suspendu",
    () => [`/users/${idField(form, "userId")}`, "/reports"],
  );
}

export async function reactivateUser(_prev: ActionResult | null, form: FormData): Promise<ActionResult> {
  const moderator = await requireModerator();
  return run(
    () => adminApi("POST", `/users/${idField(form, "userId")}/reactivate`, { moderatorId: moderator.id }),
    "Compte réactivé",
    () => [`/users/${idField(form, "userId")}`],
  );
}

export async function redactMessage(_prev: ActionResult | null, form: FormData): Promise<ActionResult> {
  const moderator = await requireModerator();
  return run(
    () => adminApi("POST", `/messages/${idField(form, "messageId")}/redact`, { moderatorId: moderator.id, reason: field(form, "reason") }),
    "Message retiré",
    () => [returnPath(form)],
  );
}

export async function deleteStoryItem(_prev: ActionResult | null, form: FormData): Promise<ActionResult> {
  const moderator = await requireModerator();
  return run(
    () => adminApi("DELETE", `/stories/${idField(form, "storyItemId")}`, { moderatorId: moderator.id, reason: field(form, "reason") }),
    "Élément de story supprimé",
    () => [returnPath(form)],
  );
}

export async function closeReport(_prev: ActionResult | null, form: FormData): Promise<ActionResult> {
  const moderator = await requireModerator();
  const status = field(form, "status") === "DISMISSED" ? "DISMISSED" : "RESOLVED";
  return run(
    () => adminApi("POST", `/reports/${idField(form, "reportId")}/close`, { moderatorId: moderator.id, status }),
    status === "RESOLVED" ? "Signalement résolu" : "Signalement classé sans suite",
    () => ["/reports", `/reports/${idField(form, "reportId")}`, "/"],
  );
}
