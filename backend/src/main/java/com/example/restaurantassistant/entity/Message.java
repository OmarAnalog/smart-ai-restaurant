package com.example.restaurantassistant.entity;

import jakarta.persistence.*;
import java.time.Instant;
import com.example.restaurantassistant.model.*;

@Entity @Table(name="messages") public class Message {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
  @Column(nullable=false,updatable=false) public Instant createdAt=Instant.now();
  @Column(nullable=false) public Long conversationId;
  @Enumerated(EnumType.STRING) @Column(nullable=false) public Role role;
  @Column(nullable=false,columnDefinition="text") public String content;
}
