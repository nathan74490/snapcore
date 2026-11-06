/**
 * Dashboard PostHog « Onboarding — de l'accueil au premier snap » (ADR-008, plan de marquage
 * docs/analytics/plan-de-marquage.md, détail dans docs/analytics/dashboard-onboarding.md).
 *
 *   POSTHOG_PERSONAL_API_KEY=phx_… npm run posthog:dashboard -w @snapcore/backend
 *   npm run posthog:dashboard -w @snapcore/backend -- --dry-run   (affiche les requêtes, n'envoie rien)
 *
 * - Clé personnelle (phx_, PostHog > Settings > Personal API keys) avec les droits dashboard:write,
 *   insight:write et project:read. Ce n'est PAS la clé de projet phc_ de l'app et du backend.
 * - POSTHOG_APP_HOST : https://eu.posthog.com par défaut (l'hôte de l'interface, pas eu.i.posthog.com).
 * - POSTHOG_PROJECT_ID : id numérique du projet ; par défaut « @current », le projet courant de la clé.
 * - Relançable : le dashboard est retrouvé par son nom, ses anciens graphiques sont supprimés et recréés,
 *   l'URL ne change pas. Rien d'autre n'est touché dans PostHog.
 */

const DASHBOARD_NAME = "Onboarding — de l'accueil au premier snap";
const DASHBOARD_DESCRIPTION =
  "Accueil, inscription par SMS, premiers pas guidés par le fantôme, puis SnapCore+ et activation. " +
  "Généré par apps/backend/scripts/posthog-onboarding-dashboard.ts : modifier le script plutôt que le dashboard.";
const TAG = "onboarding";
const DATE_RANGE = { date_from: "-30d" };

// --- Construction des requêtes (schéma HogQL « query » de PostHog) ------------------------------

type Filter = Record<string, string | number | boolean | (string | number | boolean)[]>;
type QueryNode = Record<string, unknown>;

/** Filtres d'égalité sur les propriétés de l'événement. */
function props(filter: Filter = {}) {
  return Object.entries(filter).map(([key, value]) => ({
    key,
    value: Array.isArray(value) ? value : [value],
    operator: "exact",
    type: "event",
  }));
}

function ev(event: string, label: string, filter: Filter = {}, extra: QueryNode = {}): QueryNode {
  return { kind: "EventsNode", event, name: event, custom_name: label, properties: props(filter), ...extra };
}

const median = (property: string): QueryNode => ({ math: "median", math_property: property });
const average = (property: string): QueryNode => ({ math: "avg", math_property: property });

type Display = "BoldNumber" | "ActionsLineGraph" | "ActionsBar" | "ActionsBarValue" | "ActionsPie" | "ActionsTable";

interface TrendsOptions {
  display: Display;
  breakdown?: string;
  formula?: string;
  format?: "numeric" | "duration_ms" | "percentage_scaled";
  interval?: "day" | "week";
  compare?: boolean;
}

function trends(series: QueryNode[], o: TrendsOptions): QueryNode {
  return {
    kind: "InsightVizNode",
    source: {
      kind: "TrendsQuery",
      series,
      interval: o.interval ?? "day",
      dateRange: DATE_RANGE,
      filterTestAccounts: true,
      ...(o.breakdown ? { breakdownFilter: { breakdown: o.breakdown, breakdown_type: "event" } } : {}),
      ...(o.compare ? { compareFilter: { compare: true } } : {}),
      trendsFilter: {
        display: o.display,
        ...(o.formula ? { formula: o.formula } : {}),
        ...(o.format ? { aggregationAxisFormat: o.format } : {}),
        showValuesOnSeries: o.display === "ActionsBarValue" || o.display === "ActionsPie",
      },
    },
  };
}

