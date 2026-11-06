import Link from "next/link";
import { redactMessage } from "@/app/actions";
import { ActionForm } from "@/components/action-form";
import { Pagination } from "@/components/pagination";
import { describeMessage, formatDateTime } from "@/lib/format";
import { userMessages } from "@/lib/queries";

// Messages privés d'un compte ; `withUserId` limite la liste à une conversation.
export async function UserMessages({ userId, page, withUserId }: { userId: string; page: number; withUserId?: string }) {
  const messages = await userMessages(userId, page, withUserId);
  const base = `/users/${userId}`;
  const first = messages.items[0];
  const peerInConversation = withUserId && first ? (first.sender.id === userId ? first.recipient : first.sender) : null;

  return (
    <>
      {withUserId && (
        <p>
          Conversation avec {peerInConversation ? `@${peerInConversation.username}` : "ce compte"} ·{" "}
          <Link href={`${base}?tab=messages`}>toutes les conversations</Link>
        </p>
      )}
      {messages.items.length === 0 ? (
        <p className="empty">Aucun message.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>Avec</th>
              <th>Contenu</th>
              <th>Ouvert</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {messages.items.map((m) => {
              const sent = m.sender.id === userId;
              const peer = sent ? m.recipient : m.sender;
              return (
                <tr key={m.id}>
                  <td className="nowrap">{formatDateTime(m.createdAt)}</td>
                  <td className="nowrap">
                    <span title={sent ? "Envoyé" : "Reçu"}>{sent ? "→" : "←"}</span> <Link href={`/users/${peer.id}`}>@{peer.username}</Link>
                    {!withUserId && (
                      <>
                        {" "}
                        <Link className="small" href={`${base}?tab=messages&with=${peer.id}`}>
                          conversation
                        </Link>
                      </>
                    )}
                  </td>
                  <td className={m.moderatedAt ? "muted" : undefined}>
                    {m.contentType !== "TEXT" && !m.moderatedAt && <span className="badge snap">Snap</span>} {describeMessage(m)}
                    {m.moderationReason && <span className="muted"> : {m.moderationReason}</span>}
                  </td>
                  <td className="nowrap muted">{m.openedAt ? formatDateTime(m.openedAt) : "Non"}</td>
                  <td>
                    {!m.moderatedAt && (
                      <ActionForm action={redactMessage} label="Retirer" fields={{ messageId: m.id, returnTo: base }} withReason variant="danger" />
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      <Pagination path={base} params={{ tab: "messages", with: withUserId }} page={messages.page} hasNext={messages.hasNext} />
    </>
  );
}
