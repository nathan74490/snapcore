import Link from "next/link";
import { notFound } from "next/navigation";
import { deleteStoryItem, reactivateUser, suspendUser } from "@/app/actions";
import { ActionForm } from "@/components/action-form";
import { Tabs } from "@/components/tabs";
import { UserFriends } from "@/components/user/user-friends";
import { UserMessages } from "@/components/user/user-messages";
import { UserReports } from "@/components/user/user-reports";
import { UserAvatar } from "@/components/user-avatar";
import { UserStatusBadge } from "@/components/user-status-badge";
import {
  LOCATION_SHARING_LABEL,
  SUBSCRIPTION_STATE_LABEL,
  formatDateOnly,
  formatDateTime,
  parsePage,
  subscriptionState,
} from "@/lib/format";
import { getUserDetail } from "@/lib/queries";

export const dynamic = "force-dynamic";

const TABS = { profile: "Profil", messages: "Messages", friends: "Amis", groups: "Groupes", reports: "Signalements" } as const;
type TabKey = keyof typeof TABS;

// Fiche d'un compte et de toute son activité, consultable sans signalement préalable (ADR-021).
export default async function UserPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ tab?: string; page?: string; with?: string }>;
}) {
  const [{ id }, query] = await Promise.all([params, searchParams]);
  const detail = await getUserDetail(id);
  if (!detail) notFound();
  const { user, stats, stories, devices, shareRules, groups } = detail;
  const tab: TabKey = query.tab && query.tab in TABS ? (query.tab as TabKey) : "profile";
  const base = `/users/${user.id}`;
  const sub = subscriptionState(user, new Date());
  const counts: Partial<Record<TabKey, number>> = {
    friends: stats.friends,
    groups: groups.length,
    reports: stats.reportsAgainst,
  };

  return (
    <div className="stack">
      <p>
        <Link href="/users">← Utilisateurs</Link>
      </p>
      <header className="profilehead">
        <UserAvatar user={user} size={72} />
        <div>
          <h1>
            {[user.firstName, user.lastName].filter(Boolean).join(" ") || `@${user.username}`} <UserStatusBadge user={user} />{" "}
            {sub !== "free" && <span className={`badge ${sub === "expired" ? "" : "premium"}`}>{SUBSCRIPTION_STATE_LABEL[sub]}</span>}
          </h1>
          <p className="muted">
            @{user.username} · inscrit le {formatDateTime(user.createdAt)} · <code>{user.id}</code>
          </p>
        </div>
        {!user.deletionRequestedAt && (
          <div className="headactions">
            {user.moderationStatus === "ACTIVE" ? (
              <ActionForm
                action={suspendUser}
                label="Suspendre le compte"
                fields={{ userId: user.id }}
                withReason
                variant="danger"
                confirm={`Suspendre @${user.username} ? Le compte deviendra invisible et ne pourra plus se connecter.`}
              />
            ) : (
              <ActionForm action={reactivateUser} label="Réactiver le compte" fields={{ userId: user.id }} variant="primary" />
            )}
          </div>
        )}
      </header>

      {user.moderationStatus === "SUSPENDED" && (
        <div className="card alert">
          Suspendu le {formatDateTime(user.suspendedAt)} par {user.suspendedBy?.email ?? "un modérateur supprimé"} : {user.suspensionReason}
        </div>
      )}
      {user.deletionRequestedAt && <div className="card alert">Suppression du compte demandée le {formatDateTime(user.deletionRequestedAt)} (ADR-019).</div>}

      <div className="grid compact">
        {(
          [
            ["Amis", stats.friends],
            ["Demandes reçues", stats.pendingRequests],
            ["Messages envoyés", stats.messagesSent],
            ["dont Snaps", stats.snapsSent],
            ["Messages de groupe", stats.groupMessagesSent],
            ["Signalements reçus", stats.reportsAgainst],
            ["Signalements envoyés", stats.reportsMade],
          ] as const
        ).map(([label, value]) => (
          <div key={label} className="card stat">
            <div className="value">{value}</div>
            <div className="label">{label}</div>
          </div>
        ))}
      </div>

      <Tabs
        tabs={(Object.keys(TABS) as TabKey[]).map((key) => ({
          href: key === "profile" ? base : `${base}?tab=${key}`,
          label: TABS[key],
          count: counts[key],
          active: key === tab,
        }))}
      />

      {tab === "profile" && (
        <div className="columns">
          <div className="card">
            <h3>Identité</h3>
            <dl>
              <dt>Prénom</dt>
              <dd>{user.firstName ?? "Non renseigné"}</dd>
              <dt>Nom</dt>
              <dd>{user.lastName ?? "Non renseigné"}</dd>
              <dt>Date de naissance</dt>
              <dd>{formatDateOnly(user.birthday) ?? "Non renseignée"}</dd>
              <dt>Email</dt>
              <dd>{user.email ?? "Non renseigné"}</dd>
              <dt>Téléphone</dt>
              <dd>{user.phoneNumber ?? "Non renseigné"}</dd>
              <dt>Avatar</dt>
              <dd>{user.avatar ? "Avatar dessiné" : user.profilePhotoUrl ? "Photo de profil" : "Aucun"}</dd>
            </dl>
          </div>
          <div className="card">
            <h3>SnapCore+</h3>
            <dl>
              <dt>Offre</dt>
              <dd>{SUBSCRIPTION_STATE_LABEL[sub]}</dd>
              <dt>Fin de période</dt>
              <dd>{formatDateTime(user.premiumUntil)}</dd>
              <dt>Dernier événement</dt>
              <dd>{formatDateTime(user.subscriptionEventAt)}</dd>
            </dl>
            <h3>Snap Map</h3>
            <dl>
              <dt>Partage de position</dt>
              <dd>{LOCATION_SHARING_LABEL[user.locationSharing]}</dd>
              {(user.locationSharing === "FRIENDS_EXCEPT" || user.locationSharing === "ONLY_FRIENDS") && (
                <>
                  <dt>{user.locationSharing === "FRIENDS_EXCEPT" ? "Sauf" : "Avec"}</dt>
                  <dd>
                    {shareRules.length === 0
                      ? "personne"
                      : shareRules.map((friend, i) => (
                          <span key={friend.id}>
                            {i > 0 && ", "}
                            <Link href={`/users/${friend.id}`}>@{friend.username}</Link>
                          </span>
                        ))}
                  </dd>
                </>
              )}
            </dl>
            <h3>Appareils</h3>
            <p className="muted">
              {devices.length === 0
                ? "Aucun appareil enregistré pour les notifications."
                : `${devices.length} appareil(s) pour les notifications, dernier enregistrement le ${formatDateTime(devices[0].updatedAt)}.`}
            </p>
          </div>
          <div className="card wide">
            <h3>Stories actives ({stories.length})</h3>
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
                      <td>{story.latitude !== null ? <span className="badge">Sur la carte</span> : null}</td>
                      <td>
                        <ActionForm action={deleteStoryItem} label="Supprimer" fields={{ storyItemId: story.id, returnTo: base }} withReason variant="danger" />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>
      )}

      {tab === "messages" && <UserMessages userId={user.id} page={parsePage(query.page)} withUserId={query.with} />}
      {tab === "friends" && <UserFriends userId={user.id} />}
      {tab === "reports" && <UserReports userId={user.id} />}
      {tab === "groups" &&
        (groups.length === 0 ? (
          <p className="empty">Membre d&apos;aucun groupe.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Groupe</th>
                <th>Membres</th>
                <th>Messages</th>
                <th>Membre depuis</th>
              </tr>
            </thead>
            <tbody>
              {groups.map(({ group, joinedAt }) => (
                <tr key={group.id}>
                  <td>
                    <Link href={`/groups/${group.id}`}>{group.name ?? "Groupe sans nom"}</Link>
                  </td>
                  <td>{group._count.members}</td>
                  <td>{group._count.messages}</td>
                  <td>{formatDateTime(joinedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ))}
    </div>
  );
}
