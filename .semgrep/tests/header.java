// Fixture di semgrep --test per .semgrep/rules/header.yml. `ruleid:` = deve segnalare, `ok:` = non deve segnalare.
package io.loyaltyhub.fixture;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

class HeaderFixture {

    private static final String NOSNIFF = "X-Content-Type-Options";

    // ---- ammessi -------------------------------------------------------------------------------------------------

    @GetMapping("/a")
    ResponseEntity<String> constants(@PathVariable String id) {
        // ok: lh-header-valore-da-richiesta
        return ResponseEntity.ok().header(NOSNIFF, "nosniff").body(id);
    }

    @GetMapping("/b/{id}")
    ResponseEntity<byte[]> contentDispositionBuilder(@PathVariable String id) {
        HttpHeaders headers = new HttpHeaders();
        // ok: lh-header-valore-da-richiesta
        headers.setContentDisposition(ContentDisposition.attachment().filename("import-" + id + ".csv").build());
        // ok: lh-header-valore-da-richiesta
        headers.set(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(id).build().toString());
        return ResponseEntity.ok().headers(headers).body(new byte[0]);
    }

    void retryAfter(HttpServletResponse response, long retryAfterMs) {
        // ok: lh-header-valore-da-richiesta
        response.setHeader("Retry-After", String.valueOf(Math.max(1, (retryAfterMs + 999) / 1000)));
    }

    @GetMapping("/c")
    void parsedNumber(HttpServletResponse response, @RequestParam String page) {
        // ok: lh-header-valore-da-richiesta
        response.setHeader("X-Page", String.valueOf(Integer.parseInt(page)));
    }

    @GetMapping("/d")
    void stripped(HttpServletResponse response, @RequestHeader("X-Trace") String trace) {
        // ok: lh-header-valore-da-richiesta
        response.setHeader("X-Trace", trace.replaceAll("[\\r\\n]", ""));
        // ok: lh-header-valore-da-richiesta
        response.setHeader("X-Trace-2", sanitizeHeader(trace));
    }

    @GetMapping("/e")
    void encoded(HttpServletResponse response, @RequestParam String name) {
        // ok: lh-header-valore-da-richiesta
        response.setHeader("X-Name", URLEncoder.encode(name, StandardCharsets.UTF_8));
    }

    // ---- vietati -------------------------------------------------------------------------------------------------

    @GetMapping("/f")
    void pathVariable(HttpServletResponse response, @PathVariable("id") String id) {
        // ruleid: lh-header-valore-da-richiesta
        response.setHeader("X-Id", id);
    }

    @GetMapping("/g")
    ResponseEntity<String> requestParamInContentDisposition(@RequestParam String code) {
        // ruleid: lh-header-valore-da-richiesta
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + code + ".csv\"").body("x");
    }

    void requestApi(HttpServletRequest request, HttpServletResponse response) {
        // ruleid: lh-header-valore-da-richiesta
        response.addHeader("X-Echo", request.getHeader("X-Correlation-Id"));
        // ruleid: lh-header-valore-da-richiesta
        response.setHeader("Location", "/next?to=" + request.getParameter("to"));
    }

    @GetMapping("/h")
    void headerName(HttpServletResponse response, @RequestParam String name) {
        // ruleid: lh-header-valore-da-richiesta
        response.setHeader(name, "1");
    }

    @GetMapping("/i")
    void headersObject(@RequestParam String value) {
        HttpHeaders headers = new HttpHeaders();
        // ruleid: lh-header-valore-da-richiesta
        headers.set("X-Value", value);
    }

    @GetMapping("/j")
    void viaLookup(HttpServletResponse response, @PathVariable String id, Repo repo) {
        String code = repo.get(id).code();
        // ruleid: lh-header-valore-da-richiesta
        response.setHeader("X-Code", code);
    }

    private static String sanitizeHeader(String value) {
        return value.replaceAll("[\\r\\n]", "");
    }

    interface Repo { Item get(String id); }
    interface Item { String code(); }
}
