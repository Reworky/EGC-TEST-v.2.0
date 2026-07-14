package ru.gamebot.platform.api.controller;

import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.PollDto;
import ru.gamebot.platform.api.dto.ShopActionResponseDto;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Poll;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.PollService;

@RestController
@RequestMapping("/api/polls")
@RequiredArgsConstructor
public class PollController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final PollService pollService;
    private final AppUserRepository appUserRepository;

    @Data
    public static class VoteRequest {
        private int optionIndex;
    }

    @GetMapping
    public ResponseEntity<List<PollDto>> polls(@AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        List<PollDto> result = pollService.findActive().stream().map(p -> toDto(p, user)).toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/vote")
    public ResponseEntity<ShopActionResponseDto> vote(
            @PathVariable Long id, @AuthenticationPrincipal Long telegramId, @RequestBody VoteRequest body) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Poll poll = pollService.findById(id).orElse(null);
        if (poll == null) {
            return ResponseEntity.notFound().build();
        }
        PollService.VoteResult result = pollService.castVote(user, poll, body.getOptionIndex());
        return ResponseEntity.ok(ShopActionResponseDto.builder()
                .success(result.success())
                .message(result.success() ? "Голос принят!" : result.error())
                .build());
    }

    private PollDto toDto(Poll poll, AppUser user) {
        return PollDto.builder()
                .id(poll.getId())
                .question(poll.getQuestion())
                .options(pollService.getOptions(poll))
                .voteCounts(pollService.getVoteCounts(poll))
                .totalVotes(pollService.totalVotes(poll))
                .priceExc(poll.getPriceExc())
                .closesAt(poll.getClosesAt() != null ? poll.getClosesAt().format(FMT) : null)
                .closed(poll.isClosed())
                .voted(pollService.hasVoted(poll, user))
                .build();
    }
}
