package ru.gamebot.platform.domain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.AlertRule;

public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {

    List<AlertRule> findAllByOrderByIdAsc();

    List<AlertRule> findAllByEnabledTrue();
}
