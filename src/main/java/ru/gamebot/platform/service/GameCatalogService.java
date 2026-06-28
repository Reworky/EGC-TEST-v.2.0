package ru.gamebot.platform.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.GameCatalog;
import ru.gamebot.platform.domain.repository.GameCatalogRepository;

@Service
@RequiredArgsConstructor
public class GameCatalogService {

    private final GameCatalogRepository gameCatalogRepository;

    public Optional<String> getPhotoFileId(String gameName) {
        return gameCatalogRepository.findByGameNameIgnoreCase(gameName)
                .map(GameCatalog::getPhotoFileId)
                .filter(s -> s != null && !s.isBlank());
    }

    @Transactional
    public void setPhoto(String gameName, String photoFileId) {
        GameCatalog entry = gameCatalogRepository.findByGameNameIgnoreCase(gameName)
                .orElseGet(() -> {
                    GameCatalog g = new GameCatalog();
                    g.setGameName(gameName);
                    return g;
                });
        entry.setPhotoFileId(photoFileId);
        gameCatalogRepository.save(entry);
    }

    @Transactional
    public void removePhoto(String gameName) {
        gameCatalogRepository.findByGameNameIgnoreCase(gameName)
                .ifPresent(g -> { g.setPhotoFileId(null); gameCatalogRepository.save(g); });
    }
}
