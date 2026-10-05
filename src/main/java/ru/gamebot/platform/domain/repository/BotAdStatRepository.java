package ru.gamebot.platform.domain.repository;

import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.BotAdStat;

public interface BotAdStatRepository extends JpaRepository<BotAdStat, LocalDate> {
}