function funnel(series: QueryNode[], windowDays: number, breakdown?: string): QueryNode {
  return {
    kind: "InsightVizNode",
    source: {
      kind: "FunnelsQuery",
      series,
      dateRange: DATE_RANGE,
      filterTestAccounts: true,
      ...(breakdown ? { breakdownFilter: { breakdown, breakdown_type: "event" } } : {}),
      funnelsFilter: {
        funnelVizType: "steps",
        funnelOrderType: "ordered",
        funnelWindowInterval: windowDays,
        funnelWindowIntervalUnit: "day",
        layout: "vertical",
      },
    },
  };
}

function retention(target: string, returning: string): QueryNode {
  return {
    kind: "InsightVizNode",
    source: {
      kind: "RetentionQuery",
      dateRange: { date_from: "-8w" },
      filterTestAccounts: true,
      retentionFilter: {
        targetEntity: { id: target, name: target, type: "events" },
        returningEntity: { id: returning, name: returning, type: "events" },
        retentionType: "retention_first_time",
        period: "Week",
        totalIntervals: 8,
      },
    },
  };
}

// --- Le dashboard, de haut en bas -----------------------------------------------------------
// w et h en cases de la grille PostHog (12 colonnes) ; les tuiles se rangent dans l'ordre.

interface Tile {
  name: string;
  description: string;
  query: QueryNode;
  w: number;
  h: number;
}

