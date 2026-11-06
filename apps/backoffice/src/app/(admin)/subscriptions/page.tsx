import { UserLink } from "@/components/user-link";
import { SUBSCRIPTION_STATE_LABEL, formatDateTime, subscriptionState } from "@/lib/format";
import { subscriptionOverview } from "@/lib/queries";

export const dynamic = "force-dynamic";

// Abonnements SnapCore+ (ADR-007, ADR-025). RevenueCat reste la source de vérité ; la base n'en
// garde que l'état appliqué par le dernier webhook.
export default async function SubscriptionsPage() {
  const { active, expiringSoon, expired, total } = await subscriptionOverview();
  const now = new Date();
  const rate = total ? Math.round((active.length / total) * 1000) / 10 : 0;

  return (
    <>
      <h1>SnapCore+</h1>
      <div className="grid">
        <div className="card stat">
          <div className="value">{active.length}</div>
          <div className="label">Abonnés actifs</div>
        </div>
        <div className="card stat">
          <div className="value">{rate.toLocaleString("fr-FR")} %</div>
          <div className="label">des comptes</div>
        </div>
        <div className={`card stat${expiringSoon ? " warning" : ""}`}>
          <div className="value">{expiringSoon}</div>
          <div className="label">Fin de période sous 7 jours</div>
        </div>
        <div className="card stat">
          <div className="value">{expired.length}</div>
          <div className="label">Abonnements expirés</div>
        </div>
      </div>

      <h2>Abonnés actifs</h2>
      {active.length === 0 ? (
        <p className="empty">Aucun abonné pour le moment.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Compte</th>
              <th>Email</th>
              <th>État</th>
              <th>Fin de période</th>
              <th>Dernier événement RevenueCat</th>
            </tr>
          </thead>
          <tbody>
            {active.map((user) => {
              const state = subscriptionState(user, now);
              return (
                <tr key={user.id}>
                  <td>
                    <UserLink user={user} />
                  </td>
                  <td>{user.email ?? <span className="muted">-</span>}</td>
                  <td>
                    <span className={`badge ${state === "expiring" ? "warning" : "premium"}`}>{SUBSCRIPTION_STATE_LABEL[state]}</span>
                  </td>
                  <td className="nowrap">{formatDateTime(user.premiumUntil)}</td>
                  <td className="nowrap">{formatDateTime(user.subscriptionEventAt)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      <h2>Abonnements expirés (100 derniers)</h2>
      {expired.length === 0 ? (
        <p className="empty">Aucun.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Compte</th>
              <th>Email</th>
              <th>Fin le</th>
            </tr>
          </thead>
          <tbody>
            {expired.map((user) => (
              <tr key={user.id}>
                <td>
                  <UserLink user={user} />
                </td>
                <td>{user.email ?? <span className="muted">-</span>}</td>
                <td className="nowrap">{formatDateTime(user.premiumUntil)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
