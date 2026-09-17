package com.devopsobs.boardgame.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.time.Instant;

@Entity
@Table(name = "review")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "board_game_id", nullable = false)
    private Long boardGameId;

    @Min(1) @Max(5)
    @Column(nullable = false)
    private int rating;

    @NotBlank
    @Size(max = 500)
    @Column(nullable = false, length = 500)
    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Review() { }

    public Review(Long boardGameId, int rating, String comment) {
        this.boardGameId = boardGameId;
        this.rating = rating;
        this.comment = comment;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getBoardGameId() { return boardGameId; }
    public void setBoardGameId(Long boardGameId) { this.boardGameId = boardGameId; }
    public int getRating() { return rating; }
    public void setRating(int rating) { this.rating = rating; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public Instant getCreatedAt() { return createdAt; }
}
