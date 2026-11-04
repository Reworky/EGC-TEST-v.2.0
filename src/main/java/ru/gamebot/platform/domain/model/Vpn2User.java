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

/** Пользователь отдельного бота EGC VPN (не путать с AppUser: человек мог ни разу не заходить в основной бот EGC).
 *  Хранит, кто его пригласил, награждена ли уже рефералка, и бонусные дни, ждущие выдачи (если ключа ещё нет). */
@Getter
@Setter
@Entity
@Table(name = "vpn2_users")
public class Vpn2User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long telegramId;

    private Long referredBy;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean referralRewarded = false;

    /** Бонусные дни от рефералки, которые некуда зачесть (ключа нет): добавятся к первому пробному или оплаченному сроку. */
    @Column(nullable = false, columnDefinition = "int default 0")
    private int bonusDays = 0;

    @Column(length = 128)
    private String firstName;

    @Column(length = 64)
    private String username;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
