package ru.gamebot.platform.domain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.NewsPost;

public interface NewsPostRepository extends JpaRepository<NewsPost, Long> {

    List<NewsPost> findTop5ByActiveTrueOrderByPublishedAtDesc();
}
