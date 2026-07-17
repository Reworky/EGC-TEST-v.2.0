package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.ExcTransfer;

public interface ExcTransferRepository extends JpaRepository<ExcTransfer, Long> {

    long countBySenderAndCreatedAtAfter(AppUser sender, LocalDateTime since);
}
