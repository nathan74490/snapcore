import { UserLink } from "@/components/user-link";
import { UserStatusBadge } from "@/components/user-status-badge";
import { FRIENDSHIP_STATUS_LABEL, formatDateTime } from "@/lib/format";
import { userFriendships } from "@/lib/queries";

const ORDER = { ACCEPTED: 0, PENDING: 1, BLOCKED: 2, DECLINED: 3 } as const;

// Relations d'un compte (ADR-012) : amis, demandes envoyées et reçues, blocages.
export async function UserFriends({ userId }: { userId: string }) {
  const friendships = await userFriendships(userId);
  if (friendships.length === 0) return <p className="empty">Aucune relation.</p>;
  const sorted = [...friendships].sort((a, b) => ORDER[a.status] - ORDER[b.status]);

  return (
    <table>
      <thead>
        <tr>
          <th>Compte</th>
          <th>Relation</th>
          <th>Demande</th>
          <th>Réponse</th>
          <th>Statut du compte</th>
        </tr>
      </thead>
      <tbody>
        {sorted.map((f) => {
          const outgoing = f.requester.id === userId;
          const other = outgoing ? f.addressee : f.requester;
          let relation: string = FRIENDSHIP_STATUS_LABEL[f.status];
          if (f.status === "PENDING") relation = outgoing ? "Demande envoyée" : "Demande reçue";
          if (f.status === "BLOCKED") relation = f.blockedBy === userId ? "A bloqué ce compte" : "Bloqué par ce compte";
          const tone = f.status === "ACCEPTED" ? "ok" : f.status === "BLOCKED" ? "danger" : "";
          return (
            <tr key={f.id}>
              <td>
                <UserLink user={other} />
              </td>
              <td>
                <span className={`badge ${tone}`}>{relation}</span>
              </td>
              <td className="nowrap">{formatDateTime(f.requestedAt)}</td>
              <td className="nowrap">{formatDateTime(f.respondedAt)}</td>
              <td>
                <UserStatusBadge user={other} />
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
