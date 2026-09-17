package com.devopsobs.boardgame.repo;

import com.devopsobs.boardgame.domain.Review;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findByBoardGameId(Long boardGameId);
    long countByBoardGameId(Long boardGameId);
}
