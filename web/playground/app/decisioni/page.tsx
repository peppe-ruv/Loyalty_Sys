'use client';

import { useMemo, useState } from 'react';
import { Chip } from '@loyalty-hub/backoffice-design-system/components';
import { formatValue } from '@loyalty-hub/backoffice-design-system';
import { decide } from '../../lib/decision';
import { CANDIDATES, POLICY, PROFILES } from '../../lib/mock';

/**
 * Simulatore del motore decisionale: stessi vincoli, stessi codici motivo, dati finti.
 * Il valore della pagina è lo scarto motivato — è lì che si capisce perché il cliente non ha
 * ricevuto l'offerta, che è la domanda che il marketing fa davvero.
 */
export default function Decisioni() {
  const [profileId, setProfileId] = useState(PROFILES[0]?.id ?? '');
  const [hour, setHour] = useState(11);
  const [spent, setSpent] = useState(0);
  const [maxActions, setMaxActions] = useState(POLICY.maxArbitratedPerEvent);
  const [budget, setBudget] = useState(POLICY.dailyUnitsBudget);

  const profile = PROFILES.find((p) => p.id === profileId) ?? PROFILES[0];

  const decision = useMemo(() => {
    if (!profile) return null;
    return decide(CANDIDATES, profile, { ...POLICY, maxArbitratedPerEvent: maxActions, dailyUnitsBudget: budget }, {
      hour,
      unitsGrantedToday: spent,
    });
  }, [profile, hour, spent, maxActions, budget]);

  if (!profile || !decision) return <p>Nessun profilo configurato.</p>;

  return (
    <>
      <section className="pg-hero">
        <h1>Che cosa deciderebbe il motore</h1>
        <p className="pg-lead">
          Un evento premiante arriva per un membro. Il motore mette insieme il contesto del cliente, gli effetti delle
          campagne e le offerte del catalogo, applica la policy e sceglie. Cambia i parametri e guarda come cambiano
          scelte e scarti.
        </p>
      </section>

      <section className="lh-card pg-section">
        <div className="pg-controls">
          <label className="pg-field">
            <span>Profilo del membro</span>
            <select value={profileId} onChange={(event) => { setProfileId(event.target.value); }}>
              {PROFILES.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.label}
                </option>
              ))}
            </select>
          </label>
          <label className="pg-field">
            <span>Ora del giorno: {String(hour)}:00</span>
            <input type="range" min={0} max={23} value={hour} onChange={(event) => { setHour(Number(event.target.value)); }} />
          </label>
          <label className="pg-field">
            <span>Unità già concesse oggi</span>
            <input
              type="number"
              min={0}
              step={1000}
              value={spent}
              onChange={(event) => { setSpent(Math.max(0, Number(event.target.value))); }}
            />
          </label>
          <label className="pg-field">
            <span>Budget giornaliero (0 = nessuno)</span>
            <input
              type="number"
              min={0}
              step={1000}
              value={budget}
              onChange={(event) => { setBudget(Math.max(0, Number(event.target.value))); }}
            />
          </label>
          <label className="pg-field">
            <span>Azioni arbitrate per evento</span>
            <input
              type="number"
              min={0}
              max={5}
              value={maxActions}
              onChange={(event) => { setMaxActions(Math.max(0, Number(event.target.value))); }}
            />
          </label>
        </div>

        <div className="lh-row" style={{ marginTop: 'var(--lh-space-4)' }}>
          <Chip tone="accent">Livello {profile.tier}</Chip>
          <Chip tone={profile.riskLevel === 'LOW' ? 'ok' : profile.riskLevel === 'CRITICAL' ? 'bad' : 'warn'}>
            Rischio {profile.riskLevel}
          </Chip>
          <Chip tone={profile.consents.marketing === true ? 'ok' : 'bad'}>
            Consenso marketing: {profile.consents.marketing === true ? 'sì' : 'no'}
          </Chip>
          <Chip>Canale preferito: {profile.preferredChannel}</Chip>
          <Chip>
            Contatti 7 giorni:{' '}
            {Object.entries(profile.contacts7d)
              .map(([channel, count]) => `${channel} ${String(count)}`)
              .join(' · ')}
          </Chip>
        </div>
      </section>

      <div className="pg-outcome">
        <section className="lh-card">
          <h2 className="lh-title">Scelte</h2>
          {decision.chosen.length === 0 ? (
            <p className="lh-muted">Nessuna azione: con questi parametri il motore non propone nulla.</p>
          ) : (
            <ul className="lh-stack" style={{ listStyle: 'none', padding: 0 }}>
              {decision.chosen.map((item) => (
                <li key={item.candidate.id} className="lh-condition" style={{ alignItems: 'flex-start' }}>
                  <div>
                    <p className="lh-row">
                      <Chip tone={item.contractual ? 'accent' : 'ok'}>{item.candidate.action}</Chip>
                      <strong>{item.candidate.reference}</strong>
                    </p>
                    <p className="lh-muted">
                      {item.contractual
                        ? 'Azione contrattuale: applicata sempre, non arbitrata e non limitata dal budget.'
                        : `Punteggio ${String(item.score)}${item.channel === null ? '' : ` · canale ${item.channel}`} — ${item.reasons.join('; ')}`}
                    </p>
                  </div>
                </li>
              ))}
            </ul>
          )}
          <p className="lh-muted">
            Unità del budget: {formatValue(decision.unitsBefore, { format: 'integer' })} →{' '}
            {formatValue(decision.unitsAfter, { format: 'integer' })}
            {budget > 0 ? ` di ${formatValue(budget, { format: 'integer' })}` : ' (nessun tetto)'}
          </p>
        </section>

        <section className="lh-card">
          <h2 className="lh-title">Scarti, con il motivo</h2>
          {decision.rejected.length === 0 ? (
            <p className="lh-muted">Nessuno scarto.</p>
          ) : (
            <ul className="lh-stack" style={{ listStyle: 'none', padding: 0 }}>
              {decision.rejected.map((item) => (
                <li key={item.candidate.id} className="lh-condition" style={{ alignItems: 'flex-start' }}>
                  <div>
                    <p className="lh-row">
                      <Chip tone="warn">{item.reasonCode}</Chip>
                      <strong>{item.candidate.reference}</strong>
                    </p>
                    <p className="lh-muted">{item.detail}</p>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      <section className="pg-section">
        <h2>Che cosa provare</h2>
        <ul>
          <li>
            Sposta l’ora dopo le 21: i messaggi finiscono in <code>QUIET_HOURS</code>, le offerte in app no.
          </li>
          <li>
            Scegli il cliente senza consenso marketing: resta solo l’azione contrattuale, con{' '}
            <code>CONSENT_MISSING</code> su tutto il resto.
          </li>
          <li>
            Scegli il cliente molto contattato: i canali sono esauriti e arriva <code>NO_CHANNEL</code>.
          </li>
          <li>
            Porta le unità già concesse vicino al budget: il premio da 2.000 unità viene scartato con{' '}
            <code>UNITS_BUDGET</code>, ma i punti della campagna restano — sono contrattuali.
          </li>
          <li>
            Scegli il cliente sotto verifica antifrode: <code>RISK_BLOCK</code> ferma tutto il discrezionale.
          </li>
        </ul>
        <p className="lh-muted">
          Il motore vero vive in <code>services/decision-service</code> e questa è una riproduzione ridotta: stessi
          vincoli, stessi codici motivo, nessun effetto.
        </p>
      </section>
    </>
  );
}
