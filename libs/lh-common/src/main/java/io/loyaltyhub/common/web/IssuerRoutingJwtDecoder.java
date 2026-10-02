package io.loyaltyhub.common.web;

import com.nimbusds.jwt.JWTParser;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.text.ParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Due emittenti, due decoder (ADR-051 decisione 6): legge il {@code iss} del token senza fidarsene e passa il token al
 * decoder di quell'emittente, che verifica firma (JWKS del suo realm), {@code iss}, {@code aud} e scadenza. Un emittente
 * che non è nell'elenco è rifiutato come un token non valido: nessun decoder di ripiego.
 */
public final class IssuerRoutingJwtDecoder implements JwtDecoder {

    private final Map<String, JwtDecoder> byIssuer;

    /** @param byIssuer emittente esatto (senza {@code /} finale) → decoder con i validatori di quell'emittente */
    public IssuerRoutingJwtDecoder(Map<String, JwtDecoder> byIssuer) {
        Map<String, JwtDecoder> copy = new LinkedHashMap<>();
        byIssuer.forEach((issuer, decoder) -> copy.put(normalize(issuer), decoder));
        this.byIssuer = Map.copyOf(copy);
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        String issuer;
        try {
            Object claim = JWTParser.parse(token).getJWTClaimsSet().getClaim("iss");
            issuer = claim instanceof String s ? s : null;
        } catch (ParseException | RuntimeException e) {
            throw new BadJwtException("token non leggibile", e);
        }
        JwtDecoder decoder = issuer == null ? null : byIssuer.get(normalize(issuer));
        if (decoder == null) {
            throw new BadJwtException("emittente non ammesso");
        }
        return decoder.decode(token);
    }

    private static String normalize(String issuer) {
        return issuer.trim().replaceAll("/+$", "");
    }
}
