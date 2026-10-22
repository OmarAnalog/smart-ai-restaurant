package com.example.restaurantassistant.entity;

import jakarta.persistence.*;
import java.time.Instant;
import com.example.restaurantassistant.model.*;

@Entity @Table(name="conversations") public class Conversation {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
  @Column(nullable=false,updatable=false) public Instant createdAt=Instant.now();
  @Column(nullable=false) public Long userId;
}
