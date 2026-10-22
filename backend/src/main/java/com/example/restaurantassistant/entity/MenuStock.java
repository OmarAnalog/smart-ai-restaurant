package com.example.restaurantassistant.entity;
import jakarta.persistence.*;
@Entity @Table(name = "menu_stock")
public class MenuStock {
  @Id @Column(length = 100) public String menuItemId;
  @Column(nullable = false) public int remainingQuantity;
  @Version @Column(nullable = false) public long version;
}
