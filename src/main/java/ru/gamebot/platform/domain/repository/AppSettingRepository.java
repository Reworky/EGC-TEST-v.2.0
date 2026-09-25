package ru.gamebot.platform.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.AppSetting;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
