package com.example.restaurantassistant.service;
import com.example.restaurantassistant.model.MenuItem;
import com.example.restaurantassistant.repository.MenuStockRepository;
import java.util.*;
import org.springframework.stereotype.Service;
@Service
public class StockService {
  private final MenuStockRepository stock;
  public StockService(MenuStockRepository stock) { this.stock = stock; }
  public List<MenuItem> available(List<MenuItem> candidates) {
    var available = new HashSet<String>();
    stock.findAll().stream().filter(s -> s.remainingQuantity > 0).forEach(s -> available.add(s.menuItemId));
    return candidates.stream().filter(m -> available.contains(m.id())).toList();
  }
}
