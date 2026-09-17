import { describe, expect, it } from 'vitest';

import {
  WORKFLOW_STATES,
  WORKFLOW_STATE_HELP,
  WORKFLOW_STATE_LABELS,
  WORKFLOW_TRANSITIONS,
  availableTransitions,
  buildWorkflowInfo,
  canTransition,
  isWorkflowState,
  transitionRequiresComment,
  type WorkflowRole,
  type WorkflowState,
} from './workflow.js';

const asRoles = (...roles: WorkflowRole[]): WorkflowRole[] => roles;

describe('catalogo degli stati', () => {
  it('ogni stato ha etichetta e testo di aiuto', () => {
    for (const state of WORKFLOW_STATES) {
      expect(WORKFLOW_STATE_LABELS[state]).toBeTruthy();
      expect(WORKFLOW_STATE_HELP[state]).toBeTruthy();
    }
  });

  it('la tabella delle transizioni cita solo stati noti', () => {
    for (const rule of WORKFLOW_TRANSITIONS) {
      expect(WORKFLOW_STATES).toContain(rule.from);
      expect(WORKFLOW_STATES).toContain(rule.to);
      expect(rule.roles.length).toBeGreaterThan(0);
    }
  });

  it('riconosce gli stati validi al confine con l’API', () => {
    expect(isWorkflowState('published')).toBe(true);
    expect(isWorkflowState('attivo')).toBe(false);
    expect(isWorkflowState(undefined)).toBe(false);
  });

  it('tutti gli stati sono raggiungibili da Bozza lungo il ciclo di vita completo', () => {
    // Il ciclo attraversa due contesti: prima del via libera di Legal e dopo.
    const contexts = [
      { roles: asRoles('admin', 'legal'), legalRequired: true },
      { roles: asRoles('admin', 'legal'), legalRequired: true, legalApproved: true },
    ];
    const reachable = new Set<WorkflowState>(['draft']);
    let grown = true;
    while (grown) {
      grown = false;
      for (const state of [...reachable]) {
        for (const context of contexts) {
          for (const transition of availableTransitions(state, context)) {
            if (!reachable.has(transition.to)) {
              reachable.add(transition.to);
              grown = true;
            }
          }
        }
      }
    }
    expect([...reachable].sort()).toEqual([...WORKFLOW_STATES].sort());
  });

  it('Bloccato è terminale: nemmeno un amministratore ne esce (DPR 430)', () => {
    expect(availableTransitions('locked', { roles: asRoles('admin', 'legal', 'publisher') })).toEqual([]);
  });
});

describe('filtro per ruolo (RF-43)', () => {
  it('un editor può solo inviare in revisione', () => {
    expect(availableTransitions('draft', { roles: asRoles('editor') }).map((t) => t.to)).toEqual(['inReview']);
    expect(availableTransitions('inReview', { roles: asRoles('editor') })).toEqual([]);
  });

  it('senza ruoli non c’è alcuna transizione', () => {
    expect(availableTransitions('approved', { roles: [] })).toEqual([]);
  });

  it('solo Legal chiude la verifica Legal', () => {
    expect(canTransition('legalReview', 'approved', { roles: asRoles('legal') })).toBe(true);
    expect(canTransition('legalReview', 'approved', { roles: asRoles('publisher') })).toBe(false);
  });
});

describe('verifica Legal (D14, RF-137)', () => {
  const publisher = asRoles('publisher');

  it('gli oggetti che non la richiedono vanno da Approvato a Pubblicato', () => {
    const transitions = availableTransitions('approved', { roles: publisher }).map((t) => t.to);
    expect(transitions).toContain('published');
    expect(transitions).toContain('scheduled');
    expect(transitions).not.toContain('legalReview');
  });

  it('gli oggetti che la richiedono non possono essere pubblicati prima del via libera', () => {
    const context = { roles: publisher, legalRequired: true };
    const transitions = availableTransitions('approved', context).map((t) => t.to);
    expect(transitions).toContain('legalReview');
    expect(transitions).not.toContain('published');
    expect(transitions).not.toContain('scheduled');
  });

  it('dopo il via libera di Legal la pubblicazione si sblocca', () => {
    const context = { roles: publisher, legalRequired: true, legalApproved: true };
    expect(canTransition('approved', 'published', context)).toBe(true);
    expect(canTransition('approved', 'legalReview', context)).toBe(false);
  });
});

describe('commento obbligatorio (RF-140)', () => {
  it('i ritorni indietro e i ritiri lo richiedono', () => {
    expect(transitionRequiresComment('inReview', 'draft')).toBe(true);
    expect(transitionRequiresComment('legalReview', 'draft')).toBe(true);
    expect(transitionRequiresComment('published', 'draft')).toBe(true);
  });

  it('gli avanzamenti no', () => {
    expect(transitionRequiresComment('draft', 'inReview')).toBe(false);
    expect(transitionRequiresComment('scheduled', 'published')).toBe(false);
  });
});

describe('buildWorkflowInfo', () => {
  it('compone la sezione Stato del form con aiuto e transizioni filtrate (LG-06)', () => {
    const info = buildWorkflowInfo('inReview', { roles: asRoles('reviewer') }, { user: 'g.ruvolo', at: '2026-09-17T08:30:00+02:00' });
    expect(info.state).toBe('inReview');
    expect(info.helpText).toBe(WORKFLOW_STATE_HELP.inReview);
    expect(info.by?.user).toBe('g.ruvolo');
    expect(info.transitions.map((t) => t.to).sort()).toEqual(['approved', 'draft']);
  });

  it('omette l’autore quando non è noto', () => {
    const info = buildWorkflowInfo('draft', { roles: asRoles('editor') });
    expect('by' in info).toBe(false);
  });
});
