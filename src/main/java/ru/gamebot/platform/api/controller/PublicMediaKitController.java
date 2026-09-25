package ru.gamebot.platform.api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.service.AdvertiserService;

/** Публичная облегчённая версия медиа-кита по ссылке с токеном (без входа в админку): можно просто скинуть ссылку рекламодателю.
 *  Токен создаётся и перевыпускается в админке («📣 Рекламодателям → 🔗 Публичная ссылка»); бюджеты кампаний в публичную версию не входят. */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicMediaKitController {

    private final AdvertiserService advertiserService;

    @GetMapping(value = "/mediakit/{token}", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> mediaKit(@PathVariable String token) {
        if (!advertiserService.tokenMatches(token)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(advertiserService.mediaKitHtml(advertiserService.cachedSubscribers(), true));
    }
}
