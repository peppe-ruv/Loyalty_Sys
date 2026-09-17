import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import { buildCondition, type ConditionType, type Rule } from '../rules.js';
import { buildFilterChip, type FilterAttribute } from '../filters.js';
import { buildWorkflowInfo } from '../workflow.js';
import type { Column } from '../data-table.js';
import { DataTable } from './DataTable.js';
import { RuleCard } from './RuleCard.js';
import { SectionForm, WorkflowSection } from './SectionForm.js';
import { KpiTabsChart } from './KpiTabsChart.js';
import { Timeline } from './EntityProfile.js';

/**
 * I componenti sono l'unica implementazione dei pattern: questi test provano che rispettino le
 * regole che il catalogo dichiara — contatore della lista, operatori sempre scritti, sezioni in
 * ordine fisso, nessun identificativo tecnico a schermo — non che «rendano qualcosa».
 */

interface Riga {
  id: string;
  nome: string;
  punti: number;
  inactive?: boolean;
}

const COLONNE: Array<Column<Riga>> = [
  { key: 'nome', header: 'Nome', sortable: true },
  { key: 'punti', header: 'Punti attivi', unit: 'punti', align: 'end' },
  { key: 'interna', header: 'Colonna nascosta', hidden: true },
];

const ATTRIBUTO: FilterAttribute = {
  key: 'tier',
  label: 'Livello',
  type: 'entity',
  operators: ['eq', 'in'],
};

function tabella(rows: Riga[], filtered = rows.length, total = 740) {
  return renderToStaticMarkup(
    <DataTable
      caption="Membri"
      columns={COLONNE}
      rows={rows}
      total={total}
      filtered={filtered}
      filters={{
        attributes: [ATTRIBUTO],
        chips: [buildFilterChip(ATTRIBUTO, 'eq', { id: 'plus', label: 'Plus', kind: 'tier' })],
        onChange: vi.fn(),
      }}
      pagination={{ page: 1, pageSize: 25, onChange: vi.fn() }}
      preferencesKey="test"
      emptyState={{ title: 'Nessun membro', description: 'Cambia i filtri o importa i primi membri.' }}
    />,
  );
}

describe('DataTable (LG-04, LG-05)', () => {
  it('mostra il contatore ad anello con la percentuale calcolata da filtrate e totale', () => {
    const html = tabella([{ id: '1', nome: 'Rossi', punti: 1200 }], 618, 740);
    expect(html).toContain('618 (83,5%) di 740');
  });

  it('non mostra le colonne nascoste e scrive l’unità nell’intestazione', () => {
    const html = tabella([{ id: '1', nome: 'Rossi', punti: 1200 }]);
    expect(html).toContain('Punti attivi (punti)');
    expect(html).not.toContain('Colonna nascosta');
  });

  it('la chip del filtro è una frase, non un id', () => {
    const html = tabella([{ id: '1', nome: 'Rossi', punti: 1200 }]);
    expect(html).toContain('Livello: uguale a Plus');
    expect(html).not.toContain('"plus"');
  });

  it('senza righe mostra lo stato vuoto con il primo passo, non «nessun risultato»', () => {
    const html = tabella([]);
    expect(html).toContain('Nessun membro');
    expect(html).toContain('Cambia i filtri o importa i primi membri.');
    expect(html).not.toContain('<tbody>');
  });

  it('ingrigisce le righe inattive', () => {
    const html = tabella([{ id: '1', nome: 'Rossi', punti: 10, inactive: true }]);
    expect(html).toContain('data-inactive="true"');
  });
});

const TIPI: ConditionType[] = [
  { key: 'tier', label: 'Livello', category: 'member', operators: ['in', 'notIn'], valueType: 'entity' },
  { key: 'punti', label: 'Punti attivi (Wallet premio)', category: 'popular', operators: ['gte'], valueType: 'number', unit: 'punti' },
];

function regola(): Rule {
  return {
    id: 'r1',
    name: 'Clienti Plus attivi',
    conditions: [
      buildCondition({ id: 'c1', type: 'tier', operator: 'notIn', value: [{ id: 'elite', label: 'Elite', kind: 'tier' }] }, TIPI),
      buildCondition({ id: 'c2', type: 'punti', operator: 'gte', value: 1000 }, TIPI),
    ],
    effects: [{ id: 'e1', kind: 'addUnits', wallet: { id: 'premio', label: 'Punti premio', kind: 'wallet' } }],
  };
}

describe('RuleCard (LG-13, LG-28, RF-138, RF-139)', () => {
  it('scrive l’operatore fra due condizioni invece di lasciarlo capire', () => {
    const html = renderToStaticMarkup(
      <RuleCard rule={regola()} conditionTypes={TIPI} effectKinds={[]} onChange={vi.fn()} onDuplicate={vi.fn()} onDelete={vi.fn()} />,
    );
    expect(html).toContain('AND');
  });

  it('legge le condizioni come frasi con le label, mai con gli id', () => {
    const html = renderToStaticMarkup(
      <RuleCard rule={regola()} conditionTypes={TIPI} effectKinds={[]} onChange={vi.fn()} onDuplicate={vi.fn()} onDelete={vi.fn()} />,
    );
    expect(html).toContain('Livello');
    expect(html).toContain('non è uno di');
    expect(html).toContain('Elite');
    expect(html).not.toContain('elite"');
  });

  it('mostra gli effetti con il nome del wallet', () => {
    const html = renderToStaticMarkup(
      <RuleCard rule={regola()} conditionTypes={TIPI} effectKinds={[]} onChange={vi.fn()} onDuplicate={vi.fn()} onDelete={vi.fn()} />,
    );
    expect(html).toContain('Punti premio');
  });
});

