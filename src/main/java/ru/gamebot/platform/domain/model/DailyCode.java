package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** «Код дня» (2026-10-17): спрятан в посте канала, игрок вводит его в боте за награду. Строка создаётся в момент публикации поста
 *  (после одобрения админом), код действует 24 часа. */
@Getter
@Setter
@Entity
@Table(name = "daily_codes")
public class DailyCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String code;

    /** id поста в канале (для кнопки «Открыть пост»); null - не удалось определить. */
    private Integer channelMessageId;

    @Column(nullable = false)
    private LocalDateTime publishedAt;

    @Column(nullable = false)
    private LocalDateTime validUntil;
}
