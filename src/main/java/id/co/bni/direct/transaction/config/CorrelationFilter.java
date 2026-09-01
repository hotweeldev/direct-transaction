package id.co.bni.direct.transaction.config;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request one id, from the inbound line to the response.
 *
 * <p>Reuses the caller's {@code X-Correlation-Id} when there is one, so an id minted by
 * the gateway or by a calling service survives the hop; generates a UUID otherwise. The id
 * goes into the MDC, which puts it on every log line through the {@code %X{correlationId}}
 * pattern in {@code logback-spring.xml}, and onto the response header so a caller can
 * quote it in a ticket.
 *
 * <p>The MDC is thread-local. Nothing here runs work on another thread; if that changes,
 * the executor must copy the MDC or the id silently stops appearing.
 *
 * <p>This is a deliberately small stand-in for the hw-auth-shared starter's filter, which
 * also does body capture and the 12-field BASE_LOG envelope. Adopt the starter when this
 * service gains auth rather than growing this class toward it.
 */
@Component
public class CorrelationFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger inboundLogger = LoggerFactory.getLogger("InboundLogger");

    private final CorrelationProperties properties;

    public CorrelationFilter(CorrelationProperties properties) {
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        // After the CORS filter, before anything that logs.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String correlationId = request.getHeader(properties.getHeaderName());
        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }

        response.setHeader(properties.getHeaderName(), correlationId);
        MDC.put(CorrelationContext.MDC_CORRELATION_ID, correlationId);
        MDC.put(CorrelationContext.MDC_REQUEST_PATH, request.getRequestURI());

        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            if (shouldLog(request)) {
                inboundLogger.info("{}|@|{}|@|{}|@|{}|@|{}|@|{}",
                        properties.getServiceName(),
                        correlationId,
                        request.getMethod(),
                        request.getRequestURI(),
                        response.getStatus(),
                        (System.nanoTime() - startNanos) / 1_000_000L);
            }
            // Tomcat reuses request threads, so a left-behind entry would tag the next
            // request with the previous one's id.
            MDC.clear();
        }
    }

    private boolean shouldLog(HttpServletRequest request) {
        String path = request.getRequestURI();
        return properties.getExcludedPaths().stream().noneMatch(path::startsWith);
    }
}
