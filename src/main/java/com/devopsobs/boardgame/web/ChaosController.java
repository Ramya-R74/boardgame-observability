package com.devopsobs.boardgame.web;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Failure injection. This is what makes the project a LEARNING project rather than
 * a demo: you cannot practise observability without something to observe going wrong.
 *
 * Guarded by chaos.enabled, which is false unless you set it. Never ship this on.
 */
@RestController
@RequestMapping("/api/chaos")
@ConditionalOnProperty(name = "chaos.enabled", havingValue = "true")
public class ChaosController {

    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);

    private final MeterRegistry registry;
    private final List<byte[]> leak = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean unhealthy = new AtomicBoolean(false);

    public ChaosController(MeterRegistry registry) { this.registry = registry; }

    /** Adds latency. Watch p95 move in the Kibana latency panel. */
    @GetMapping("/latency")
    public Map<String, Object> latency(@RequestParam(defaultValue = "1500") long ms) throws InterruptedException {
        long capped = Math.min(ms, 30_000);
        log.warn("CHAOS: injecting {}ms of latency", capped);
        Thread.sleep(capped);
        return Map.of("injected_latency_ms", capped);
    }

    /** Throws for a percentage of calls. Drives your error-rate SLO burn. */
    @GetMapping("/error")
    public Map<String, Object> error(@RequestParam(defaultValue = "100") int rate) {
        if (ThreadLocalRandom.current().nextInt(100) < rate) {
            log.error("CHAOS: injected failure (rate={}%)", rate);
            throw new IllegalStateException("Injected chaos failure");
        }
        return Map.of("status", "survived", "rate", rate);
    }

    /** Returns a chosen HTTP status - handy for testing alerting thresholds. */
    @GetMapping("/status/{code}")
    public void status(@PathVariable int code) {
        log.warn("CHAOS: returning status {}", code);
        throw new ResponseStatusException(HttpStatus.valueOf(code), "Injected status " + code);
    }

    /** Allocates 10MB per call and never frees it. Watch JVM heap in Metricbeat. */
    @PostMapping("/memory-leak")
    public Map<String, Object> memoryLeak(@RequestParam(defaultValue = "10") int mb) {
        for (int i = 0; i < mb; i++) leak.add(new byte[1024 * 1024]);
        log.warn("CHAOS: leaked {}MB, total retained {}MB", mb, leak.size());
        return Map.of("retained_mb", leak.size());
    }

    @DeleteMapping("/memory-leak")
    public Map<String, Object> releaseMemory() {
        int freed = leak.size();
        leak.clear();
        log.warn("CHAOS: released {}MB", freed);
        return Map.of("released_mb", freed);
    }

    /** Burns CPU on the request thread. Saturation signal. */
    @GetMapping("/cpu")
    public Map<String, Object> cpu(@RequestParam(defaultValue = "5") int seconds) {
        long until = System.currentTimeMillis() + Math.min(seconds, 60) * 1000L;
        log.warn("CHAOS: burning CPU for {}s", seconds);
        double sink = 0;
        while (System.currentTimeMillis() < until) sink += Math.sqrt(ThreadLocalRandom.current().nextDouble());
        return Map.of("burned_seconds", seconds, "sink", sink);
    }

    /** Flips the custom health indicator so readiness starts failing. */
    @PostMapping("/unhealthy")
    public Map<String, Object> toggleUnhealthy(@RequestParam boolean on) {
        unhealthy.set(on);
        log.error("CHAOS: health indicator forced to {}", on ? "DOWN" : "UP");
        return Map.of("unhealthy", on);
    }

    /** Emits a burst of log lines - use it to test Filebeat backpressure and ILM rollover. */
    @PostMapping("/log-storm")
    public Map<String, Object> logStorm(@RequestParam(defaultValue = "1000") int lines) {
        int capped = Math.min(lines, 100_000);
        for (int i = 0; i < capped; i++) {
            log.info("CHAOS log storm line {}/{} payload={}", i, capped, UUID.randomUUID());
        }
        registry.counter("boardgame.chaos.log_storm.lines").increment(capped);
        return Map.of("lines_emitted", capped);
    }

    public boolean isUnhealthy() { return unhealthy.get(); }
}
