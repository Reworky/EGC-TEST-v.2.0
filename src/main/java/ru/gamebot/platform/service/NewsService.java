package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.NewsPost;
import ru.gamebot.platform.domain.repository.NewsPostRepository;

@Service
@RequiredArgsConstructor
public class NewsService {

    private final NewsPostRepository newsPostRepository;

    public List<NewsPost> latestNews() {
        return newsPostRepository.findTop5ByActiveTrueOrderByPublishedAtDesc();
    }

    @Transactional
    public NewsPost createPost(String title, String body) {
        NewsPost post = new NewsPost();
        post.setTitle(title);
        post.setBody(body);
        post.setActive(true);
        post.setPublishedAt(LocalDateTime.now());
        return newsPostRepository.save(post);
    }
}
