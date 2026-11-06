import { currentModerator } from "@/lib/auth";
import { avatarQuery } from "@/lib/format";

// Rendu des avatars dessinés (ADR-028) : relais vers GET /avatars/avataaars.png du backend, pour
// que le navigateur du modérateur n'ait pas à joindre le backend directement.
export async function GET(request: Request): Promise<Response> {
  if (!(await currentModerator())) return new Response(null, { status: 401 });
  const params = Object.fromEntries(new URL(request.url).searchParams);
  // Bornes du backend (AVATAR_SIZE) : 64 à 512 px.
  const size = Math.min(512, Math.max(64, Math.round(Number(params.size)) || 128));
  const query = avatarQuery(params, size);
  const baseUrl = process.env.BACKEND_URL;
  if (!query || !baseUrl) return new Response(null, { status: 400 });
  try {
    const upstream = await fetch(`${baseUrl.replace(/\/$/, "")}/avatars/avataaars.png?${query}`);
    if (!upstream.ok) return new Response(null, { status: 502 });
    return new Response(upstream.body, {
      headers: { "Content-Type": "image/png", "Cache-Control": "private, max-age=86400" },
    });
  } catch {
    return new Response(null, { status: 503 });
  }
}
