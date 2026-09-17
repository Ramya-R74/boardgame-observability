package com.devopsobs.boardgame.web;

import com.devopsobs.boardgame.domain.BoardGame;
import com.devopsobs.boardgame.domain.Review;
import com.devopsobs.boardgame.service.BoardGameService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/boardgames")
public class BoardGameController {

    private final BoardGameService service;

    public BoardGameController(BoardGameService service) { this.service = service; }

    @GetMapping
    public List<BoardGame> list(@RequestParam(required = false) String category) {
        return service.list(category);
    }

    @GetMapping("/{id}")
    public BoardGame get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<BoardGame> create(@Valid @RequestBody BoardGame game) {
        BoardGame saved = service.create(game);
        return ResponseEntity.created(URI.create("/api/boardgames/" + saved.getId())).body(saved);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @GetMapping("/{id}/reviews")
    public List<Review> reviews(@PathVariable Long id) {
        return service.reviewsFor(id);
    }

    @PostMapping("/{id}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public Review addReview(@PathVariable Long id, @Valid @RequestBody Review review) {
        return service.addReview(id, review);
    }
}
