package com.example.restaurantassistant.controller;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
  private final JdbcTemplate database;

  public HealthController(JdbcTemplate database) {
    this.database = database;
  }

  @GetMapping("/api/health")
  public Map<String, String> health() {
    database.queryForObject("SELECT 1", Integer.class);
    return Map.of("status", "UP");
  }
}
