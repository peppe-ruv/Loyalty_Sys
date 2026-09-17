package io.loyaltyhub.rulesengine.domain;

import java.util.List;

/** Porta verso il backoffice/CMS (ContentProvider): restituisce solo le versioni pubblicate. */
public interface RuleSource {
    List<Rule> publishedRulesFor(String actionType);
}
