package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.Vpn2CryptoInvoice;

public interface Vpn2CryptoInvoiceRepository extends JpaRepository<Vpn2CryptoInvoice, Long> {

    /** Неоплаченные счета, созданные не раньше границы: их опрашиваем раз в минуту. */
    List<Vpn2CryptoInvoice> findByStatusAndCreatedAtAfter(String status, LocalDateTime after);
}
