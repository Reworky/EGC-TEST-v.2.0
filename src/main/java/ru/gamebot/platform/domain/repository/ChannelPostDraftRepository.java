package ru.gamebot.platform.domain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.ChannelPostDraft;

public interface ChannelPostDraftRepository extends JpaRepository<ChannelPostDraft, Long> {

    List<ChannelPostDraft> findAllByType(String type);

    long countByStatus(String status);
}
