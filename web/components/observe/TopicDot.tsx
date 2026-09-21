import { familyColorVar, type LiveFamily } from "@/lib/realtime/sse";

/** Pallino colorato del topic in base alla famiglia (docs/08 §BO-24). */
export function TopicDot({ family }: { family: LiveFamily }) {
  return (
    <span
      className="inline-block h-2.5 w-2.5 flex-shrink-0 rounded-full"
      style={{ backgroundColor: familyColorVar(family) }}
      title={family}
      aria-hidden
    />
  );
}
