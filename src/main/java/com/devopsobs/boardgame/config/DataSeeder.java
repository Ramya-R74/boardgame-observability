package com.devopsobs.boardgame.config;

import com.devopsobs.boardgame.domain.BoardGame;
import com.devopsobs.boardgame.domain.Review;
import com.devopsobs.boardgame.repo.BoardGameRepository;
import com.devopsobs.boardgame.repo.ReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final BoardGameRepository games;
    private final ReviewRepository reviews;

    public DataSeeder(BoardGameRepository games, ReviewRepository reviews) {
        this.games = games;
        this.reviews = reviews;
    }

    @Override
    public void run(String... args) {
        if (games.count() > 0) {
            log.info("Catalogue already seeded, skipping ({} games)", games.count());
            return;
        }
        List<BoardGame> seed = List.of(
                new BoardGame("Catan", "strategy", 4, 4),
                new BoardGame("Ticket to Ride", "family", 3, 5),
                new BoardGame("Gloomhaven", "campaign", 9, 4),
                new BoardGame("Codenames", "party", 2, 8),
                new BoardGame("Wingspan", "engine-builder", 5, 5),
                new BoardGame("Pandemic", "co-op", 5, 4));
        games.saveAll(seed);
        reviews.save(new Review(seed.get(0).getId(), 5, "Still the gateway game."));
        reviews.save(new Review(seed.get(2).getId(), 4, "Incredible, but the setup time is brutal."));
        log.info("Seeded catalogue with {} games", seed.size());
    }
}
