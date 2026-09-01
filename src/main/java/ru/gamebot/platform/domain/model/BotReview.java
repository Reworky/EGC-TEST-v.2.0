package ru.gamebot.platform.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import ru.gamebot.platform.domain.enums.BotReviewStatus;

/** Отзыв игрока о боте, оставленный по желанию после закрытия заявки на вывод EXC.
 * Проходит модерацию (approve/reject) перед публикацией в канал отзывов. */
@Getter
@Setter
@Entity
@Table(name = "bot_reviews")
public class BotReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    /** ID заявки на вывод (RewardRequest), по которой запрошен отзыв. */
    private Long rewardRequestId;

    private int stars;

    @Column(length = 1000)
    private String text;

    /** Telegram file_id скриншота, приложенного игроком (необязательно). */
    private String photoFileId;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(20)")
    private BotReviewStatus status;

    private LocalDateTime createdAt;
}
