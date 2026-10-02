import type { NextRequest } from "next/server";
import { handleCodespaceGet, handleCodespacePost } from "@/lib/hub/codespace";

// HUB-01, Q-674, ADR-051 decisione 9: stato e avvio del codespace della vetrina dalla demo pubblica. Solo profilo
// `demo` e solo con LH_VETRINA_CODESPACE + LH_VETRINA_GITHUB_TOKEN; la logica sta in lib/hub/codespace.ts.
export const dynamic = "force-dynamic";

export function GET(req: NextRequest) {
  return handleCodespaceGet(req);
}

export function POST(req: NextRequest) {
  return handleCodespacePost(req);
}
