package ru.gamebot.platform.service;

import java.util.Optional;
import ru.gamebot.platform.domain.model.Tournament;

/** Игра, по трофеям которой проводится турнир-марафон: единственное, что отличает Brawl Stars от Clash Royale в турнирной
 *  автоматике (регистрация по тегу, стартовый/финальный снимки, прирост, призы, отмена при недоборе) - откуда брать трофеи.
 *  Остальная логика общая (TrophyTournamentService), новая игра = ещё одна реализация этого интерфейса. */
public interface TrophyGameProvider {

    /** Тип подсчёта турнира, который обслуживает этот провайдер. */
    Tournament.ScoringType scoringType();

    /** Название игры как в Tournament.gameName / для текстов игроку. */
    String gameName();

    /** false, если токен официального API не настроен - турнир деградирует без падения (снимки помечаются FAILED). */
    boolean isEnabled();

    /** Пусто = тег не найден/некорректен (404/400), повторять не нужно; временная ошибка после ретраев = исключение. */
    Optional<TrophyPlayer> fetchPlayer(String rawTag) throws TrophyApiTransientException;

    /** Игрок и его трофеи из официального API; tag в формате «#ABC123». */
    record TrophyPlayer(String tag, String name, int trophies) {}

    /** Временный сбой API игры (таймаут/429/5xx после ретраев, либо 403 - токен/IP не в белом списке). */
    class TrophyApiTransientException extends Exception {
        public TrophyApiTransientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
