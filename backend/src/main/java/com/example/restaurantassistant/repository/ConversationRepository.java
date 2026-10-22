package com.example.restaurantassistant.repository;

import com.example.restaurantassistant.entity.*;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation,Long> {
  List<Conversation> findByUserIdOrderByCreatedAtDesc(Long userId);
  Optional<Conversation> findFirstByUserIdOrderByIdDesc(Long userId);
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE) @org.springframework.data.jpa.repository.Query("select c from Conversation c where c.id = :id") Optional<Conversation> lockById(Long id);
}
