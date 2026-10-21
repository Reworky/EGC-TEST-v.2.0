package ru.gamebot.platform.domain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.ChannelPostDraft;

public interface ChannelPostDraftRepository extends JpaRepository<ChannelPostDraft, Long> {

    List<ChannelPostDraft> findAllByType(String type);

    long countByStatus(String status);

    /** Посты из очереди плана (MANUAL_POST), карточка которых ещё не ушла админам: ждут своего часа и в «на согласовании» не считаются. */
    long countByTypeAndStatusAndCardSentAtIsNull(String type, String status);

    /** Есть ли уже черновик этого типа с таким служебным ключом в meta (любого статуса) - идемпотентность сидера очереди. */
    boolean existsByTypeAndMetaStartingWith(String type, String metaPrefix);

    List<ChannelPostDraft> findAllByStatusAndCardSentAtIsNull(String status);

    /** Последний созданный черновик этого типа (любого статуса) - для предпросмотра текста в экране управления типом. */
    java.util.Optional<ChannelPostDraft> findFirstByTypeOrderByIdDesc(String type);
}
