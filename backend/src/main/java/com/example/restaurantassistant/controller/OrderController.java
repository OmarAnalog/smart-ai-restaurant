package com.example.restaurantassistant.controller;
import com.example.restaurantassistant.dto.Contracts.*;
import com.example.restaurantassistant.service.OrderService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api")
public class OrderController {
  private final OrderService orders;
  public OrderController(OrderService orders) { this.orders=orders; }
  @PostMapping("/orders") public OrderView place(@Valid @RequestBody PlaceOrder request) { return orders.place(request); }
  @GetMapping("/users/{userId}/orders") public List<OrderView> history(@PathVariable Long userId) { return orders.history(userId); }
}
