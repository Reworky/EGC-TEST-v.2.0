package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.TrafficSource;
import ru.gamebot.platform.domain.model.TrafficSpendEntry;
import ru.gamebot.platform.domain.repository.TrafficSourceRepository;
import ru.gamebot.platform.domain.repository.TrafficSpendEntryRepository;

@Service
@RequiredArgsConstructor
public class TrafficSourceService {

    private final TrafficSourceRepository trafficSourceRepository;
    private final TrafficSpendEntryRepository spendEntryRepository;

    public List<TrafficSource> findAll() {
        return trafficSourceRepository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<TrafficSource> findById(Long id) {
        return trafficSourceRepository.findById(id);
    }

    public Optional<TrafficSource> findByCode(String code) {
        return trafficSourceRepository.findByCode(code);
    }

    @Transactional
    public TrafficSource create(String name, String code) {
        if (trafficSourceRepository.findByCode(code).isPresent()) {
            throw new IllegalArgumentException("Источник с кодом «" + code + "» уже существует.");
        }
        TrafficSource ts = new TrafficSource();
        ts.setName(name);
        ts.setCode(code);
        ts.setClicks(0);
        ts.setCreatedAt(LocalDateTime.now());
        return trafficSourceRepository.save(ts);
    }

    /** Создаёт сразу N источников одной пачкой — для рассылки блогерам/площадкам без ручного
     * ввода названия и кода на каждую ссылку по отдельности. */
    @Transactional
    public List<TrafficSource> createBatch(int count) {
        LocalDate today = LocalDate.now();
        String dateLabel = today.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        List<TrafficSource> created = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            TrafficSource ts = new TrafficSource();
            ts.setName("Пачка " + dateLabel + " #" + i);
            ts.setCode(generateUniqueCode());
            ts.setClicks(0);
            ts.setCreatedAt(LocalDateTime.now());
            created.add(trafficSourceRepository.save(ts));
        }
        return created;
    }

    private String generateUniqueCode() {
        String code;
        do {
            code = "b" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        } while (trafficSourceRepository.findByCode(code).isPresent());
        return code;
    }

    @Transactional
    public void recordClick(String code) {
        trafficSourceRepository.findByCode(code).ifPresent(ts -> {
            ts.setClicks(ts.getClicks() + 1);
            trafficSourceRepository.save(ts);
        });
    }

    /** Расход за период (для вкладки «Удержание»): сохраняет запись и увеличивает общую сумму источника на ту же величину. false - источник не найден. */
    @Transactional
    public boolean addSpendEntry(Long id, long amountRub, LocalDate from, LocalDate to) {
        TrafficSource ts = trafficSourceRepository.findById(id).orElse(null);
        if (ts == null || amountRub <= 0 || from == null || to == null || to.isBefore(from)) return false;
        TrafficSpendEntry e = new TrafficSpendEntry();
        e.setSourceCode(ts.getCode());
        e.setAmountRub(amountRub);
        e.setFromDate(from);
        e.setToDate(to);
        e.setCreatedAt(LocalDateTime.now());
        spendEntryRepository.save(e);
        ts.setSpendRub(ts.getSpendRub() + amountRub);
        trafficSourceRepository.save(ts);
        return true;
    }

    @Transactional
    public void setSpend(Long id, long spendRub) {
        trafficSourceRepository.findById(id).ifPresent(ts -> {
            ts.setSpendRub(Math.max(0, spendRub));
            trafficSourceRepository.save(ts);
        });
    }

    @Transactional
    public void delete(Long id) {
        trafficSourceRepository.deleteById(id);
    }
}
