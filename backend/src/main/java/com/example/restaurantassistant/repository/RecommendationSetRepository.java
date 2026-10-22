package com.example.restaurantassistant.repository;
import com.example.restaurantassistant.entity.*;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RecommendationSetRepository extends JpaRepository<RecommendationSet, Long> {
  Optional<RecommendationSet> findFirstByConversationIdOrderByIdDesc(Long conversationId);
}
