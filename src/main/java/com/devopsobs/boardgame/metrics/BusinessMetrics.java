package com.devopsobs.boardgame.metrics;

import io.micrometer.core.instrument.*;
import com.devopsobs.boardgame.repo.BoardGameRepository;
import com.devopsobs.boardgame.repo.ReviewRepository;
import org.springframework.stereotype.Component;

/**
 * Custom business metrics.
 *
 * Rules applied here (steal these for any service you instrument):
 *  1. Name with a consistent prefix: boardgame_*
 *  2. Counters end in _total, timers describe a unit of work, gauges describe a level.
 *  3. Tag values must be LOW CARDINALITY. "category" has ~6 values -> fine.
 *     Never tag with game id, user id, request id or a raw URL: that is how you
 *     blow up an Elasticsearch/Prometheus index.
 *  4. Business metrics, not just technical ones. "reviews created" tells you the
 *     product is alive; "cpu usage" does not.
 */
@Component
public class BusinessMetrics {

    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry,
                           BoardGameRepository games,
                           ReviewRepository reviews) {
        this.registry = registry;

        // Gauges are read on scrape - keep the supplier cheap.
        Gauge.builder("boardgame.catalogue.size", games, r -> (double) r.count())
                .description("Number of board games currently in the catalogue")
                .register(registry);

        Gauge.builder("boardgame.reviews.size", reviews, r -> (double) r.count())
                .description("Total number of reviews stored")
                .register(registry);
    }

    public void reviewCreated(String category, int rating) {
        Counter.builder("boardgame.reviews.created")
                .description("Reviews submitted by users")
                .tag("category", category)
                .register(registry)
                .increment();

        DistributionSummary.builder("boardgame.review.rating")
                .description("Distribution of star ratings")
                .baseUnit("stars")
                .publishPercentiles(0.5, 0.95)
                .tag("category", category)
                .register(registry)
                .record(rating);
    }

    public void gameCreated(String category) {
        Counter.builder("boardgame.catalogue.created")
                .description("Board games added to the catalogue")
                .tag("category", category)
                .register(registry)
                .increment();
    }

    /** Wrap any block of work in a timer. */
    public Timer timer(String name, String... tags) {
        return Timer.builder(name)
                .tags(tags)
                .publishPercentileHistogram()
                .register(registry);
    }
}
