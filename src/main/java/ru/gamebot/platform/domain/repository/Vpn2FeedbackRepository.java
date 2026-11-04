package ru.gamebot.platform.domain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.gamebot.platform.domain.model.Vpn2Feedback;

public interface Vpn2FeedbackRepository extends JpaRepository<Vpn2Feedback, Long> {

    long countByStatus(String status);

    @Query("select avg(f.rating) from Vpn2Feedback f where f.rating is not null")
    Double averageRating();

    @Query("select f.rating, count(f) from Vpn2Feedback f where f.rating is not null group by f.rating order by f.rating desc")
    List<Object[]> ratingDistribution();

    @Query("select f.reason, count(f) from Vpn2Feedback f where f.reason is not null group by f.reason order by count(f) desc")
    List<Object[]> reasonCounts();
}
