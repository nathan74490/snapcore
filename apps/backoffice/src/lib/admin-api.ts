import "server-only";

// Client de l'API admin du backend (ADR-021). Exécuté uniquement côté serveur Next.js : le
// secret n'est jamais envoyé au navigateur. L'identifiant du modérateur, issu de la session
// vérifiée, est transmis pour l'attribution de l'action.
export class AdminApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

export async function adminApi(method: "POST" | "DELETE", path: string, body: Record<string, unknown>): Promise<void> {
  const baseUrl = process.env.BACKEND_URL;
  const secret = process.env.ADMIN_API_SECRET;
  if (!baseUrl || !secret) throw new AdminApiError(500, "BACKEND_URL et ADMIN_API_SECRET doivent être configurés");

  let response: Response;
  try {
    response = await fetch(`${baseUrl.replace(/\/$/, "")}/admin${path}`, {
      method,
      headers: { "Content-Type": "application/json", "x-admin-secret": secret },
      body: JSON.stringify(body),
      cache: "no-store",
    });
  } catch {
    throw new AdminApiError(503, "Backend injoignable");
  }
  if (!response.ok) {
    const payload = (await response.json().catch(() => null)) as { message?: string | string[] } | null;
    const message = Array.isArray(payload?.message) ? payload.message.join(", ") : payload?.message;
    throw new AdminApiError(response.status, message ?? `Erreur du backend (${response.status})`);
  }
}
