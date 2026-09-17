'use client';

import { useState } from 'react';
import {
  DataTable,
  EntityProfile,
  KpiTabsChart,
  RuleList,
  SectionForm,
  Timeline,
} from '@loyalty-hub/backoffice-design-system/components';
import { buildWorkflowInfo, formatValue } from '@loyalty-hub/backoffice-design-system';
import type { FilterChip, Rule, SortDirection } from '@loyalty-hub/backoffice-design-system';
import {
  CONDITION_TYPES,
  DEFAULT_CHIPS,
  FILTER_ATTRIBUTES,
  KPI_DEFINITIONS,
  KPI_SERIES,
  MEMBER_ROWS,
  RULES,
  type MemberRow,
} from '../../lib/mock';

const PAGE_SIZE = 5;

export default function Pattern() {
  const [chips, setChips] = useState<FilterChip[]>(DEFAULT_CHIPS);
  const [sort, setSort] = useState<{ key: string; direction: SortDirection }>({ key: 'punti', direction: 'desc' });
  const [page, setPage] = useState(1);
  const [rules, setRules] = useState<Rule[]>(RULES);
  const [kpi, setKpi] = useState('attivi');

  // I filtri del playground sono volutamente semplici: quello che conta è che la chip sia una
  // frase e che il contatore dica sempre «filtrate di totali».
  const filtered = MEMBER_ROWS.filter((row) => {
    const livelli = chips.find((chip) => chip.attribute === 'livello');
    const minimo = chips.find((chip) => chip.attribute === 'punti');
    const okLivello =
      livelli === undefined ||
      (Array.isArray(livelli.value) &&
        livelli.value.some((value) => typeof value === 'object' && value !== null && 'label' in value && String((value as { label: string }).label).toUpperCase() === row.livello));
    const okPunti = minimo === undefined || typeof minimo.value !== 'number' || row.punti >= minimo.value;
    return okLivello && okPunti;
  });

  const ordered = [...filtered].sort((a, b) => {
    const key = sort.key as keyof MemberRow;
    const av = a[key];
    const bv = b[key];
    const cmp = typeof av === 'number' && typeof bv === 'number' ? av - bv : String(av).localeCompare(String(bv), 'it');
    return sort.direction === 'asc' ? cmp : -cmp;
  });

  const rows = ordered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  return (
    <>
      <section className="pg-hero">
        <h1>I pattern del backoffice</h1>
        <p className="pg-lead">
          Sono gli stessi componenti che il backoffice usa: cambiarli qui li cambia ovunque. Le regole che seguono non
          sono estetiche — sono quelle che tengono comprensibile un programma configurato da persone diverse in mesi
          diversi.
        </p>
      </section>

      <section className="pg-section">
        <h2>Lista con filtri</h2>
        <p className="lh-muted">
          Ogni filtro è una frase, mai un identificativo tecnico; il contatore dice sempre quante righe passano i
          filtri sul totale; le colonne portano l’unità nell’intestazione; le righe inattive restano visibili ma
          spente.
        </p>
        <DataTable<MemberRow>
          caption="Membri"
          columns={[
            { key: 'membro', header: 'Membro', sortable: true },
            { key: 'livello', header: 'Livello', sortable: true },
            { key: 'punti', header: 'Punti attivi', unit: 'punti', align: 'end', sortable: true, render: (row) => formatValue(row.punti, { format: 'integer' }) },
            { key: 'spesa', header: 'Spesa 365 giorni', unit: 'EUR', align: 'end', sortable: true, render: (row) => formatValue(row.spesa, { format: 'currency', unit: 'EUR' }) },
            { key: 'ultimaAzione', header: 'Ultima azione' },
          ]}
          rows={rows}
          total={MEMBER_ROWS.length}
          filtered={filtered.length}
          filters={{ attributes: FILTER_ATTRIBUTES, chips, onChange: (next) => { setChips(next); setPage(1); } }}
          sort={{ ...sort, onChange: (key, direction) => { setSort({ key, direction }); } }}
          pagination={{ page, pageSize: PAGE_SIZE, onChange: (next) => { setPage(next); } }}
          rowActions={(row) => [{ label: 'Apri', onSelect: () => { window.alert(`Nel backoffice si aprirebbe la scheda di ${row.membro}.`); } }]}
          preferencesKey="playground.membri"
          emptyState={{
            title: 'Nessun membro con questi filtri',
            description: 'Togli una chip per allargare la ricerca: il contatore in alto dice quanti ne restano fuori.',
          }}
        />
      </section>

      <section className="pg-section">
        <h2>Regole: si leggono come frasi</h2>
        <p className="lh-muted">
          Dentro una regola le condizioni sono in AND, fra regole è OR, e l’operatore è sempre scritto: nessuno deve
          indovinare come si combinano.
        </p>
        <RuleList rules={rules} conditionTypes={CONDITION_TYPES} onChange={setRules} />
      </section>

      <section className="pg-section">
        <h2>Form a sezioni fisse</h2>
        <p className="lh-muted">
          Le sezioni escono sempre nello stesso ordine — tipo, impostazioni, logica, limiti — e lo stato è l’ultima
          scelta, con il testo che ne spiega l’effetto e le sole transizioni permesse al ruolo.
        </p>
        <SectionForm
          breadcrumb={[{ label: 'Campagne', href: '/pattern' }, { label: 'Bolletta puntuale' }]}
          sections={[
            { kind: 'limits', title: 'Limiti', help: 'Oltre il limite la campagna smette di premiare, non si disattiva.', content: <p className="lh-muted">Massimo 1 accredito al mese per membro.</p> },
            { kind: 'type', title: 'Tipo', help: 'Decide quali trigger sono disponibili più avanti nel form.', content: <p className="lh-muted">Campagna premiante su evento.</p> },
            { kind: 'logic', title: 'Logica', help: 'Le condizioni valgono al momento dell’evento, non al salvataggio.', content: <RuleList rules={rules} conditionTypes={CONDITION_TYPES} onChange={setRules} /> },
          ]}
          workflow={buildWorkflowInfo('inReview', { roles: ['reviewer'], legalRequired: false })}
          dependencies={{
            usedBy: [{ id: 'seg-1', label: 'Segmento «Clienti puntuali»', kind: 'segment' }],
            uses: [{ id: 'wallet-premio', label: 'Punti premio', kind: 'wallet' }],
            onNavigate: () => { window.alert('Nel backoffice si aprirebbe l’oggetto collegato.'); },
          }}
          primaryAction={{ label: 'Salva', onClick: () => { window.alert('Qui il backoffice salverebbe la bozza.'); } }}
          secondaryAction={{ label: 'Annulla', onClick: () => { /* nel playground non c'è niente da annullare */ } }}
          destructiveWarning={{ message: 'Cambiare la logica non tocca i punti già accreditati: il registro è immutabile.' }}
        />
      </section>

      <section className="pg-section">
        <h2>KPI: un grafico, il periodo precedente sempre</h2>
        <p className="lh-muted">
          Un numero senza il suo periodo precedente non dice se le cose vanno meglio. La serie tratteggiata è il
          periodo precedente; la definizione del KPI è quella del warehouse, non una riscrittura.
        </p>
        <KpiTabsChart
          kpis={KPI_DEFINITIONS}
          series={KPI_SERIES}
          selected={kpi}
          onSelect={setKpi}
          period={{ from: '2027-01-01', to: '2027-01-14', granularity: 'day' }}
          onPeriodChange={() => { /* il playground ha un periodo solo */ }}
        />
      </section>

      <section className="pg-section">
        <h2>Scheda membro e timeline</h2>
        <p className="lh-muted">
          A sinistra chi è, a destra che cosa ha fatto. Ogni movimento mostra il saldo <em>dopo</em>: la domanda del
          call center non è «quanto ha» ma «perché ha questo».
        </p>
        <EntityProfile
          initials="8F"
          status={{ label: 'Attivo', tone: 'ok' }}
          identity={[
            { label: 'Identificativo', value: 'sub-8f2a…', copyable: true },
            { label: 'Adesione', value: '2025-03-14', relative: 'due anni fa' },
            { label: 'Canale di adesione', value: 'app' },
          ]}
          chips={[
            { label: 'Livello Top', kind: 'tier' },
            { label: 'Clienti puntuali', kind: 'segment' },
            { label: 'Rischio basso', kind: 'risk' },
          ]}
          tierPanel={{
            current: { id: 'top', label: 'Top', kind: 'tier' },
            progressToNext: { value: 18_420, threshold: 25_000, unit: 'punti' },
          }}
          actions={[
            { label: 'Accredito manuale', onSelect: () => { window.alert('Richiede commento e finisce a registro.'); }, requiresComment: true },
            { label: 'Blocca punti', onSelect: () => { window.alert('Il blocco passa dal registro, non dalla scheda.'); }, requiresComment: true, fourEyesAboveThreshold: true },
          ]}
          tabs={[
            {
              key: 'timeline',
              label: 'Timeline',
              content: (
                <Timeline
                  filters={{ attributes: [], chips: [], onChange: () => { /* nessun filtro nel playground */ } }}
                  events={[
                    {
                      id: 'ev-1',
                      at: new Date(Date.now() - 86_400_000).toISOString(),
                      type: 'MOVIMENTO',
                      title: 'Accredito «Bolletta puntuale»',
                      meta: [{ label: 'Campagna', value: 'Bolletta puntuale' }, { label: 'Importo', value: '84,30 €' }],
                      balanceAfter: [{ wallet: { id: 'premio', label: 'Punti premio', kind: 'wallet' }, value: 18_420 }],
                    },
                    {
                      id: 'ev-2',
                      at: new Date(Date.now() - 6 * 86_400_000).toISOString(),
                      type: 'DECISIONE',
                      title: 'Offerta presentata in app',
                      meta: [{ label: 'Motivo', value: 'propensione 0,62 · canale preferito' }],
                    },
                    {
                      id: 'ev-3',
                      at: new Date(Date.now() - 20 * 86_400_000).toISOString(),
                      type: 'PREMIO',
                      title: 'Riscatto «Buono manutenzione caldaia»',
                      meta: [{ label: 'Stato', value: 'Consegnato' }],
                      balanceAfter: [{ wallet: { id: 'premio', label: 'Punti premio', kind: 'wallet' }, value: 16_252 }],
                    },
                  ]}
                />
              ),
              badge: 3,
            },
            {
              key: 'consensi',
              label: 'Consensi',
              content: (
                <p className="lh-muted">
                  Marketing: concesso il 14/03/2025 · Profilazione: concesso il 14/03/2025. Ogni consenso porta la sua
                  base giuridica e la prova; una revoca si propaga a decisioni e consegne.
                </p>
              ),
            },
          ]}
        />
      </section>
    </>
  );
}
