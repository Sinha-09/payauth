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

    /**
     * Claim a batch for this relay instance.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what lets more than one application
     * instance run the relay at the same time: each transaction takes rows nobody
     * else holds and skips the rest instead of queueing behind them. Without
     * SKIP LOCKED the relays would serialize; without FOR UPDATE they would both
     * publish the same events.
     */
    @Query(value = """
            SELECT * FROM outbox
             WHERE published_at IS NULL
             ORDER BY id
             LIMIT :batchSize
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(@Param("batchSize") int batchSize);

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.publishedAt = :publishedAt WHERE e.id IN :ids")
    int markPublished(@Param("ids") List<Long> ids, @Param("publishedAt") Instant publishedAt);

    long countByPublishedAtIsNull();
}
