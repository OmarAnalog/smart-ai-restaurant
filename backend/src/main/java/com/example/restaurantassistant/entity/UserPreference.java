package com.example.restaurantassistant.entity;

import jakarta.persistence.*;
import java.time.Instant;
import com.example.restaurantassistant.model.*;

@Entity @Table(name="preferences") public class UserPreference {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
  @Column(nullable=false,updatable=false) public Instant createdAt=Instant.now();
  @Column(nullable=false) public Long userId;
  @Enumerated(EnumType.STRING) @Column(nullable=false) public PreferenceType type;
  @Column(name="preference_value",nullable=false) public String value;
  @Enumerated(EnumType.STRING) @Column(nullable=false) public PreferenceStatus status=PreferenceStatus.ACTIVE;
  @Column(nullable=false) public double confidence;
  public Long sourceMessageId;
  @Column(nullable=false) public Instant updatedAt=Instant.now();
}
