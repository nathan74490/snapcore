import Link from "next/link";
import { Pagination } from "@/components/pagination";
import { UserAvatar } from "@/components/user-avatar";
import { UserStatusBadge } from "@/components/user-status-badge";
import { SUBSCRIPTION_STATE_LABEL, formatDateTime, parsePage, subscriptionState } from "@/lib/format";
import { listUsers, type UserPlanFilter, type UserStatusFilter } from "@/lib/queries";

export const dynamic = "force-dynamic";

const STATUS: Record<UserStatusFilter, string> = { all: "Tous", active: "Actifs", suspended: "Suspendus", deleting: "Suppression en cours" };
const PLAN: Record<UserPlanFilter, string> = { all: "Toutes les offres", premium: "SnapCore+", free: "Gratuit" };

type Params = { q?: string; status?: string; plan?: string; page?: string };

// Annuaire des comptes, consultable sans signalement préalable (ADR-021) : recherche par pseudo,
// prénom, nom, email, téléphone ou identifiant, filtres par statut et par offre.
export default async function UsersPage({ searchParams }: { searchParams: Promise<Params> }) {
  const params = await searchParams;
  const q = params.q ?? "";
  const status = (params.status && params.status in STATUS ? params.status : "all") as UserStatusFilter;
  const plan = (params.plan && params.plan in PLAN ? params.plan : "all") as UserPlanFilter;
  const page = parsePage(params.page);
  const users = await listUsers({ q, status, plan }, page);
  const now = new Date();
  const link = (next: Partial<Params>) => {
    const search = new URLSearchParams();
    const merged = { q, status, plan, ...next };
    if (merged.q) search.set("q", merged.q);
    if (merged.status !== "all") search.set("status", merged.status);
    if (merged.plan !== "all") search.set("plan", merged.plan);
    const query = search.toString();
    return query ? `/users?${query}` : "/users";
  };

  return (
    <>
      <header className="pagehead">
        <h1>Utilisateurs</h1>
        <span className="muted">{users.total.toLocaleString("fr-FR")} compte(s)</span>
      </header>
      <form className="toolbar" action="/users">
        <input name="q" defaultValue={q} placeholder="Pseudo, prénom, nom, email, téléphone ou identifiant" autoFocus />
        {status !== "all" && <input type="hidden" name="status" value={status} />}
        {plan !== "all" && <input type="hidden" name="plan" value={plan} />}
        <button className="primary" type="submit">
          Rechercher
        </button>
      </form>
      <div className="chips">
        {(Object.keys(STATUS) as UserStatusFilter[]).map((s) => (
          <Link key={s} href={link({ status: s })} className={s === status ? "active" : undefined}>
            {STATUS[s]}
          </Link>
        ))}
        <span className="sep" />
        {(Object.keys(PLAN) as UserPlanFilter[]).map((p) => (
          <Link key={p} href={link({ plan: p })} className={p === plan ? "active" : undefined}>
            {PLAN[p]}
          </Link>
        ))}
      </div>

      {users.items.length === 0 ? (
        <p className="empty">Aucun compte ne correspond.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Compte</th>
              <th>Nom</th>
              <th>Contact</th>
              <th>Inscription</th>
              <th>Offre</th>
              <th>Statut</th>
            </tr>
          </thead>
          <tbody>
            {users.items.map((user) => {
              const sub = subscriptionState(user, now);
              return (
                <tr key={user.id}>
                  <td>
                    <Link href={`/users/${user.id}`} className="userlink">
                      <UserAvatar user={user} size={28} />
                      <span>@{user.username}</span>
                    </Link>
                  </td>
                  <td>{[user.firstName, user.lastName].filter(Boolean).join(" ") || <span className="muted">-</span>}</td>
                  <td>
                    {user.email ?? <span className="muted">Pas d'email</span>}
                    {user.phoneNumber && <div className="muted small">{user.phoneNumber}</div>}
                  </td>
                  <td className="nowrap">{formatDateTime(user.createdAt)}</td>
                  <td>{sub === "free" ? <span className="muted">Gratuit</span> : <span className={`badge ${sub === "expired" ? "" : "premium"}`}>{SUBSCRIPTION_STATE_LABEL[sub]}</span>}</td>
                  <td>
                    <UserStatusBadge user={user} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      <Pagination path="/users" params={{ q: q || undefined, status: status === "all" ? undefined : status, plan: plan === "all" ? undefined : plan }} page={users.page} hasNext={users.hasNext} />
    </>
  );
}
