package ru.gamebot.platform.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.LeadgidConversion;

public interface LeadgidConversionRepository extends JpaRepository<LeadgidConversion, Long> {

    /** Тот же статус той же конверсии уже приходил (LeadGid может повторять постбек). */
    boolean existsByExternalIdAndStatusNorm(String externalId, String statusNorm);
}
