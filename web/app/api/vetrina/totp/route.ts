import { NextResponse } from "next/server";
import { problem } from "@/lib/auth/bff";
import { testMode } from "@/lib/hub/testMode";
import { OPERATORS_TOTP_SEED } from "@/lib/hub/testUsers";
import { totp } from "@/lib/hub/totp";

// HUB-02, ADR-051, Q-676: codice OTP del momento degli operatori di test, calcolato dal seme pubblico (RFC 6238).
// Solo GET; 404 se non siamo in enterprise con LH_TEST_USERS_ALLOWED=true e LH_ENVIRONMENT=test. Mai in cache.
export const dynamic = "force-dynamic";

export function GET() {
  if (testMode() === null) {
    return problem(404, "NOT_FOUND", "Non disponibile", "Il codice di prova esiste solo nell'ambiente di test dichiarato.");
  }
  const res = NextResponse.json(totp(OPERATORS_TOTP_SEED));
  res.headers.set("cache-control", "no-store");
  return res;
}
