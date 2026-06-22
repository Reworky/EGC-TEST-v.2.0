package ru.gamebot.platform.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.gamebot.platform.domain.model.PayoutPoolEntry;

public interface PayoutPoolEntryRepository extends JpaRepository<PayoutPoolEntry, Long> {

    @Query("SELECT COALESCE(SUM(e.amountRub), 0) FROM PayoutPoolEntry e")
    long sumAllAmounts();
}
