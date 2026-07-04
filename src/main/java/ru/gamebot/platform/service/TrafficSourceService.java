package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.TrafficSource;
import ru.gamebot.platform.domain.repository.TrafficSourceRepository;

@Service
@RequiredArgsConstructor
public class TrafficSourceService {

    private final TrafficSourceRepository trafficSourceRepository;

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

    @Transactional
    public void recordClick(String code) {
        trafficSourceRepository.findByCode(code).ifPresent(ts -> {
            ts.setClicks(ts.getClicks() + 1);
            trafficSourceRepository.save(ts);
        });
    }

    @Transactional
    public void delete(Long id) {
        trafficSourceRepository.deleteById(id);
    }
}
