/**
 * Pattern 14 — ImportFlow (LG-44, LG-45): caricamento file, storico dei job e rimappatura
 * dei riferimenti non risolti nella destinazione.
 */

import type { EntityRef, IsoDateTime } from './common.js';

export const IMPORT_TYPES = [
  'members',
  'segmentMembers',
  'unitsAdd',
  'unitsRemove',
  'collectionValues',
  'campaignsJson',
  'achievementsJson',
  'eventSchemasJson',
  'configBundle',
] as const;

export type ImportType = (typeof IMPORT_TYPES)[number];

export type ImportStatus = 'queued' | 'running' | 'done' | 'failed';

export interface ImportJob {
  id: string;
  type: ImportType;
  fileName: string;
  createdAt: IsoDateTime;
  status: ImportStatus;
  records?: number;
  rejected?: number;
}

export interface ImportReviewItem {
  ref: EntityRef;
  status: 'imported' | 'actionRequired';
  /** Riferimenti non risolti nella destinazione, da rimappare (LG-44). */
  missing?: Array<{ field: string; expected: EntityRef; candidates: EntityRef[] }>;
}

export interface ImportFlowProps {
  type: ImportType;
  accept: string[];
  maxSizeMb: number;
  guideHref: string;
  sampleFileHref: string;
  history: ImportJob[];
  onUpload: (file: File) => Promise<ImportJob>;
  review?: {
    items: ImportReviewItem[];
    onRemap: (item: ImportReviewItem, field: string, to: EntityRef) => void;
  };
}

/** True se l'import richiede un intervento prima di essere confermato (LG-44). */
export function needsReview(items: readonly ImportReviewItem[]): boolean {
  return items.some((item) => item.status === 'actionRequired');
}
