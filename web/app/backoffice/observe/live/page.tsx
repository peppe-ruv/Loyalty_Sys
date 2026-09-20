import { PageHeader } from "@/components/bo/primitives";
import { PipelineStrip } from "@/components/observe/PipelineStrip";
import { EventRail } from "@/components/observe/EventRail";

// BO-24 Flusso eventi live (docs/08 §BO-24): rende visibile l'architettura a eventi. Dati via SSE
// (insight /v1/stream/events, diretto) + /v1/pipeline/status.
export default function LivePage() {
  return (
    <div>
      <PageHeader
        title="Flusso eventi live"
        subtitle="Ogni azione attraversa i 5 topic: azione → valutazione → effetti → fatti. Passa su una riga per seguire lo stesso correlationId."
      />
      <div className="mt-4 space-y-4">
        <PipelineStrip />
        <EventRail />
      </div>
    </div>
  );
}
