import { describe, it, expect } from "vitest";
import { execSync } from "child_process";

describe("architecture/imports", () => {
  it("non ci sono importazioni incrociate tra backoffice e portal", () => {
    // Troviamo i file del portal che importano dal backoffice
    let portalImportsBo = "";
    try {
      portalImportsBo = execSync(
        "grep -rnw 'app/portal' 'components/portal' -e '@/app/backoffice' -e '@/components/bo' || true",
        { encoding: "utf8", cwd: __dirname + "/.." }
      );
    } catch (e) {
      // ignore
    }

    // Troviamo i file del backoffice che importano dal portal
    let boImportsPortal = "";
    try {
      boImportsPortal = execSync(
        "grep -rnw 'app/backoffice' 'components/bo' -e '@/app/portal' -e '@/components/portal' || true",
        { encoding: "utf8", cwd: __dirname + "/.." }
      );
    } catch (e) {
      // ignore
    }

    // Debito noto, vedi AUDIT-WEB-1.
    // Questi file nel portal importano QueryState dal backoffice e formano una allowlist.
    // Ogni nuova cross-import deve fallire.
    const allowedPortalImportsBo = [
      "app/portal/invite/page.tsx",
      "app/portal/profile/page.tsx",
      "app/portal/achievements/page.tsx",
      "app/portal/earn/page.tsx",
      "app/portal/play/page.tsx",
      "app/portal/play/[code]/page.tsx",
      "app/portal/page.tsx",
      "app/portal/leaderboard/page.tsx",
      "app/portal/activity/page.tsx",
      "app/portal/my-rewards/page.tsx",
      "app/portal/rewards/page.tsx",
      "app/portal/rewards/[code]/page.tsx",
      "components/portal/profile/ProfileForm.tsx"
    ];

    const unexpectedPortalImportsBo = portalImportsBo
      .split('\n')
      .filter(line => line.trim() !== '')
      .filter(line => {
        // Ignora se la riga proviene da un file nella allowlist ed è solo l'import di QueryState.
        const isAllowedFile = allowedPortalImportsBo.some(allowedFile => line.includes(allowedFile));
        const isQueryState = line.includes('QueryState');
        return !(isAllowedFile && isQueryState);
      });

    expect(unexpectedPortalImportsBo).toEqual([]);
    expect(boImportsPortal.trim()).toBe("");
  });
});
