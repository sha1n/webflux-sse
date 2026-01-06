package com.example.virtualthreads.search.repository.jpa;

import com.example.virtualthreads.search.model.EventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface EventRepository extends JpaRepository<EventEntity, Long> {

    @Query(value = "SELECT * FROM events ORDER BY timestamp DESC LIMIT 200", nativeQuery = true)
    List<EventEntity> findAllOrderByTimestampDesc();

    @Query(value = "SELECT * FROM events WHERE timestamp > :since ORDER BY timestamp DESC LIMIT 200", nativeQuery = true)
    List<EventEntity> findByTimestampAfterOrderByTimestampDesc(LocalDateTime since);
}
