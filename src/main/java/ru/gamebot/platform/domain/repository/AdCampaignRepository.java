package ru.gamebot.platform.domain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.AdCampaign;

public interface AdCampaignRepository extends JpaRepository<AdCampaign, Long> {

    List<AdCampaign> findAllByOrderByStartDateDesc();
}
