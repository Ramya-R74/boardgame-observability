package com.devopsobs.boardgame.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;

@Entity
@Table(name = "board_game")
public class BoardGame {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 120)
    @Column(nullable = false, length = 120)
    private String name;

    @NotBlank
    @Size(max = 60)
    @Column(nullable = false, length = 60)
    private String category;

    @Min(1) @Max(10)
    @Column(nullable = false)
    private int difficulty;

    @Min(1) @Max(12)
    @Column(name = "max_players", nullable = false)
    private int maxPlayers;

    protected BoardGame() { }

    public BoardGame(String name, String category, int difficulty, int maxPlayers) {
        this.name = name;
        this.category = category;
        this.difficulty = difficulty;
        this.maxPlayers = maxPlayers;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public int getDifficulty() { return difficulty; }
    public void setDifficulty(int difficulty) { this.difficulty = difficulty; }
    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }
}
