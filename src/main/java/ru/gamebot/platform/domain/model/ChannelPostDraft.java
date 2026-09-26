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

/** Черновик автоматического поста для канала (ChannelContentService): хранится в БД, а не в памяти бота, поэтому переживает рестарт.
 *  Публикуется только после одобрения админом (status PENDING -> PUBLISHED / REJECTED). */
@Getter
@Setter
@Entity
@Table(name = "channel_post_drafts")
public class ChannelPostDraft {

    public static final String PENDING = "PENDING";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Тип поста: NEW_QUESTS, TOP_QUESTS_WEEK (см. ChannelContentService). */
    @Column(nullable = false, length = 32)
    private String type;

    /** Текст поста в HTML-разметке Telegram. */
    @Column(nullable = false, length = 4000)
    private String postText;

    /** Картинка, которую админ добавил через «Изменить» (file_id Telegram); null - пост без картинки. */
    @Column(length = 255)
    private String photoFileId;

    @Column(nullable = false, length = 16)
    private String status = PENDING;

    /** Служебные данные: для NEW_QUESTS - id квестов из поста через запятую (чтобы не повторять их в следующем посте). */
    @Column(length = 2000)
    private String meta;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime publishedAt;

    /** Событийные посты (зал славы, итоги, лиги, топ рефереров) создаются в момент события (ночью), а карточка админу приходит в назначенное время: null - карточка ушла сразу. */
    private LocalDateTime sendAfter;

    /** Когда карточка ушла админам (только для постов с sendAfter). */
    private LocalDateTime cardSentAt;
}
