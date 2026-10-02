import type { NextRequest } from "next/server";
import { enterpriseOnly } from "@/lib/auth/bff";
import { handleLogin } from "@/lib/auth/handlers";
import { parseRealm, realmForPage } from "@/lib/auth/realm";
import { safeReturnTo } from "@/lib/auth/returnTo";

// Login OIDC del BFF (ADR-027, docs/07 §4-bis): `GET /api/auth/login?returnTo=/percorso[&realm=members|operators]`.
// Con due realm (ADR-051) il portale (`/portal…`) entra nel realm dei membri, ogni altra pagina in quello degli
// operatori; `realm` lo sceglie in modo esplicito. Solo profilo enterprise.
export const dynamic = "force-dynamic";

export function GET(req: NextRequest) {
  const params = req.nextUrl.searchParams;
  const realm = parseRealm(params.get("realm")) ?? realmForPage(safeReturnTo(params.get("returnTo")));
  return enterpriseOnly((bff) => handleLogin(req, bff), realm);
}
