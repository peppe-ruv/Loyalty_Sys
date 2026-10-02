import { isEnterprise } from "@/lib/auth/config";
import { codespaceConfigured } from "@/lib/hub/codespace";
import { enterpriseShowcaseUrl } from "@/lib/hub/links";
import { EnterpriseBox, EnterpriseNotConfigured } from "./EnterpriseBox";

// HUB-01, F2-DIST-09, ADR-049, Q-674, ADR-051: riquadro «Modalità Enterprise» della demo pubblica. Server component:
// legge LH_HUB_ENTERPRISE_URL e la presenza di token e codespace a runtime (mai NEXT_PUBLIC; il token non esce dal
// server, al client arriva solo l'origine della vetrina). Nascosto senza URL valido e nel profilo enterprise (la
// vetrina non deve puntare a sé stessa). Senza token o codespace: nota «disponibile su richiesta» (Q-662).
export function EnterpriseShowcase({ env = process.env }: { env?: Readonly<Record<string, string | undefined>> }) {
  if (isEnterprise(env)) return null;
  const url = enterpriseShowcaseUrl(env);
  if (url === null) return null;
  return codespaceConfigured(env) ? <EnterpriseBox url={url} /> : <EnterpriseNotConfigured />;
}
