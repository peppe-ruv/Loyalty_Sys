"use client";

import { useQuery } from "@tanstack/react-query";
import type { DemoStatus } from "@/lib/api/status";

// Stato aggregato del Demo Hub (docs/07 §8): una sola query condivisa da pannello stato e ingressi.
export const DEMO_STATUS_KEY = ["demo-status"] as const;

async function fetchDemoStatus(): Promise<DemoStatus> {
  const res = await fetch("/api/demo/status", { cache: "no-store" });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return (await res.json()) as DemoStatus;
}

/** `refetchInterval` solo dal pannello stato; gli altri lettori condividono la cache. */
export function useDemoStatus(refetchInterval: number | false = false) {
  return useQuery({ queryKey: DEMO_STATUS_KEY, queryFn: fetchDemoStatus, refetchInterval });
}