describe('SectionForm (LG-06, RF-137)', () => {
  const workflow = buildWorkflowInfo('inReview', { roles: ['reviewer'], legalRequired: false });

  it('ordina le sezioni secondo il catalogo, non secondo l’array', () => {
    const html = renderToStaticMarkup(
      <SectionForm
        breadcrumb={[{ label: 'Campagne', href: '/campagne' }, { label: 'Nuova' }]}
        sections={[
          { kind: 'limits', title: 'Limiti', content: <p>limiti</p> },
          { kind: 'type', title: 'Tipo', content: <p>tipo</p> },
          { kind: 'logic', title: 'Logica', content: <p>logica</p> },
        ]}
        workflow={workflow}
        primaryAction={{ label: 'Salva', onClick: vi.fn() }}
      />,
    );
    expect(html.indexOf('Tipo')).toBeLessThan(html.indexOf('Logica'));
    expect(html.indexOf('Logica')).toBeLessThan(html.indexOf('Limiti'));
    // Lo stato è sempre l'ultima sezione (LG-06).
    expect(html.indexOf('Limiti')).toBeLessThan(html.indexOf('lh-section-status'));
  });

  it('l’avviso distruttivo sta sopra, con il numero di oggetti coinvolti', () => {
    const html = renderToStaticMarkup(
      <SectionForm
        breadcrumb={[{ label: 'Segmenti' }]}
        sections={[]}
        workflow={workflow}
        primaryAction={{ label: 'Salva', onClick: vi.fn() }}
        destructiveWarning={{ message: 'Cambiare i criteri svuota le appartenenze correnti.', affectedCount: 1240 }}
      />,
    );
    expect(html).toContain('role="alert"');
    expect(html).toContain('1240');
  });
});

describe('WorkflowSection (RF-137, RF-43)', () => {
  it('mostra solo le transizioni permesse al ruolo e spiega l’effetto dello stato', () => {
    const html = renderToStaticMarkup(
      <WorkflowSection workflow={buildWorkflowInfo('draft', { roles: ['editor'], legalRequired: false })} />,
    );
    expect(html).toContain('Bozza');
    expect(html).not.toContain('Pubblica');
  });

  it('quando il ruolo non può fare nulla lo dice, invece di mostrare bottoni spenti', () => {
    const html = renderToStaticMarkup(
      <WorkflowSection workflow={buildWorkflowInfo('published', { roles: [], legalRequired: false })} />,
    );
    expect(html).toContain('Nessuna transizione disponibile');
  });
});

describe('KpiTabsChart (LG-40, LG-41)', () => {
  it('mostra sempre il confronto con il periodo precedente', () => {
    const html = renderToStaticMarkup(
      <KpiTabsChart
        kpis={[{ key: 'attivi', label: 'Membri attivi', definition: 'Membri con almeno un evento nel periodo.', format: 'integer' }]}
        series={{
          attivi: {
            key: 'attivi',
            current: [{ t: '2027-01-01T00:00:00Z', v: 100 }, { t: '2027-01-02T00:00:00Z', v: 140 }],
            previous: [{ t: '2026-12-01T00:00:00Z', v: 90 }, { t: '2026-12-02T00:00:00Z', v: 100 }],
            currentTotal: 240,
            previousTotal: 190,
          },
        }}
        selected="attivi"
        onSelect={vi.fn()}
        period={{ from: '2027-01-01', to: '2027-01-31', granularity: 'day' }}
        onPeriodChange={vi.fn()}
      />,
    );
    expect(html).toContain('rispetto al periodo precedente');
    expect(html).toContain('+26,3%');
    // La serie precedente è tratteggiata (LG-41).
    expect(html).toContain('stroke-dasharray');
  });
});

describe('Timeline (LG-37)', () => {
  it('mostra il saldo dopo il movimento, che è la domanda vera del call center', () => {
    const html = renderToStaticMarkup(
      <Timeline
        events={[
          {
            id: 'e1',
            at: new Date().toISOString(),
            type: 'MOVIMENTO',
            title: 'Accredito bolletta pagata',
            meta: [{ label: 'Campagna', value: 'Bolletta puntuale' }],
            balanceAfter: [{ wallet: { id: 'premio', label: 'Punti premio', kind: 'wallet' }, value: 11_340 }],
          },
        ]}
        filters={{ attributes: [], chips: [], onChange: vi.fn() }}
      />,
    );
    expect(html).toContain('Saldo dopo');
    // In italiano il separatore delle migliaia compare da cinque cifre in su: 1340 resta «1340»,
    // 11340 diventa «11.340». È la regola di Intl, e il design system non la corregge a mano.
    expect(html).toContain('11.340');
    expect(html).toContain('oggi');
  });
});
