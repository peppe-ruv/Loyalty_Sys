import { NextRequest, NextResponse } from "next/server";
import {
  PERSONA_COOKIE,
  PERSONA_MAX_AGE,
  serializePersona,
  type Persona,
} from "@/lib/persona/cookie";
import { backofficePersonaFromUsername } from "@/lib/persona/cookie";

// Cambio persona (docs/07 §4): scrive il cookie lh_persona. Nessuna login, solo identità simulata.
export const dynamic = "force-dynamic";

export async function POST(req: NextRequest) {
  const body = (await req.json().catch(() => ({}))) as {
    kind?: string;
    memberId?: string;
    username?: string;
  };

  let persona: Persona | null = null;
  if (body.kind === "MEMBER" && typeof body.memberId === "string") {
    persona = { kind: "MEMBER", memberId: body.memberId };
  } else if (body.kind === "BO" && typeof body.username === "string") {
    persona = backofficePersonaFromUsername(body.username);
  }

  if (!persona) {
    return NextResponse.json({ error: "INVALID_PERSONA" }, { status: 400 });
  }

  const res = NextResponse.json({ ok: true, persona });
  res.cookies.set(PERSONA_COOKIE, serializePersona(persona), {
    path: "/",
    maxAge: PERSONA_MAX_AGE,
    sameSite: "lax",
  });
  return res;
}
