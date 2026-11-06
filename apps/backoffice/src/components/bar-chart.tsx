import type { DayCount } from "@/lib/format";

// Histogramme quotidien en CSS pur (pas de bibliothèque de graphiques) : une barre par jour,
// la valeur au survol et dans le texte accessible.
export function BarChart({ title, series, tone = "accent" }: { title: string; series: DayCount[]; tone?: "accent" | "ok" | "warning" | "violet" }) {
  const max = Math.max(1, ...series.map((d) => d.count));
  const total = series.reduce((sum, d) => sum + d.count, 0);
  return (
    <figure className="card chart">
      <figcaption>
        <span>{title}</span>
        <strong>{total.toLocaleString("fr-FR")}</strong>
      </figcaption>
      <div className={`bars ${tone}`} role="img" aria-label={`${title} : ${series.map((d) => `${d.label} ${d.count}`).join(", ")}`}>
        {series.map((d) => (
          <div key={d.key} className="bar" title={`${d.label} : ${d.count}`}>
            <span style={{ height: `${(d.count / max) * 100}%` }} />
          </div>
        ))}
      </div>
      <div className="axis">
        <span>{series[0]?.label}</span>
        <span>{series[series.length - 1]?.label}</span>
      </div>
    </figure>
  );
}
