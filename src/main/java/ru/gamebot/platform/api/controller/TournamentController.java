package ru.gamebot.platform.api.controller;

import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.ShopActionResponseDto;
import ru.gamebot.platform.api.dto.TournamentDto;
import ru.gamebot.platform.api.dto.TournamentEntryDto;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Tournament;
import ru.gamebot.platform.domain.model.TournamentEntry;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.TournamentService;

@RestController
@RequestMapping("/api/tournament")
@RequiredArgsConstructor
public class TournamentController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final TournamentService tournamentService;
    private final AppUserRepository appUserRepository;

    @GetMapping
    public ResponseEntity<TournamentDto> current(@AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return tournamentService.findCurrentForUser()
                .map(t -> ResponseEntity.ok(toDto(t, user)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/join")
    public ResponseEntity<ShopActionResponseDto> join(@PathVariable Long id, @AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Tournament tournament = tournamentService.findById(id).orElse(null);
        if (tournament == null) {
            return ResponseEntity.notFound().build();
        }
        TournamentService.JoinResult res = tournamentService.join(user, tournament);
        return ResponseEntity.ok(ShopActionResponseDto.builder()
                .success(res.success())
                .message(res.success() ? "Вы зарегистрированы! Взнос списан." : res.error())
                .build());
    }

    @GetMapping("/{id}/leaderboard")
    public ResponseEntity<List<TournamentEntryDto>> leaderboard(@PathVariable Long id) {
        Tournament tournament = tournamentService.findById(id).orElse(null);
        if (tournament == null) {
            return ResponseEntity.notFound().build();
        }
        List<TournamentEntry> entries = tournamentService.getLeaderboard(tournament);
        List<TournamentEntryDto> result = new java.util.ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            TournamentEntry e = entries.get(i);
            result.add(TournamentEntryDto.builder()
                    .rank(e.getRank() > 0 ? e.getRank() : i + 1)
                    .nickname(e.getUser().getNickname() != null ? e.getUser().getNickname() : "—")
                    .prizeExc(e.getPrizeExc())
                    .build());
        }
        return ResponseEntity.ok(result);
    }

    private TournamentDto toDto(Tournament t, AppUser user) {
        return TournamentDto.builder()
                .id(t.getId())
                .name(t.getName())
                .gameName(t.getGameName())
                .entryFeeExc(t.getEntryFeeExc())
                .prizePoolExc(t.getPrizePoolExc())
                .entryCount(tournamentService.entryCount(t))
                .startDate(t.getStartDate() != null ? t.getStartDate().format(FMT) : null)
                .endDate(t.getEndDate() != null ? t.getEndDate().format(FMT) : null)
                .status(t.getStatus().name())
                .entered(tournamentService.hasEntered(t, user))
                .build();
    }
}
