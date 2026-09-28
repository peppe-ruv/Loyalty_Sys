import { leaves, removeNode, type UiGroup, type UiLeaf } from "./conditions";

// Importo nella bozza di una nuova campagna (BO-06 «2 · Quando», Q-432). La bozza parte dalla condizione d'esempio
// `data.amount ≥ 1` e da un effetto «per importo» (`PER_AMOUNT` su `data.amount`): con un'azione senza `amount` la
// campagna non scatterebbe. Puro e testato.

export const AMOUNT_FIELD = "data.amount";

/** La condizione d'esempio con cui parte la bozza, ancora com'era. */
export function isAmountExample(leaf: UiLeaf): boolean {
  return leaf.field === AMOUNT_FIELD && leaf.cmp === "gte" && Number(leaf.value) === 1;
}

/** Le condizioni usano `data.amount` (l'esempio o una condizione aggiunta dall'operatore). */
export function conditionUsesAmount(tree: UiGroup): boolean {
  return leaves(tree).some((l) => l.field === AMOUNT_FIELD);
}

/** Un effetto usa `data.amount` (per esempio `PER_AMOUNT` con `amountField`). */
export function effectUsesAmount(effectsJson: string): boolean {
  return effectsJson.includes(AMOUNT_FIELD);
}

/** C'è ancora la condizione d'esempio. */
export function hasAmountExample(tree: UiGroup): boolean {
  return leaves(tree).some(isAmountExample);
}

/** Toglie solo la condizione d'esempio; le condizioni su Importo scritte dall'operatore restano. */
export function dropAmountExample(tree: UiGroup): UiGroup {
  const example = leaves(tree).find(isAmountExample);
  return example ? removeNode(tree, example.id) : tree;
}
