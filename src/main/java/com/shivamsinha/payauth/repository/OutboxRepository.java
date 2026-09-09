package com.shivamsinha.payauth.repository;

import com.shivamsinha.payauth.domain.OutboxEvent;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * The relay's read. Ordered by id so events for one aggregate keep their
     * causal order, and served entirely from the partial index on
     * {@code published_at IS NULL}.
     */
    List<OutboxEvent> findByPublishedAtIsNullOrderByIdAsc(Limit limit);

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.publishedAt = :publishedAt WHERE e.id IN :ids")
    int markPublished(@Param("ids") List<Long> ids, @Param("publishedAt") Instant publishedAt);

    long countByPublishedAtIsNull();
}
