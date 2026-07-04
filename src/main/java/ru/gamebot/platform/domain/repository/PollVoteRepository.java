package ru.gamebot.platform.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Poll;
import ru.gamebot.platform.domain.model.PollVote;

import java.util.List;
import java.util.Optional;

public interface PollVoteRepository extends JpaRepository<PollVote, Long> {
    boolean existsByPollAndUser(Poll poll, AppUser user);
    long countByPollAndOptionIndex(Poll poll, int optionIndex);
    long countByPoll(Poll poll);
    List<PollVote> findAllByPoll(Poll poll);
    Optional<PollVote> findByPollAndUser(Poll poll, AppUser user);

    @Query("SELECT v.optionIndex, COUNT(v) FROM PollVote v WHERE v.poll = :poll GROUP BY v.optionIndex ORDER BY COUNT(v) DESC")
    List<Object[]> countByOptionForPoll(@Param("poll") Poll poll);
}
