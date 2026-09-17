"use client";
/**
 * Vista "Andamenti" del backoffice (RF-123): incorpora il cruscotto Apache Superset con l'SDK ufficiale; il token
 * ospite arriva dall'endpoint /api/bi/guest-token (rinnovato automaticamente dall'SDK), filtri nativi abilitati,
 * nessuna barra di Superset. In caso di indisponibilità mostra un messaggio e non blocca il resto del pannello.
 */
import React, { useEffect, useRef, useState } from "react";
import { embedDashboard } from "@superset-ui/embedded-sdk";

export default function Analytics() {
  const ref = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [dashboard, setDashboard] = useState<"kpi">("kpi");

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const first = await fetch(`/api/bi/guest-token?dashboard=${dashboard}`, { credentials: "include" });
        if (!first.ok) throw new Error(`guest token ${first.status}`);
        const info = await first.json();
        if (cancelled || !ref.current) return;
        ref.current.innerHTML = "";
        await embedDashboard({
          id: info.dashboardId,
          supersetDomain: info.supersetUrl,
          mountPoint: ref.current,
          fetchGuestToken: async () => {
            const r = await fetch(`/api/bi/guest-token?dashboard=${dashboard}`, { credentials: "include" });
            return (await r.json()).token;
          },
          dashboardUiConfig: { hideTitle: true, hideChartControls: false, filters: { expanded: false }, urlParams: {} },
          iframeSandboxExtras: ["allow-top-navigation", "allow-popups-to-escape-sandbox"],
        });
        const iframe = ref.current.querySelector("iframe");
        if (iframe) { iframe.style.width = "100%"; iframe.style.height = "calc(100vh - 140px)"; iframe.style.border = "0"; }
      } catch (e: any) {
        setError(`Cruscotto non disponibile: ${e.message}. Le operazioni del backoffice non sono impattate.`);
      }
    })();
    return () => { cancelled = true; };
  }, [dashboard]);

  return (
    <div style={{ padding: "16px 24px" }}>
      <header style={{ display: "flex", alignItems: "baseline", gap: 16, marginBottom: 12 }}>
        <h1 style={{ margin: 0 }}>Andamenti del programma</h1>
        <select value={dashboard} onChange={(e) => setDashboard(e.target.value as "kpi")}>
          <option value="kpi">KPI del programma</option>
        </select>
        <a href={process.env.NEXT_PUBLIC_GRAFANA_URL || "http://localhost:3005/dashboards"} target="_blank" rel="noreferrer" style={{ marginLeft: "auto" }}>Metriche tecniche (Grafana) ↗</a>
      </header>
      {error && <p role="alert" style={{ color: "#b42323" }}>{error}</p>}
      <div ref={ref} />
    </div>
  );
}
