"use client";

import { useKeepAlive } from "@/lib/hooks/useKeepAlive";

/** Monta il keep-alive gentile nei layout delle aree (docs/07 §8). Non rende nulla. */
export function KeepAlive() {
  useKeepAlive();
  return null;
}
