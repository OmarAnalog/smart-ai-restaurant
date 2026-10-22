package com.example.restaurantassistant.repository;

import com.example.restaurantassistant.entity.*;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message,Long> {
  List<Message> findByConversationIdOrderByIdDesc(Long conversationId, org.springframework.data.domain.Pageable pageable);
}