const tiles: Tile[] = [
  // Les quatre chiffres du jour
  {
    name: "Nouveaux comptes",
    description: "Comptes créés (backend, user_registered) sur 30 jours, comparés aux 30 jours précédents.",
    query: trends([ev("user_registered", "Comptes créés")], { display: "BoldNumber", compare: true }),
    w: 3, h: 4,
  },
  {
    name: "Accueil → compte créé",
    description: "Part des « Je m'inscris » touchés sur l'accueil qui aboutissent à un compte.",
    query: trends(
      [ev("welcome_choice", "Je m'inscris", { choice: "signup" }), ev("signup_finished", "Comptes créés", { outcome: "registered" })],
      { display: "BoldNumber", formula: "B / A", format: "percentage_scaled", compare: true },
    ),
    w: 3, h: 4,
  },
  {
    name: "Premiers pas terminés",
    description: "Part des premiers pas commencés qui vont jusqu'au bout sans la touche retour.",
    query: trends(
      [ev("onboarding_step_viewed", "Premiers pas commencés", { step: "find_friends" }),
        ev("onboarding_completed", "Terminés jusqu'au bout", { ended_by: "done" })],
      { display: "BoldNumber", formula: "B / A", format: "percentage_scaled", compare: true },
    ),
    w: 3, h: 4,
  },
  {
    name: "Durée médiane de l'inscription",
    description: "Du prénom au compte créé, sur le téléphone (signup_finished.duration_ms).",
    query: trends([ev("signup_finished", "Inscription", { outcome: "registered" }, median("duration_ms"))],
      { display: "BoldNumber", format: "duration_ms", compare: true }),
    w: 3, h: 4,
  },

  // Le parcours entier
  {
    name: "De l'accueil au premier snap",
    description:
      "Entonnoir complet sur 7 jours par personne : l'accueil anonyme est rattaché au compte par l'identify. " +
      "La dernière marche, le premier snap pris, est l'activation.",
    query: funnel([
      ev("welcome_choice", "Je m'inscris", { choice: "signup" }),
      ev("signup_step_viewed", "Prénom", { step: "name" }),
      ev("sms_code_requested", "Code SMS envoyé", { flow: "signup" }),
      ev("signup_finished", "Compte créé", { outcome: "registered" }),
      ev("onboarding_step_viewed", "Premiers pas", { step: "find_friends" }),
      ev("onboarding_completed", "Premiers pas terminés"),
      ev("snap_captured", "Premier snap pris"),
    ], 7),
    w: 12, h: 6,
  },

  // Inscription
  {
    name: "Inscription, étape par étape",
    description: "Où l'on décroche dans les cinq écrans de l'inscription (hors reprise d'inscription).",
    query: funnel([
      ev("signup_step_viewed", "1 · Prénom", { step: "name", resuming: false }),
      ev("signup_step_viewed", "2 · Naissance", { step: "birthday", resuming: false }),
      ev("signup_step_viewed", "3 · Pseudo", { step: "username", resuming: false }),
      ev("signup_step_viewed", "4 · Numéro", { step: "phone", resuming: false }),
      ev("signup_step_viewed", "5 · Code SMS", { step: "code", resuming: false }),
      ev("signup_finished", "Compte créé", { outcome: "registered" }),
    ], 1),
    w: 6, h: 6,
  },
  {
    name: "Temps passé par étape",
    description: "Durée médiane avant de valider chaque étape de l'inscription.",
    query: trends([ev("signup_step_completed", "Durée médiane", {}, median("duration_ms"))],
      { display: "ActionsBarValue", breakdown: "step", format: "duration_ms" }),
    w: 6, h: 6,
  },
  {
    name: "Échecs de l'inscription",
    description: "Refus du backend ou de Firebase, par type d'erreur (jamais le message).",
    query: trends([ev("signup_failed", "Échecs")], { display: "ActionsBar", breakdown: "reason" }),
    w: 6, h: 5,
  },
  {
    name: "Retours en arrière",
    description: "Étape quittée par la flèche retour : un écran qu'on quitte souvent est un écran qui doute.",
    query: trends([ev("signup_step_back", "Retours")], { display: "ActionsBarValue", breakdown: "step" }),
    w: 6, h: 5,
  },
  {
    name: "Codes SMS",
    description: "Premiers envois, renvois (« Renvoyer le code ») et codes remplis par le SMS simulé.",
    query: trends([
      ev("sms_code_requested", "Premier envoi", { resend: false }),
      ev("sms_code_requested", "Renvoi", { resend: true }),
      ev("sms_code_autofilled", "Code rempli automatiquement"),
    ], { display: "ActionsLineGraph" }),
    w: 6, h: 5,
  },
  {
    name: "D'où vient le pseudo",
    description: "Pseudo gardé tel que proposé, proposé après « actualiser », ou tapé à la main.",
    query: trends([ev("signup_step_completed", "Pseudos", { step: "username" })], { display: "ActionsPie", breakdown: "username_source" }),
    w: 3, h: 5,
  },
  {
    name: "Pays des numéros",
    description: "Code pays ISO choisi pour le code SMS (jamais le numéro).",
    query: trends([ev("sms_code_requested", "Codes envoyés", { resend: false })], { display: "ActionsPie", breakdown: "country" }),
    w: 3, h: 5,
  },

  // Premiers pas avec le fantôme
  {
    name: "Premiers pas, écran par écran",
    description: "Amis, avatar puis fin. Les invitations et les notifications, conditionnelles, ont leurs propres graphiques.",
    query: funnel([
      ev("onboarding_step_viewed", "Trouve tes amis", { step: "find_friends" }),
      ev("onboarding_step_viewed", "Ajoute des amis", { step: "add_friends" }),
      ev("onboarding_step_viewed", "Avatar", { step: "avatar" }),
      ev("onboarding_completed", "Terminé"),
    ], 1),
    w: 6, h: 6,
  },
  {
    name: "Taux de « Passer » par écran",
    description: "Écrans passés sur écrans affichés.",
    query: trends(
      [ev("onboarding_step_viewed", "Affichés"), ev("onboarding_step_skipped", "Passés")],
      { display: "ActionsBarValue", breakdown: "step", formula: "B / A", format: "percentage_scaled" },
    ),
    w: 6, h: 6,
  },
  {
    name: "Accès aux contacts",
    description: "Réponse à la demande d'accès aux contacts.",
    query: trends([ev("contacts_permission_answered", "Réponses")], { display: "ActionsPie", breakdown: "granted" }),
    w: 3, h: 5,
  },
  {
    name: "Notifications",
    description: "Réponse à la demande de notifications d'Android 13+ en fin de parcours.",
    query: trends([ev("notifications_permission_answered", "Réponses", { source: "onboarding" })], { display: "ActionsPie", breakdown: "granted" }),
    w: 3, h: 5,
  },
  {
    name: "A/B onboarding-contacts-copy",
    description: "Part des contacts acceptés selon le texte de l'écran (ADR-030).",
    query: trends(
      [ev("contacts_permission_answered", "Réponses"), ev("contacts_permission_answered", "Acceptés", { granted: true })],
      { display: "ActionsBarValue", breakdown: "$feature/onboarding-contacts-copy", formula: "B / A", format: "percentage_scaled" },
    ),
    w: 6, h: 5,
  },
  {
    name: "Amis et invitations",
    description: "Moyennes par parcours : amis proposés, amis ajoutés, invitations envoyées.",
    query: trends([
      ev("onboarding_friends_added", "Amis proposés", {}, average("suggested")),
      ev("onboarding_friends_added", "Amis ajoutés", {}, average("count")),
      ev("invites_sent", "Invitations envoyées", {}, average("count")),
    ], { display: "ActionsLineGraph", interval: "week" }),
    w: 6, h: 5,
  },
  {
    name: "Durée médiane des premiers pas",
    description: "Du premier écran à la caméra, pour ceux qui vont au bout.",
    query: trends([ev("onboarding_completed", "Premiers pas", { ended_by: "done" }, median("duration_ms"))],
      { display: "BoldNumber", format: "duration_ms" }),
    w: 3, h: 5,
  },
  {
    name: "Fin des premiers pas",
    description: "Au bout du parcours, ou interrompus par la touche retour.",
    query: trends([ev("onboarding_completed", "Fins")], { display: "ActionsPie", breakdown: "ended_by" }),
    w: 3, h: 5,
  },

  // Après les premiers pas
  {
    name: "SnapCore+ en fin d'onboarding",
    description: "Offre proposée par-dessus la caméra, paiement lancé, abonnement actif au retour.",
    query: funnel([
      ev("paywall_opened", "Offre affichée", { source: "onboarding" }),
      ev("checkout_started", "Paiement lancé"),
      ev("checkout_returned", "Abonné", { activated: true }),
    ], 1),
    w: 6, h: 6,
  },
  {
    name: "Activation à 7 jours",
    description: "Compte créé, puis un premier ami accepté et un premier snap envoyé dans la semaine.",
    query: funnel([
      ev("user_registered", "Compte créé"),
      ev("friend_request_accepted", "Premier ami"),
      ev("snap_sent", "Premier snap envoyé"),
    ], 7),
    w: 6, h: 6,
  },
  {
    name: "Rétention des nouveaux comptes",
    description: "Semaine après semaine, part des nouveaux comptes qui envoient au moins un snap.",
    query: retention("user_registered", "snap_sent"),
    w: 12, h: 6,
  },

  // Accueil et connexion
  {
    name: "Accueil : s'inscrire ou se connecter",
    description: "Bouton touché sur l'écran d'accueil du fantôme.",
    query: trends([ev("welcome_choice", "Choix")], { display: "ActionsLineGraph", breakdown: "choice" }),
    w: 4, h: 5,
  },
  {
    name: "Connexions par méthode",
    description: "Numéro et code SMS, ou email pour les anciens comptes.",
    query: trends([ev("login_completed", "Réussies"), ev("login_failed", "Refusées")], { display: "ActionsBarValue", breakdown: "method" }),
    w: 4, h: 5,
  },
  {
    name: "Connexions refusées",
    description: "Par type d'erreur.",
    query: trends([ev("login_failed", "Refusées")], { display: "ActionsBar", breakdown: "reason" }),
    w: 4, h: 5,
  },
];

