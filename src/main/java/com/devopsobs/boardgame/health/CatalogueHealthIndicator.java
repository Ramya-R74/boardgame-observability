package com.devopsobs.boardgame.health;

import com.devopsobs.boardgame.repo.BoardGameRepository;
import com.devopsobs.boardgame.web.ChaosController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * A health check that means something. "the process is up" is not a health check -
 * this one actually touches the database and reports the round-trip time, so a
 * degraded DB surfaces in /actuator/health and therefore in Heartbeat/Uptime.
 */
@Component("catalogue")
public class CatalogueHealthIndicator implements HealthIndicator {

    private final BoardGameRepository games;
    private final ObjectProvider<ChaosController> chaos;

    public CatalogueHealthIndicator(BoardGameRepository games, ObjectProvider<ChaosController> chaos) {
        this.games = games;
        this.chaos = chaos;
    }

    @Override
    public Health health() {
        ChaosController c = chaos.getIfAvailable();
        if (c != null && c.isUnhealthy()) {
            return Health.down().withDetail("reason", "chaos: forced unhealthy").build();
        }
        long start = System.nanoTime();
        try {
            long count = games.count();
            long ms = (System.nanoTime() - start) / 1_000_000;
            Health.Builder builder = ms > 500 ? Health.status("DEGRADED") : Health.up();
            return builder.withDetail("catalogue_size", count)
                          .withDetail("query_time_ms", ms)
                          .build();
        } catch (Exception ex) {
            return Health.down(ex).build();
        }
    }
}
