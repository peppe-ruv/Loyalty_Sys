"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { type ConnectionState, type LiveEvent, type LiveFamily, type LiveFilters, streamUrl } from "./sse";

const BUFFER_MAX = 500;
const POLL_INTERVAL_MS = 3000;
const MAX_SSE_ERRORS = 3;

interface UseLiveEvents {
  events: LiveEvent[];
  state: ConnectionState;
  paused: boolean;
  pendingCount: number;
  pause: () => void;
  resume: () => void;
  clear: () => void;
}

/**
 * Flusso eventi live (docs/07 §3): EventSource diretto a insight; dopo 3 errori consecutivi passa a
 * polling di {@code /v1/events} ("live ridotto"). Buffer 500 righe, pausa con contatore dei nuovi.
 */
export function useLiveEvents(filters: LiveFilters, enabled = true): UseLiveEvents {
  const [events, setEvents] = useState<LiveEvent[]>([]);
  const [state, setState] = useState<ConnectionState>("disconnected");
  const [paused, setPaused] = useState(false);
  const [pendingCount, setPendingCount] = useState(0);

  const buffer = useRef<LiveEvent[]>([]);
  const seen = useRef<Set<string>>(new Set());
  const pausedRef = useRef(false);
  const filterKey = JSON.stringify(filters);

  const add = useCallback((ev: LiveEvent) => {
    if (!ev.eventId || seen.current.has(ev.eventId)) return;
    seen.current.add(ev.eventId);
    buffer.current = [ev, ...buffer.current];
    if (buffer.current.length > BUFFER_MAX) {
      const removed = buffer.current.slice(BUFFER_MAX);
      buffer.current = buffer.current.slice(0, BUFFER_MAX);
      removed.forEach((r) => seen.current.delete(r.eventId));
    }
    if (pausedRef.current) {
      setPendingCount((n) => n + 1);
    } else {
      setEvents([...buffer.current]);
    }
  }, []);

  const pause = useCallback(() => {
    pausedRef.current = true;
    setPaused(true);
  }, []);

  const resume = useCallback(() => {
    pausedRef.current = false;
    setPaused(false);
    setPendingCount(0);
    setEvents([...buffer.current]);
  }, []);

  const clear = useCallback(() => {
    buffer.current = [];
    seen.current.clear();
    setPendingCount(0);
    setEvents([]);
  }, []);

  useEffect(() => {
    if (!enabled || typeof window === "undefined") return;

    let source: EventSource | null = null;
    let pollTimer: ReturnType<typeof setInterval> | null = null;
    let errorCount = 0;
    let closed = false;

    const startPolling = () => {
      if (pollTimer) return;
      setState("reduced");
      const poll = async () => {
        try {
          const res = await fetch("/api/lh/insight/v1/events?limit=100", { cache: "no-store" });
          if (!res.ok) {
            setState("disconnected");
            return;
          }
          const body = (await res.json()) as { items?: PolledEvent[] };
          // Il polling arriva dal più recente: invertiamo così i più vecchi entrano per primi nel buffer.
          [...(body.items ?? [])].reverse().forEach((row) => add(toLiveEvent(row)));
          setState("reduced");
        } catch {
          setState("disconnected");
        }
      };
      void poll();
      pollTimer = setInterval(poll, POLL_INTERVAL_MS);
    };

    const openStream = () => {
      try {
        source = new EventSource(streamUrl(filters));
      } catch {
        startPolling();
        return;
      }
      source.onopen = () => {
        errorCount = 0;
        setState("live");
      };
      source.addEventListener("lh-event", (e: MessageEvent) => {
        try {
          add(JSON.parse(e.data) as LiveEvent);
        } catch {
          // riga malformata ignorata
        }
      });
      source.onerror = () => {
        if (closed) return;
        errorCount += 1;
        if (errorCount >= MAX_SSE_ERRORS) {
          source?.close();
          source = null;
          startPolling();
        } else {
          setState("disconnected");
        }
      };
    };

    openStream();

    return () => {
      closed = true;
      source?.close();
      if (pollTimer) clearInterval(pollTimer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterKey, enabled, add]);

  return { events, state, paused, pendingCount, pause, resume, clear };
}

interface PolledEvent {
  eventId: string;
  topic: string;
  family: LiveFamily;
  shortType: string;
  memberId?: string | null;
  correlationId?: string | null;
  eventTime?: string | null;
  receivedAt?: string | null;
}

function toLiveEvent(row: PolledEvent): LiveEvent {
  return {
    eventId: row.eventId,
    topic: row.topic,
    family: row.family,
    shortType: row.shortType,
    memberId: row.memberId,
    correlationId: row.correlationId,
    time: row.eventTime ?? row.receivedAt ?? null,
    summary: row.shortType,
  };
}
