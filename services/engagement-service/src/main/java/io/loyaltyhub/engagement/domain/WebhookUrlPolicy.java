package io.loyaltyhub.engagement.domain;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * URL ammessi per i webhook (docs/servizi/engagement-service.md §5, docs/11 "anti-SSRF"): solo {@code https://};
 * {@code http://localhost} solo se {@code allowHttpLocalhost} (profilo {@code local}); con {@code blockPrivateAddresses}
 * (profilo {@code free}) sono rifiutati gli indirizzi privati, di loopback, link-local e simili, sia come letterali al
 * salvataggio sia dopo la risoluzione DNS al momento dell'invio.
 */
public record WebhookUrlPolicy(boolean allowHttpLocalhost, boolean blockPrivateAddresses) {

    public static final int MAX_LENGTH = 500;
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final String PRIVATE = "indirizzo locale o privato non ammesso";
    private static final Pattern NUMERIC_LABEL = Pattern.compile("^(0x[0-9a-f]*|[0-9]+)$");
    private static final String OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])";
    private static final Pattern CANONICAL_IPV4 = Pattern.compile("^" + OCTET + "(\\." + OCTET + "){3}$");

    /** Motivo del rifiuto (in italiano, per l'errore di campo) o vuoto se l'URL è ammesso. */
    public Optional<String> problem(String url) {
        if (url == null || url.isBlank()) {
            return Optional.of("obbligatorio");
        }
        if (url.length() > MAX_LENGTH) {
            return Optional.of("al massimo " + MAX_LENGTH + " caratteri");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return Optional.of("URL non valido");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute() || host.isEmpty()) {
            return Optional.of("URL assoluto con host, es. https://example.org/hook");
        }
        if (uri.getRawUserInfo() != null) {
            return Optional.of("credenziali nell'URL non ammesse");
        }
        if (uri.getRawFragment() != null) {
            return Optional.of("frammento (#…) non ammesso");
        }
        // Q-184 DECISA: un host numerico (ultima etichetta di sole cifre o 0x…) vale solo in forma puntata canonica
        // a.b.c.d (0…255, senza zeri iniziali): 2130706433, 0x7f000001, 127.1 o 0177.0.0.1 sono rifiutati al salvataggio
        // invece di scoprire all'invio, dopo la risoluzione, che puntano a un indirizzo bloccato.
        if (numericHost(host) && !CANONICAL_IPV4.matcher(host).matches()) {
            return Optional.of("indirizzo IP in forma non canonica: usa la forma puntata, es. 203.0.113.10");
        }
        boolean local = LOCAL_HOSTS.contains(host);
        if ("http".equals(scheme)) {
            if (!(allowHttpLocalhost && local)) {
                return Optional.of(allowHttpLocalhost ? "solo https:// (http:// solo verso localhost)" : "solo https://");
            }
        } else if (!"https".equals(scheme)) {
            return Optional.of("solo https://");
        }
        if (blockPrivateAddresses) {
            if (local || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
                return Optional.of(PRIVATE);
            }
            Optional<InetAddress> literal = literal(host);
            if (literal.isPresent() && isBlocked(literal.get())) {
                return Optional.of(PRIVATE);
            }
        }
        return Optional.empty();
    }

    /** Indirizzi non raggiungibili da un webhook in {@code free}: loopback, privati, link-local, CGNAT, ULA, multicast. */
    public static boolean isBlocked(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            return first == 0
                    || (first == 100 && second >= 64 && second <= 127)      // 100.64.0.0/10 (CGNAT)
                    || (first == 192 && second == 0 && (b[2] & 0xFF) == 0) // 192.0.0.0/24
                    || (first == 198 && (second == 18 || second == 19))     // 198.18.0.0/15
                    || first >= 240;
        }
        if (a instanceof Inet6Address) {
            return (b[0] & 0xFE) == 0xFC; // fc00::/7 (ULA)
        }
        return false;
    }

    /** Host che un risolutore leggerebbe come IPv4 (ultima etichetta numerica, decimale o esadecimale). */
    static boolean numericHost(String host) {
        if (host.startsWith("[")) {
            return false;
        }
        String h = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        String last = h.substring(h.lastIndexOf('.') + 1);
        return !last.isEmpty() && NUMERIC_LABEL.matcher(last).matches();
    }

    /** Indirizzo IP letterale (senza DNS), se l'host lo è. */
    static Optional<InetAddress> literal(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (!IPV4.matcher(h).matches() && !h.contains(":")) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(h));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }
}
