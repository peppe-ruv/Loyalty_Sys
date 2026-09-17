/**
 * Pattern 6 — SectionForm (LG-06, LG-07, LG-08) e sezione «Stato» del workflow (RF-137).
 *
 * Due regole che il componente impone al posto dei moduli: le sezioni escono sempre nell'ordine
 * di `SECTION_ORDER` qualunque sia l'ordine dell'array, e lo stato è l'ultima sezione, con il
 * testo che ne spiega l'effetto e le sole transizioni permesse al ruolo di chi guarda.
 */

import type { DependenciesPanelProps, FormSection, SectionFormProps } from '../forms.js';
import { isDeletionBlocked, sortSections } from '../forms.js';
import type { WorkflowInfo } from '../workflow.js';
import { WORKFLOW_STATE_LABELS } from '../workflow.js';
import { Button, Chip, EntityLink } from './primitives.js';

export function WorkflowSection({ workflow, onTransition }: { workflow: WorkflowInfo; onTransition?: ((to: string) => void) | undefined }) {
  return (
    <section className="lh-section" aria-labelledby="lh-section-status">
      <h3 className="lh-section__title" id="lh-section-status">
        Stato
        <Chip tone={workflow.state === 'published' ? 'ok' : 'neutral'}>{WORKFLOW_STATE_LABELS[workflow.state]}</Chip>
      </h3>
      <p className="lh-section__help">{workflow.helpText}</p>
      {workflow.by ? (
        <p className="lh-muted">
          Ultimo passaggio: {workflow.by.user} — {workflow.by.at}
        </p>
      ) : null}
      <div className="lh-row">
        {workflow.transitions.length === 0 ? (
          <span className="lh-muted">Nessuna transizione disponibile con i tuoi permessi.</span>
        ) : (
          workflow.transitions.map((transition) => (
            <Button
              key={transition.to}
              variant={transition.to === 'published' ? 'primary' : 'default'}
              onClick={() => onTransition?.(transition.to)}
              title={transition.requiresComment === true ? 'Richiede un commento' : undefined}
            >
              {transition.label}
              {transition.requiresComment === true ? ' *' : ''}
            </Button>
          ))
        )}
      </div>
    </section>
  );
}

/** Riquadro «Usato da / Usa» (RF-141): l'eliminazione è bloccata finché qualcuno lo usa. */
export function DependenciesPanel({ usedBy, uses, onNavigate }: DependenciesPanelProps) {
  const blocked = isDeletionBlocked({ usedBy });
  return (
    <aside className="lh-card" aria-label="Dipendenze">
      <h3 className="lh-title">Usato da</h3>
      {usedBy.length === 0 ? (
        <p className="lh-muted">Nessuno: si può eliminare.</p>
      ) : (
        <ul>
          {usedBy.map((ref) => (
            <li key={ref.id}>
              <EntityLink entity={ref} onNavigate={onNavigate} />
            </li>
          ))}
        </ul>
      )}
      <h3 className="lh-title">Usa</h3>
      {uses.length === 0 ? (
        <p className="lh-muted">Niente.</p>
      ) : (
        <ul>
          {uses.map((ref) => (
            <li key={ref.id}>
              <EntityLink entity={ref} onNavigate={onNavigate} />
            </li>
          ))}
        </ul>
      )}
      {blocked ? <p className="lh-muted">L&apos;eliminazione è bloccata: prima va tolto da chi lo usa.</p> : null}
    </aside>
  );
}

function Section({ section }: { section: FormSection }) {
  return (
    <section className="lh-section">
      <h3 className="lh-section__title">{section.title}</h3>
      {section.help !== undefined ? (
        <p className="lh-section__help">
          {section.help}
          {section.learnMoreHref !== undefined ? <> — <a href={section.learnMoreHref}>approfondisci</a></> : null}
        </p>
      ) : null}
      {section.content}
    </section>
  );
}

export function SectionForm({
  breadcrumb,
  sections,
  workflow,
  dependencies,
  primaryAction,
  secondaryAction,
  destructiveWarning,
  onTransition,
}: SectionFormProps & { onTransition?: ((to: string) => void) | undefined }) {
  return (
    <form
      className="lh-card"
      onSubmit={(event) => {
        event.preventDefault();
        if (primaryAction.disabled !== true) primaryAction.onClick();
      }}
    >
      <nav className="lh-breadcrumb" aria-label="Percorso">
        {breadcrumb.map((crumb, index) => (
          <span key={crumb.label}>
            {index > 0 ? ' / ' : ''}
            {crumb.href === undefined ? crumb.label : <a href={crumb.href}>{crumb.label}</a>}
          </span>
        ))}
      </nav>

      {destructiveWarning ? (
        <p className="lh-warning" role="alert">
          {destructiveWarning.message}
          {destructiveWarning.affectedCount === undefined ? '' : ` — oggetti coinvolti: ${String(destructiveWarning.affectedCount)}`}
        </p>
      ) : null}

      {sortSections(sections).map((section) => (
        <Section key={`${section.kind}-${section.title}`} section={section} />
      ))}

      {dependencies ? <DependenciesPanel {...dependencies} /> : null}

      <WorkflowSection workflow={workflow} onTransition={onTransition} />

      <div className="lh-form-actions">
        {secondaryAction ? (
          <Button onClick={secondaryAction.onClick}>{secondaryAction.label}</Button>
        ) : null}
        <Button type="submit" variant="primary" disabled={primaryAction.disabled === true}>
          {primaryAction.label}
        </Button>
      </div>
    </form>
  );
}
