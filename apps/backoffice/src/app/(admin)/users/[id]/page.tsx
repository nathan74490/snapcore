import Link from "next/link";
import { notFound } from "next/navigation";
import { deleteStoryItem, reactivateUser, redactMessage, suspendUser } from "@/app/actions";
import { ActionForm } from "@/components/action-form";
import { UserStatusBadge } from "@/components/user-status-badge";
import { describeMessage, formatDateTime } from "@/lib/format";
import { getUserDetail } from "@/lib/queries";

export const dynamic = "force-dynamic";

// Fiche d'un compte et de son activité, consultable sans signalement préalable (ADR-021).
export default async function UserPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const detail = await getUserDetail(id).catch(() => null);
  if (!detail) notFound();
  const { user, messages, stories, friends, reportsAgainst, reportsMade } = detail;
  const returnTo = `/users/${user.id}`;

  return (
    <div className="stack">
      <p>
        <Link href="/users">← Utilisateurs</Link>
      </p>
      <h1>
        @{user.username} <UserStatusBadge user={user} />
      </h1>

      <div className="card">
        <dl>
          <dt>Email</dt>
          <dd>{user.email ?? "—"}</dd>
          <dt>Téléphone</dt>
          <dd>{user.phoneNumber ?? "—"}</dd>
          <dt>Inscription</dt>
          <dd>{formatDateTime(user.createdAt)}</dd>
          <dt>Abonnement</dt>
          <dd>{user.subscriptionTier === "premium" ? `SnapCore+ jusqu'au ${formatDateTime(user.premiumUntil)}` : "Gratuit"}</dd>
          <dt>Amis</dt>
          <dd>{friends}</dd>
          <dt>Signalements</dt>
          <dd>
            {reportsAgainst} reçu(s) · {reportsMade} envoyé(s)
          </dd>
          {user.moderationStatus === "SUSPENDED" && (
            <>
              <dt>Suspendu</dt>
              <dd>
                le {formatDateTime(user.suspendedAt)} par {user.suspendedBy?.email ?? "—"} : {user.suspensionReason}
              </dd>
            </>
          )}
        </dl>
      </div>

      {!user.deletionRequestedAt && (
        <div>
          {user.moderationStatus === "ACTIVE" ? (
            <ActionForm
              action={suspendUser}
              label="Suspendre le compte"
              fields={{ userId: user.id }}
              withReason
              variant="danger"
              confirm={`Suspendre @${user.username} ?`}
            />
          ) : (
            <ActionForm action={reactivateUser} label="Réactiver le compte" fields={{ userId: user.id }} variant="primary" />
          )}
        </div>
      )}

      <h2>Stories actives ({stories.length})</h2>
      {stories.length === 0 ? (
        <p className="muted">Aucune.</p>
      ) : (
        <table>
          <tbody>
            {stories.map((story) => (
              <tr key={story.id}>
                <td>{story.contentType === "VIDEO" ? "Vidéo" : "Photo"}</td>
                <td>publiée {formatDateTime(story.createdAt)}</td>
                <td>expire {formatDateTime(story.expiresAt)}</td>
                <td>
                  <ActionForm action={deleteStoryItem} label="Supprimer" fields={{ storyItemId: story.id, returnTo }} withReason variant="danger" />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h2>Derniers messages (50)</h2>
      {messages.length === 0 ? (
        <p className="muted">Aucun.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>Sens</th>
              <th>Contenu</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {messages.map((m) => {
              const sent = m.sender.id === user.id;
              const other = sent ? m.recipient : m.sender;
              return (
                <tr key={m.id}>
                  <td>{formatDateTime(m.createdAt)}</td>
                  <td>
                    {sent ? "→" : "←"} <Link href={`/users/${other.id}`}>@{other.username}</Link>
                  </td>
                  <td className={m.moderatedAt ? "muted" : undefined}>
                    {describeMessage(m)}
                    {m.moderationReason && <span className="muted"> — {m.moderationReason}</span>}
                  </td>
                  <td>
                    {!m.moderatedAt && (
                      <ActionForm action={redactMessage} label="Retirer" fields={{ messageId: m.id, returnTo }} withReason variant="danger" />
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </div>
  );
}
