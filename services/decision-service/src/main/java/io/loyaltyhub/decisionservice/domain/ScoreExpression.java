package io.loyaltyhub.decisionservice.domain;

import java.util.Map;

/** Porta per la strategia di punteggio EXPRESSION: valuta una formula configurata nel backoffice sulle variabili del contesto. */
public interface ScoreExpression {
    double evaluate(String expression, Map<String, Object> variables);
}
