"use client";

import { signIn } from "next-auth/react";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";

export function LoginForm() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setPending(true);
    setError(null);
    const result = await signIn("credentials", {
      email: form.get("email"),
      password: form.get("password"),
      redirect: false,
    });
    setPending(false);
    // Message unique : on ne révèle pas si c'est l'email ou le mot de passe qui est faux.
    if (!result || result.error) setError("Identifiants incorrects");
    else router.replace("/");
  }

  return (
    <form onSubmit={onSubmit}>
      <input name="email" type="email" placeholder="Email" autoComplete="username" required />
      <input name="password" type="password" placeholder="Mot de passe" autoComplete="current-password" required />
      {error && <p className="result error">{error}</p>}
      <button className="primary" type="submit" disabled={pending}>
        {pending ? "Connexion…" : "Se connecter"}
      </button>
    </form>
  );
}
