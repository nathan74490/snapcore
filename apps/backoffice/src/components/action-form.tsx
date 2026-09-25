"use client";

import { useActionState } from "react";
import type { ActionResult } from "@/app/actions";

type ServerAction = (prev: ActionResult | null, form: FormData) => Promise<ActionResult>;

interface Props {
  action: ServerAction;
  label: string;
  /** Champs cachés transmis à l'action (identifiants, statut…). */
  fields: Record<string, string>;
  /** Demande une raison, enregistrée avec l'action (ADR-021). */
  withReason?: boolean;
  confirm?: string;
  variant?: "primary" | "danger";
}

// Formulaire d'une action de modération : affiche le résultat renvoyé par l'action serveur.
export function ActionForm({ action, label, fields, withReason = false, confirm, variant }: Props) {
  const [result, formAction, pending] = useActionState(action, null);

  return (
    <form
      className="inline"
      action={formAction}
      onSubmit={(event) => {
        if (confirm && !window.confirm(confirm)) event.preventDefault();
      }}
    >
      {Object.entries(fields).map(([name, value]) => (
        <input key={name} type="hidden" name={name} value={value} />
      ))}
      {withReason && <input name="reason" placeholder="Raison (obligatoire)" required maxLength={500} />}
      <button type="submit" className={variant} disabled={pending}>
        {pending ? "…" : label}
      </button>
      {result && <span className={`result ${result.ok ? "ok" : "error"}`}>{result.message}</span>}
    </form>
  );
}
