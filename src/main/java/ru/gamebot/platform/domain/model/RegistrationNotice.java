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

/** Уведомление админам о новой регистрации. Раньше каждое такое уведомление прилетало отдельным
 *  сообщением в личку, теперь копится здесь и показывается в админке на главной («🆕 Регистрации (N)»).
 *  Копия данных игрока на момент регистрации: ник/страна/интересы потом могут измениться. */
@Getter
@Setter
@Entity
@Table(name = "registration_notices")
public class RegistrationNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long telegramId;

    private String nickname;

    private String country;

    @Column(length = 500)
    private String interests;

    private LocalDateTime createdAt;

    /** Админ открыл раздел и отметил прочитанным: в счётчик на кнопке больше не входит. */
    @Column(name = "seen", columnDefinition = "boolean default false")
    private boolean seen;
}
