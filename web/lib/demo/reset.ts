// Reset orchestrato di BO-30 (docs/08 §BO-30): *Ripristina tutto* è irreversibile e si conferma digitando `RESET`
// (docs/08 §3.5, azioni irreversibili).
export const RESET_CONFIRM_WORD = "RESET";

/** La conferma vale solo con la parola esatta (maiuscole, spazi ai bordi ignorati). */
export function isResetConfirmed(typed: string | null | undefined): boolean {
  return (typed ?? "").trim() === RESET_CONFIRM_WORD;
}
