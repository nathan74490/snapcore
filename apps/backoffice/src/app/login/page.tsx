import { getServerSession } from "next-auth";
import { redirect } from "next/navigation";
import { authOptions } from "@/lib/auth";
import { LoginForm } from "./login-form";

export default async function LoginPage() {
  if (await getServerSession(authOptions)) redirect("/");
  return (
    <div className="login card">
      <h1>Modération SnapCore</h1>
      <p className="muted">Accès réservé à l'équipe. Les comptes sont créés par un administrateur.</p>
      <LoginForm />
    </div>
  );
}
