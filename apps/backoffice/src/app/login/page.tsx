import { redirect } from "next/navigation";
import { currentModerator } from "@/lib/auth";
import { LoginForm } from "./login-form";

export default async function LoginPage() {
  // Même vérification que requireModerator : un JWT encore valide d'un modérateur supprimé ne doit pas
  // renvoyer vers /, qui le renverrait ici, en boucle (BUG-003 d'ADR-023).
  if (await currentModerator()) redirect("/");
  return (
    <div className="login card">
      <h1>Modération SnapCore</h1>
      <p className="muted">Accès réservé à l'équipe. Les comptes sont créés par un administrateur.</p>
      <LoginForm />
    </div>
  );
}
