package com.devopsobs.boardgame.repo;

import com.devopsobs.boardgame.domain.BoardGame;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BoardGameRepository extends JpaRepository<BoardGame, Long> {
    List<BoardGame> findByCategoryIgnoreCase(String category);
}