// --- Envoi ------------------------------------------------------------------------------------

const dryRun = process.argv.includes("--dry-run");
const host = (process.env.POSTHOG_APP_HOST || "https://eu.posthog.com").replace(/\/$/, "");
const project = process.env.POSTHOG_PROJECT_ID || "@current";
const apiKey = process.env.POSTHOG_PERSONAL_API_KEY;

async function api<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(`${host}/api/projects/${project}${path}`, {
    method,
    headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`${method} ${path} : HTTP ${response.status} ${await response.text()}`);
  return (response.status === 204 ? undefined : await response.json()) as T;
}

interface Dashboard {
  id: number;
  name: string;
  tiles?: { id: number; insight?: { id: number } | null }[];
}

/** Rangement des tuiles en lignes de 12 colonnes, dans l'ordre du tableau. */
function layouts() {
  let x = 0;
  let y = 0;
  let rowHeight = 0;
  return tiles.map((t) => {
    if (x + t.w > 12) {
      x = 0;
      y += rowHeight;
      rowHeight = 0;
    }
    const layout = { x, y, w: t.w, h: t.h };
    x += t.w;
    rowHeight = Math.max(rowHeight, t.h);
    return layout;
  });
}

async function main() {
  if (dryRun) {
    console.log(JSON.stringify({ dashboard: DASHBOARD_NAME, tiles: tiles.map(({ name, query }) => ({ name, query })) }, null, 2));
    return;
  }
  if (!apiKey) throw new Error("POSTHOG_PERSONAL_API_KEY manquante (clé personnelle phx_, voir l'en-tête du script)");

  const found = await api<{ results: Dashboard[] }>("GET", `/dashboards/?search=${encodeURIComponent(DASHBOARD_NAME)}`);
  let dashboard = found.results.find((d) => d.name === DASHBOARD_NAME);
  if (dashboard) {
    const full = await api<Dashboard>("GET", `/dashboards/${dashboard.id}/`);
    const old = (full.tiles ?? []).flatMap((t) => (t.insight ? [t.insight.id] : []));
    for (const id of old) await api("PATCH", `/insights/${id}/`, { deleted: true });
    console.log(`Dashboard existant (#${dashboard.id}) : ${old.length} anciens graphiques supprimés`);
  } else {
    dashboard = await api<Dashboard>("POST", "/dashboards/", { name: DASHBOARD_NAME, description: DASHBOARD_DESCRIPTION, tags: [TAG] });
    console.log(`Dashboard créé (#${dashboard.id})`);
  }
  await api("PATCH", `/dashboards/${dashboard.id}/`, { description: DASHBOARD_DESCRIPTION });

  const created: number[] = [];
  for (const t of tiles) {
    const insight = await api<{ id: number }>("POST", "/insights/", {
      name: t.name, description: t.description, query: t.query, dashboards: [dashboard.id], tags: [TAG],
    });
    created.push(insight.id);
    console.log(`  + ${t.name}`);
  }

  // Disposition : sans elle PostHog empile les tuiles ; un échec ici n'empêche pas d'utiliser le dashboard.
  try {
    const full = await api<Dashboard>("GET", `/dashboards/${dashboard.id}/`);
    const positions = layouts();
    const placed = (full.tiles ?? []).flatMap((tile) => {
      const index = tile.insight ? created.indexOf(tile.insight.id) : -1;
      if (index < 0) return [];
      const p = positions[index];
      return [{ id: tile.id, layouts: { sm: p, xs: { x: 0, y: p.y, w: 1, h: p.h } } }];
    });
    await api("PATCH", `/dashboards/${dashboard.id}/`, { tiles: placed });
  } catch (e) {
    console.warn(`Disposition non appliquée, les tuiles restent dans l'ordre par défaut : ${(e as Error).message}`);
  }

  console.log(`\n${tiles.length} graphiques : ${host}/project/${project === "@current" ? "" : project + "/"}dashboard/${dashboard.id}`);
}

main().catch((e) => {
  console.error((e as Error).message);
  process.exit(1);
});
