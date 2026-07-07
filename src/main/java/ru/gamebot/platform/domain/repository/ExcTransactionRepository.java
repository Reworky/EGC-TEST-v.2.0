package ru.gamebot.platform.domain.repository;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.ExcTransaction;

public interface ExcTransactionRepository extends JpaRepository<ExcTransaction, Long> {

    List<ExcTransaction> findByUserOrderByCreatedAtDesc(AppUser user, Pageable pageable);

    long countByUser(AppUser user);
}
