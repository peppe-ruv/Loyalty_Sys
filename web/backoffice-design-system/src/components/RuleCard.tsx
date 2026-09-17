/**
 * Pattern 7 e 8 — ConditionRow e RuleCard (LG-12..LG-16, RF-138, RF-139).
 *
 * Due invarianti che il componente non lascia decidere ai moduli: l'operatore fra due righe è
 * **sempre scritto** (dentro la regola AND, fra regole OR) e le condizioni si leggono come frasi
 * costruite dalle chip, mai come identificativi tecnici.
 */

import type { Condition, ConditionType, Effect, LogicalOperator, Rule, RuleCardProps } from '../rules.js';
import { CONDITION_JOIN, EFFECT_LABELS, RULE_JOIN, buildDisplayChips, findConditionType } from '../rules.js';
import { Button, Chip, EntityLink } from './primitives.js';

export interface ConditionViewProps {
  condition: Condition;
  types: readonly ConditionType[];
  /** Operatore mostrato sotto la riga, verso la successiva (LG-28). */
  joinWithNext?: string | undefined;
  onDelete?: (() => void) | undefined;
}

export function ConditionRow({ condition, types, joinWithNext, onDelete }: ConditionViewProps) {
  // Le chip risolte sono la verità; se mancano si ricostruiscono dal tipo, così una condizione
  // arrivata da un'API vecchia resta leggibile invece di mostrare l'id.
  const type = findConditionType(condition.type, types);
  const chips = condition.displayChips.length > 0 ? condition.displayChips : buildDisplayChips(condition, type);
  return (
    <>
      <div className="lh-condition">
        <span>
          {chips.map((chip, index) =>
            chip.ref ? (
              <EntityLink key={`${chip.text}-${String(index)}`} entity={chip.ref} />
            ) : (
              <span key={`${chip.text}-${String(index)}`}>{index > 0 ? ' ' : ''}{chip.text}</span>
            ),
          )}
        </span>
        {onDelete ? (
          <Button variant="quiet" onClick={onDelete} title="Rimuovi la condizione">
            Rimuovi
          </Button>
        ) : null}
      </div>
      {joinWithNext !== undefined ? <p className="lh-join">{joinWithNext}</p> : null}
    </>
  );
}

export function EffectRow({ effect }: { effect: Effect }) {
  const target = effect.wallet ?? effect.reward;
  return (
    <div className="lh-effect">
      <strong>{EFFECT_LABELS[effect.kind]}</strong>
      {target ? <EntityLink entity={target} /> : null}
      {effect.formula ? <code className="lh-mono">{effect.formula.expression}</code> : null}
      {effect.attribute !== undefined ? <span>{effect.attribute}</span> : null}
    </div>
  );
}

export function RuleCard({ rule, joinWithNext, conditionTypes, onChange, onDuplicate, onDelete }: RuleCardProps) {
  const removeCondition = (id: string) => {
    onChange({ ...rule, conditions: rule.conditions.filter((condition) => condition.id !== id) });
  };
  return (
    <>
      <article className="lh-card">
        <header className="lh-table-head">
          <div>
            <h3 className="lh-title">{rule.name}</h3>
            {rule.description !== undefined ? <p className="lh-muted">{rule.description}</p> : null}
          </div>
          <span className="lh-row">
            <Button variant="quiet" onClick={onDuplicate}>Duplica</Button>
            <Button variant="destructive" onClick={onDelete}>Elimina</Button>
          </span>
        </header>

        <h4 className="lh-muted">Se</h4>
        {rule.conditions.length === 0 ? (
          <p className="lh-muted">Nessuna condizione: la regola vale per tutti.</p>
        ) : (
          rule.conditions.map((condition, index) => (
            <ConditionRow
              key={condition.id}
              condition={condition}
              types={conditionTypes}
              joinWithNext={index < rule.conditions.length - 1 ? CONDITION_JOIN : undefined}
              onDelete={() => { removeCondition(condition.id); }}
            />
          ))
        )}

        <h4 className="lh-muted">Allora</h4>
        {rule.effects.length === 0 ? (
          <p className="lh-muted">Nessun effetto: la regola non fa nulla.</p>
        ) : (
          rule.effects.map((effect) => <EffectRow key={effect.id} effect={effect} />)
        )}
      </article>
      {joinWithNext !== undefined ? (
        <p className="lh-join">
          <Chip>{joinWithNext}</Chip>
        </p>
      ) : null}
    </>
  );
}

/** Elenco di regole con l'operatore fra una e l'altra già scritto (RF-138). */
export function RuleList({
  rules,
  conditionTypes,
  join = RULE_JOIN,
  onChange,
}: {
  rules: Rule[];
  conditionTypes: ConditionType[];
  /** Operatore fra una regola e l'altra: di norma OR, come vuole RF-138. */
  join?: LogicalOperator;
  onChange: (rules: Rule[]) => void;
}) {
  return (
    <div className="lh-stack">
      {rules.map((rule, index) => (
        <RuleCard
          key={rule.id}
          rule={rule}
          // Con exactOptionalPropertyTypes «assente» e «undefined» non sono la stessa cosa:
          // sull'ultima regola la proprietà non va passata affatto.
          {...(index < rules.length - 1 ? { joinWithNext: join } : {})}
          conditionTypes={conditionTypes}
          effectKinds={[]}
          onChange={(updated) => { onChange(rules.map((r) => (r.id === updated.id ? updated : r))); }}
          onDuplicate={() => {
            onChange([...rules, { ...rule, id: `${rule.id}-copia`, name: `${rule.name} (copia)` }]);
          }}
          onDelete={() => { onChange(rules.filter((r) => r.id !== rule.id)); }}
        />
      ))}
    </div>
  );
}
