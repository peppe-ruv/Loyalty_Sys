import { LogoutButton } from "./LogoutButton";

// Account autenticato ma del tipo sbagliato per l'area (membro nel backoffice, operatore nel portale): stato
// Forbidden a pagina intera (docs/07 §6) con l'uscita, per accedere con un altro account.
export function AccessDenied({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="mx-auto flex min-h-dvh max-w-md flex-col items-center justify-center gap-3 p-6 text-center">
      <h1 className="text-lg font-semibold">{title}</h1>
      <p className="text-sm text-[var(--color-bo-ink-2)]">{detail}</p>
      <LogoutButton />
    </div>
  );
}
