package com.example.restaurantassistant.entity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
@Entity @Table(name = "customer_orders", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "request_id"}))
public class CustomerOrder {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
  @Column(nullable = false) public Long userId;
  @Column(nullable = false) public Long conversationId;
  @Column(nullable = false) public Long recommendationSetId;
  @Column(nullable = false) public UUID requestId;
  @Column(nullable = false, length = 1000) public String requestFingerprint;
  @Column(nullable = false) public Instant createdAt = Instant.now();
  @Column(nullable = false, precision = 12, scale = 2) public BigDecimal total;
  @ElementCollection @CollectionTable(name = "order_lines", joinColumns = @JoinColumn(name = "order_id"))
  public Set<Line> items = new LinkedHashSet<>();
  @Embeddable public static class Line {
    @Column(nullable = false, length = 100) public String menuItemId;
    @Column(nullable = false, length = 200) public String name;
    @Column(nullable = false, precision = 10, scale = 2) public BigDecimal unitPrice;
    @Column(nullable = false) public int quantity;
    @Column(nullable = false, precision = 12, scale = 2) public BigDecimal lineTotal;
  }
}
