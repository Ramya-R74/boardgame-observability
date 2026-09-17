package com.devopsobs.boardgame.service;

import com.devopsobs.boardgame.domain.BoardGame;
import com.devopsobs.boardgame.domain.Review;
import com.devopsobs.boardgame.metrics.BusinessMetrics;
import com.devopsobs.boardgame.repo.BoardGameRepository;
import com.devopsobs.boardgame.repo.ReviewRepository;
import com.devopsobs.boardgame.web.NotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class BoardGameService {

    private static final Logger log = LoggerFactory.getLogger(BoardGameService.class);

    private final BoardGameRepository games;
    private final ReviewRepository reviews;
    private final BusinessMetrics metrics;

    public BoardGameService(BoardGameRepository games, ReviewRepository reviews, BusinessMetrics metrics) {
        this.games = games;
        this.reviews = reviews;
        this.metrics = metrics;
    }

    /** @Observed creates a span AND a timer from one annotation. */
    @Observed(name = "boardgame.catalogue.list", contextualName = "list-catalogue")
    @Transactional(readOnly = true)
    public List<BoardGame> list(String category) {
        List<BoardGame> result = (category == null || category.isBlank())
                ? games.findAll()
                : games.findByCategoryIgnoreCase(category);
        log.debug("Catalogue query returned {} games (category={})", result.size(), category);
        return result;
    }

    @Observed(name = "boardgame.catalogue.get")
    @Transactional(readOnly = true)
    public BoardGame get(Long id) {
        return games.findById(id)
                .orElseThrow(() -> new NotFoundException("board game " + id + " not found"));
    }

    @Observed(name = "boardgame.catalogue.create")
    @Transactional
    public BoardGame create(BoardGame game) {
        BoardGame saved = games.save(game);
        metrics.gameCreated(saved.getCategory());
        // Business events deserve INFO. Debug noise does not.
        log.info("Board game created id={} name='{}' category={}",
                saved.getId(), saved.getName(), saved.getCategory());
        return saved;
    }

    @Observed(name = "boardgame.catalogue.delete")
    @Transactional
    public void delete(Long id) {
        BoardGame game = get(id);
        games.delete(game);
        log.info("Board game deleted id={} name='{}'", id, game.getName());
    }

    @Observed(name = "boardgame.reviews.list")
    @Transactional(readOnly = true)
    public List<Review> reviewsFor(Long boardGameId) {
        get(boardGameId); // 404 rather than an empty list for an unknown game
        return reviews.findByBoardGameId(boardGameId);
    }

    @Observed(name = "boardgame.reviews.create")
    @Transactional
    public Review addReview(Long boardGameId, Review review) {
        BoardGame game = get(boardGameId);
        review.setBoardGameId(boardGameId);
        Review saved = reviews.save(review);
        metrics.reviewCreated(game.getCategory(), saved.getRating());
        log.info("Review created id={} boardGameId={} rating={}",
                saved.getId(), boardGameId, saved.getRating());
        return saved;
    }
}
