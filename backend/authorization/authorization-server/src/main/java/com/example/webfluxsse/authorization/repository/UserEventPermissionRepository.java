package com.example.webfluxsse.authorization.repository;

import com.example.webfluxsse.authorization.model.UserEventPermissionEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserEventPermissionRepository
    extends ReactiveCrudRepository<UserEventPermissionEntity, Long> {

  Flux<UserEventPermissionEntity> findByUserId(String userId);

  Flux<UserEventPermissionEntity> findByEventId(Long eventId);

  Mono<UserEventPermissionEntity> findByEventIdAndUserId(Long eventId, String userId);

  Mono<Void> deleteByEventIdAndUserId(Long eventId, String userId);

  @Query("SELECT event_id FROM user_event_permissions WHERE user_id = :userId")
  Flux<Long> findEventIdsByUserId(String userId);

  @Query("SELECT DISTINCT user_id FROM user_event_permissions WHERE event_id = :eventId")
  Flux<String> findUserIdsByEventId(Long eventId);

  Flux<UserEventPermissionEntity> findAllByUserIdAndEventIdIn(
      String userId, java.util.List<Long> eventIds);
}
