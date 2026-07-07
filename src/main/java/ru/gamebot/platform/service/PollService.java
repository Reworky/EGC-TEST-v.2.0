package ru.gamebot.platform.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Poll;
import ru.gamebot.platform.domain.model.PollVote;
import ru.gamebot.platform.domain.repository.PollRepository;
import ru.gamebot.platform.domain.repository.PollVoteRepository;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PollService {

    private final PollRepository pollRepository;
    private final PollVoteRepository pollVoteRepository;
    private final UserService userService;
    private final ExcTransactionService excTx;

    public List<Poll> findActive() {
        return pollRepository.findAllByClosedFalseOrderByCreatedAtDesc();
    }

    public List<Poll> findAll() {
        return pollRepository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<Poll> findById(Long id) {
        return pollRepository.findById(id);
    }

    @Transactional
    public Poll create(String question, List<String> options, long priceExc, LocalDateTime closesAt) {
        Poll poll = new Poll();
        poll.setQuestion(question);
        poll.setOptionsCsv(String.join("||", options));
        poll.setPriceExc(priceExc);
        poll.setClosesAt(closesAt);
        poll.setCreatedAt(LocalDateTime.now());
        return pollRepository.save(poll);
    }

    public List<String> getOptions(Poll poll) {
        return Arrays.asList(poll.getOptionsCsv().split("\\|\\|"));
    }

    public boolean hasVoted(Poll poll, AppUser user) {
        return pollVoteRepository.existsByPollAndUser(poll, user);
    }

    public long[] getVoteCounts(Poll poll) {
        List<String> options = getOptions(poll);
        long[] counts = new long[options.size()];
        List<Object[]> rows = pollVoteRepository.countByOptionForPoll(poll);
        for (Object[] row : rows) {
            int idx = ((Number) row[0]).intValue();
            long cnt = ((Number) row[1]).longValue();
            if (idx >= 0 && idx < counts.length) counts[idx] = cnt;
        }
        return counts;
    }

    public long totalVotes(Poll poll) {
        return pollVoteRepository.countByPoll(poll);
    }

    public record VoteResult(boolean success, String error) {}

    @Transactional
    public VoteResult castVote(AppUser user, Poll poll, int optionIndex) {
        if (poll.isClosed()) return new VoteResult(false, "Голосование уже завершено.");
        if (poll.getClosesAt() != null && LocalDateTime.now().isAfter(poll.getClosesAt()))
            return new VoteResult(false, "Голосование завершено.");
        if (hasVoted(poll, user)) return new VoteResult(false, "Вы уже проголосовали.");
        List<String> options = getOptions(poll);
        if (optionIndex < 0 || optionIndex >= options.size()) return new VoteResult(false, "Неверный вариант.");
        if (user.getCoins() < poll.getPriceExc()) return new VoteResult(false, "Недостаточно EXC. Нужно: " + poll.getPriceExc());

        user.setCoins(user.getCoins() - poll.getPriceExc());
        userService.save(user);
        excTx.log(user, -poll.getPriceExc(), ExcTransactionService.POLL, "Голосование: " + poll.getQuestion());

        PollVote vote = new PollVote();
        vote.setPoll(poll);
        vote.setUser(user);
        vote.setOptionIndex(optionIndex);
        vote.setCreatedAt(LocalDateTime.now());
        pollVoteRepository.save(vote);

        return new VoteResult(true, null);
    }

    @Transactional
    public Poll close(Poll poll) {
        poll.setClosed(true);
        return pollRepository.save(poll);
    }

    public List<Poll> findExpiredUnclosed() {
        return pollRepository.findAllByClosedFalseAndClosesAtBefore(LocalDateTime.now());
    }
}
