/**
 * Pattern 12 e 13 — EntityProfile e Timeline (LG-26, LG-35, LG-37).
 *
 * La scheda membro è a due colonne: a sinistra chi è (identità, livello, chip), a destra che cosa
 * ha fatto. La timeline mostra il saldo **dopo** ogni movimento, perché la domanda vera del call
 * center non è «quanto ha adesso» ma «perché adesso ha questo».
 */

import { useState } from 'react';
import type { EntityProfileProps, TimelineProps } from '../member.js';
import { TIMELINE_EVENT_LABELS } from '../member.js';
import { formatRelativeDays, formatValue } from '../format.js';
import type { EntityRef } from '../common.js';
import { Button, Chip, EntityLink, type Tone } from './primitives.js';

const CHIP_TONE: Record<'tier' | 'segment' | 'risk', Tone> = { tier: 'accent', segment: 'neutral', risk: 'warn' };

export function Timeline({ events, onLoadMore }: TimelineProps) {
  if (events.length === 0) return <p className="lh-muted">Nessun evento nel periodo scelto.</p>;
  return (
    <>
      <ol className="lh-timeline">
        {events.map((event) => (
          <li key={event.id} className="lh-timeline__item">
            <p className="lh-row">
              <Chip>{TIMELINE_EVENT_LABELS[event.type]}</Chip>
              <strong>{event.title}</strong>
              <span className="lh-muted">{formatRelativeDays(event.at)}</span>
            </p>
            {event.meta.length > 0 ? (
              <p className="lh-muted">
                {event.meta.map((meta) => `${meta.label}: ${meta.value}`).join(' · ')}
              </p>
            ) : null}
            {event.balanceAfter && event.balanceAfter.length > 0 ? (
              <p className="lh-mono">
                Saldo dopo:{' '}
                {event.balanceAfter
                  .map((balance) => `${balance.wallet.label} ${formatValue(balance.value, { format: 'integer' })}`)
                  .join(' · ')}
              </p>
            ) : null}
            {event.audit ? (
              <p className="lh-muted">
                Correzione manuale di {event.audit.user}: {event.audit.reason}
              </p>
            ) : null}
          </li>
        ))}
      </ol>
      {onLoadMore ? <Button onClick={onLoadMore}>Carica altri</Button> : null}
    </>
  );
}

export function EntityProfile({ initials, status, identity, chips, tabs, actions, tierPanel, onNavigate }: EntityProfileProps & { onNavigate?: ((ref: EntityRef) => void) | undefined }) {
  const [active, setActive] = useState(tabs[0]?.key ?? '');
  const tab = tabs.find((item) => item.key === active) ?? tabs[0];

  return (
    <div className="lh-profile">
      <aside className="lh-card">
        <header className="lh-row">
          <span className="lh-avatar" aria-hidden="true">{initials}</span>
          <Chip tone={status.tone === 'muted' ? 'neutral' : status.tone}>{status.label}</Chip>
        </header>

        <div className="lh-row" style={{ marginTop: 'var(--lh-space-3)' }}>
          {chips.map((chip) => (
            <Chip key={`${chip.kind}-${chip.label}`} tone={CHIP_TONE[chip.kind]}>
              {chip.label}
            </Chip>
          ))}
        </div>

        <dl className="lh-identity">
          {identity.map((field) => (
            <div key={field.label} style={{ display: 'contents' }}>
              <dt>{field.label}</dt>
              <dd className={field.copyable === true ? 'lh-mono' : undefined}>
                {field.value}
                {field.relative !== undefined ? <span className="lh-muted"> ({field.relative})</span> : null}
              </dd>
            </div>
          ))}
        </dl>

        {tierPanel ? (
          <section style={{ marginTop: 'var(--lh-space-4)' }}>
            <h3 className="lh-title">Livello</h3>
            <p className="lh-row">
              <EntityLink entity={tierPanel.current} onNavigate={onNavigate} />
              {tierPanel.lockedUntil === undefined ? null : (
                <span className="lh-muted" title="Livello congelato">🔒 fino al {tierPanel.lockedUntil}</span>
              )}
            </p>
            <div
              className="lh-progress"
              role="progressbar"
              aria-valuenow={tierPanel.progressToNext.value}
              aria-valuemin={0}
              aria-valuemax={tierPanel.progressToNext.threshold}
            >
              <div
                className="lh-progress__fill"
                style={{
                  width: `${String(Math.min(100, Math.round((tierPanel.progressToNext.value / Math.max(1, tierPanel.progressToNext.threshold)) * 100)))}%`,
                }}
              />
            </div>
            <p className="lh-muted">
              {formatValue(tierPanel.progressToNext.value, { format: 'integer' })} di{' '}
              {formatValue(tierPanel.progressToNext.threshold, { format: 'integer' })} {tierPanel.progressToNext.unit}
            </p>
          </section>
        ) : null}

        <div className="lh-row" style={{ marginTop: 'var(--lh-space-4)' }}>
          {actions.map((action) => (
            <Button
              key={action.label}
              onClick={action.onSelect}
              title={
                action.fourEyesAboveThreshold === true
                  ? 'Oltre la soglia serve una seconda approvazione'
                  : action.requiresComment === true
                    ? 'Richiede un commento a registro'
                    : undefined
              }
            >
              {action.label}
              {action.requiresComment === true ? ' *' : ''}
            </Button>
          ))}
        </div>
      </aside>

      <section className="lh-card">
        <div className="lh-tabs" role="tablist">
          {tabs.map((item) => (
            <button
              key={item.key}
              type="button"
              role="tab"
              className="lh-tab"
              aria-selected={item.key === tab?.key}
              onClick={() => { setActive(item.key); }}
            >
              {item.label}
              {item.badge === undefined ? '' : ` (${String(item.badge)})`}
            </button>
          ))}
        </div>
        {tab?.content}
      </section>
    </div>
  );
}
