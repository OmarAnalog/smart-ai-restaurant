package com.example.restaurantassistant.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
@Entity @Table(name = "recommendation_sets")
public class RecommendationSet {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
  @Column(nullable = false) public Long conversationId;
  @Column(nullable = false, unique = true) public Long assistantMessageId;
  @Column(nullable = false) public Instant createdAt = Instant.now();
  @ElementCollection @CollectionTable(name = "recommendation_set_items", joinColumns = @JoinColumn(name = "recommendation_set_id"))
  @Column(name = "menu_item_id", nullable = false, length = 100) public Set<String> itemIds = new HashSet<>();
}
