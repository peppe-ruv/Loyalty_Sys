package io.loyaltyhub.ingestion.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Tetto in byte del corpo di {@code POST /v1/events/batch} (F2-ING-01): il corpo JSON si legge tutto in memoria prima di
 * contare gli elementi, quindi va limitato come i file dell'import. {@code Content-Length} oltre
 * {@code loyaltyhub.ingestion.batch.max-bytes} (default 3 MiB) ⇒ {@code 413} {@code BATCH_BODY_TOO_LARGE} senza leggere
 * nulla; senza {@code Content-Length} (chunked) la lettura si interrompe al tetto e la richiesta è un {@code 400}
 * (corpo non leggibile). SPEC-GAP: Q-371.
 */
@Component
public class BatchBodyLimitFilter extends OncePerRequestFilter {

    static final String PATH = "/v1/events/batch";

    private final long maxBytes;

    public BatchBodyLimitFilter(@Value("${loyaltyhub.ingestion.batch.max-bytes:3145728}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/problem+json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("""
                    {"type":"urn:loyaltyhub:problem:payload-too-large","title":"Richiesta troppo grande","status":413,\
                    "detail":"Il corpo del batch supera %d byte: dividi l'invio in più richieste",\
                    "code":"BATCH_BODY_TOO_LARGE","instance":"%s"}""".formatted(maxBytes, PATH));
            return;
        }
        chain.doFilter(new Capped(request, maxBytes), response);
    }

    /** Richiesta il cui corpo non si può leggere oltre il tetto (per i corpi senza {@code Content-Length}). */
    private static final class Capped extends HttpServletRequestWrapper {
        private final long maxBytes;
        private ServletInputStream stream;

        Capped(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CappedStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }
    }

    private static final class CappedStream extends ServletInputStream {
        private final ServletInputStream in;
        private final long maxBytes;
        private long read;

        CappedStream(ServletInputStream in, long maxBytes) {
            this.in = in;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            if (read > maxBytes) {
                throw new IOException("Corpo del batch oltre " + maxBytes + " byte");
            }
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
