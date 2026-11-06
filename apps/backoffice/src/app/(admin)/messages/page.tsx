import Link from "next/link";
import { redactMessage } from "@/app/actions";
import { ActionForm } from "@/components/action-form";
import { Pagination } from "@/components/pagination";
import { Tabs } from "@/components/tabs";
import { describeMessage, formatDateTime, parsePage } from "@/lib/format";
import { listMessages, type MessageKindFilter } from "@/lib/queries";

export const dynamic = "force-dynamic";

const KINDS: Record<MessageKindFilter, string> = { all: "Tous", text: "Chats", snap: "Snaps", moderated: "Retirés" };

// Fil des messages privés de toute l'app, pour repérer un abus sans attendre un signalement (ADR-021).
// Le média d'un Snap n'est jamais affiché : seule sa nature l'est.
export default async function MessagesPage({ searchParams }: { searchParams: Promise<{ kind?: string; page?: string }> }) {
  const params = await searchParams;
  const kind = (params.kind && params.kind in KINDS ? params.kind : "all") as MessageKindFilter;
  const page = parsePage(params.page);
  const messages = await listMessages(kind, page);

  return (
    <>
      <h1>Messages privés</h1>
      <Tabs
        tabs={(Object.keys(KINDS) as MessageKindFilter[]).map((k) => ({
          href: k === "all" ? "/messages" : `/messages?kind=${k}`,
          label: KINDS[k],
          active: k === kind,
        }))}
      />
      {messages.items.length === 0 ? (
        <p className="empty">Aucun message.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>De</th>
              <th>À</th>
              <th>Contenu</th>
              <th>Ouvert</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {messages.items.map((m) => (
              <tr key={m.id}>
                <td className="nowrap">{formatDateTime(m.createdAt)}</td>
                <td>
                  <Link href={`/users/${m.sender.id}`}>@{m.sender.username}</Link>
                </td>
                <td>
                  <Link href={`/users/${m.recipient.id}`}>@{m.recipient.username}</Link>
                </td>
                <td className={m.moderatedAt ? "muted" : undefined}>
                  {m.contentType !== "TEXT" && !m.moderatedAt && <span className="badge snap">Snap</span>} {describeMessage(m)}
                  {m.moderatedAt && (
                    <div className="small">
                      par {m.moderatedBy?.email ?? "inconnu"} le {formatDateTime(m.moderatedAt)}
                      {m.moderationReason && ` : ${m.moderationReason}`}
                    </div>
                  )}
                  {m._count.reports > 0 && <span className="badge warning">{m._count.reports} signalement(s)</span>}
                </td>
                <td className="nowrap muted">{m.openedAt ? formatDateTime(m.openedAt) : "Non"}</td>
                <td>
                  {!m.moderatedAt && (
                    <ActionForm action={redactMessage} label="Retirer" fields={{ messageId: m.id, returnTo: "/messages" }} withReason variant="danger" />
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <Pagination path="/messages" params={{ kind: kind === "all" ? undefined : kind }} page={messages.page} hasNext={messages.hasNext} />
    </>
  );
}
