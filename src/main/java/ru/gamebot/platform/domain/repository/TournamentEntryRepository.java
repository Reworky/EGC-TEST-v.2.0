package ru.gamebot.platform.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Tournament;
import ru.gamebot.platform.domain.model.TournamentEntry;

import java.util.List;
import java.util.Optional;

public interface TournamentEntryRepository extends JpaRepository<TournamentEntry, Long> {
    boolean existsByTournamentAndUser(Tournament tournament, AppUser user);
    Optional<TournamentEntry> findByTournamentAndUser(Tournament tournament, AppUser user);
    List<TournamentEntry> findAllByTournamentOrderByRankAsc(Tournament tournament);
    long countByTournament(Tournament tournament);

    @Query("SELECT e FROM TournamentEntry e JOIN FETCH e.user WHERE e.tournament = :t ORDER BY e.rank ASC")
    List<TournamentEntry> findAllWithUserByTournament(@Param("t") Tournament tournament);
}
