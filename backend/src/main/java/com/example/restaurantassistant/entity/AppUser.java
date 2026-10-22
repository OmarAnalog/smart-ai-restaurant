package com.example.restaurantassistant.entity;

import jakarta.persistence.*;
import java.time.Instant;
import com.example.restaurantassistant.model.*;

@Entity @Table(name="app_users") public class AppUser {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
  @Column(nullable=false,updatable=false) public Instant createdAt=Instant.now();
  @Column(nullable=false,unique=true,length=64) public String username;
  @Column(nullable=false) public boolean onboardingCompleted;
}
