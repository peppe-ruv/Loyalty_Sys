"use client";

import { useSearchParams } from "next/navigation";
import { Tabs } from "@/components/bo/Tabs";
import { PageHeader } from "@/components/bo/primitives";
import { TemplatesTab } from "@/components/bo/messages/TemplatesTab";
import { RulesTab } from "@/components/bo/messages/RulesTab";
import { MessageLogTab } from "@/components/bo/messages/MessageLogTab";

// BO-19 Messaggi (docs/08 §BO-19; F-MSG-01, F-MSG-02): `templates` (segnaposto e anteprima renderizzata), `rules`
// (fatto → template con condizione e interruttore), `log` (messaggi inviati, anche le anteprime e-mail). Lettura per
// tutti; scritture con `content.write` (ADMIN, MARKETING; docs/08 §2), rifiutate comunque dal servizio.
const TABS = [
  { key: "templates", label: "Template" },
  { key: "rules", label: "Regole" },
  { key: "log", label: "Registro" },
];

export default function MessagesPage() {
  const tab = useSearchParams().get("tab") ?? "templates";
  return (
    <div>
      <PageHeader
        title="Messaggi"
        subtitle="Notifiche dell'inbox del portale: template con segnaposto, regole che le fanno partire dai fatti e registro di quanto inviato."
      />
      <Tabs tabs={TABS} current={tab} />
      {tab === "rules" ? <RulesTab /> : tab === "log" ? <MessageLogTab /> : <TemplatesTab />}
    </div>
  );
}
