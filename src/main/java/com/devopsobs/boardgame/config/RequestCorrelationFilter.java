package com.devopsobs.boardgame.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.UUID;

/**
 * Two jobs:
 *
 *  1. Correlation id. Accepts an inbound X-Request-Id (so a load balancer or an
 *     upstream service can propagate one), otherwise mints a UUID. Pushed into
 *     MDC so EVERY log line in this request carries it, and echoed back in the
 *     response header so a user can hand you the id from a screenshot.
 *
 *  2. One structured access-log event per request, with ECS field names and a
 *     duration. This is what you build the "requests per second / p95 latency /
 *     error rate" Kibana dashboard on top of.
 *
 * Note the MDC keys use dots (http.request.method) so the ECS encoder writes
 * them as proper nested ECS fields, not as ad-hoc labels.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("access");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            requestId = UUID.randomUUID().toString();
        }

        long start = System.nanoTime();
        MDC.put("http.request.id", requestId);
        MDC.put("http.request.method", request.getMethod());
        MDC.put("url.path", request.getRequestURI());
        MDC.put("client.ip", clientIp(request));
        MDC.put("user_agent.original", nullSafe(request.getHeader("User-Agent")));
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            int status = response.getStatus();

            // The matched route, NOT the raw URI. /api/boardgames/{id} instead of
            // /api/boardgames/8231 - keeps the field low cardinality and groupable.
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            MDC.put("http.route", route != null ? route.toString() : "UNMATCHED");
            MDC.put("http.response.status_code", String.valueOf(status));
            MDC.put("event.duration_ms", String.valueOf(durationMs));
            MDC.put("event.outcome", status >= 500 ? "failure" : status >= 400 ? "unknown" : "success");

            if (status >= 500) {
                ACCESS_LOG.error("{} {} -> {} in {}ms", request.getMethod(),
                        request.getRequestURI(), status, durationMs);
            } else if (status >= 400) {
                ACCESS_LOG.warn("{} {} -> {} in {}ms", request.getMethod(),
                        request.getRequestURI(), status, durationMs);
            } else {
                ACCESS_LOG.info("{} {} -> {} in {}ms", request.getMethod(),
                        request.getRequestURI(), status, durationMs);
            }
            MDC.clear();
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return nullSafe(request.getRemoteAddr());
    }

    private String nullSafe(String v) { return v == null ? "-" : v; }
}
