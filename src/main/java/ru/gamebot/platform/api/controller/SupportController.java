package ru.gamebot.platform.api.controller;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.gamebot.platform.api.dto.ShopActionResponseDto;
import ru.gamebot.platform.api.dto.SupportTicketDto;
import ru.gamebot.platform.bot.GamePlatformBot;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.SupportTicket;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.SupportService;
import ru.gamebot.platform.service.TelegramFileService;

@Slf4j
@RestController
@RequestMapping("/api/support")
@RequiredArgsConstructor
public class SupportController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final SupportService supportService;
    private final AppUserRepository appUserRepository;
    private final TelegramFileService telegramFileService;
    private final GamePlatformBot gamePlatformBot;

    @GetMapping("/tickets")
    public ResponseEntity<List<SupportTicketDto>> tickets(@AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        List<SupportTicketDto> result = supportService.getUserTickets(user).stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/tickets")
    public ResponseEntity<ShopActionResponseDto> createTicket(
            @AuthenticationPrincipal Long telegramId,
            @RequestParam(required = false) String text,
            @RequestParam(required = false) MultipartFile photo) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        boolean hasText = text != null && !text.isBlank();
        boolean hasPhoto = photo != null && !photo.isEmpty();
        if (!hasText && !hasPhoto) {
            return ResponseEntity.ok(ShopActionResponseDto.builder()
                    .success(false)
                    .message("Опишите проблему текстом или приложите скриншот.")
                    .build());
        }

        String photoFileId = null;
        if (hasPhoto) {
            try {
                photoFileId = telegramFileService.uploadPhoto(photo.getBytes(),
                        photo.getOriginalFilename() != null ? photo.getOriginalFilename() : "support.jpg", telegramId);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("Failed to upload support photo for user {}", telegramId, e);
                return ResponseEntity.ok(ShopActionResponseDto.builder()
                        .success(false)
                        .message("Не удалось загрузить фото. Попробуйте ещё раз.")
                        .build());
            }
        }

        SupportTicket ticket = supportService.createTicket(user, text, null);
        if (hasPhoto || hasText) {
            supportService.addAttachment(ticket, false, hasPhoto ? "photo" : "text", photoFileId, text);
        }
        gamePlatformBot.notifyModeratorsAboutSupportTicket(ticket, text, photoFileId);

        return ResponseEntity.ok(ShopActionResponseDto.builder()
                .success(true)
                .message("Заявка отправлена. Ответ придёт прямо в бот.")
                .build());
    }

    private SupportTicketDto toDto(SupportTicket t) {
        return SupportTicketDto.builder()
                .id(t.getId())
                .status(t.getStatus().name())
                .initialMessage(t.getInitialMessage())
                .lastModeratorReply(t.getLastModeratorReply())
                .createdAt(t.getCreatedAt() != null ? t.getCreatedAt().format(FMT) : null)
                .updatedAt(t.getUpdatedAt() != null ? t.getUpdatedAt().format(FMT) : null)
                .build();
    }
}
