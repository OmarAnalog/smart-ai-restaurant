package com.example.restaurantassistant.service;
import com.example.restaurantassistant.dto.Contracts.*;
import com.example.restaurantassistant.entity.CustomerOrder;
import com.example.restaurantassistant.exception.OrderConflictException;
import com.example.restaurantassistant.repository.*;
import com.example.restaurantassistant.service.menu.MenuService;
import java.math.BigDecimal;
import java.util.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@Service
public class OrderService {
  private static final Logger log = LoggerFactory.getLogger(OrderService.class);
  private final UserService users;
  private final ConversationService conversations;
  private final ConversationRepository conversationRepo;
  private final PreferenceService preferences;
  private final RecommendationSetRepository sets;
  private final MenuStockRepository stock;
  private final CustomerOrderRepository orders;
  private final MenuService menu;
  public OrderService(UserService users, ConversationService conversations, ConversationRepository conversationRepo,
      PreferenceService preferences, RecommendationSetRepository sets, MenuStockRepository stock,
      CustomerOrderRepository orders, MenuService menu) {
    this.users=users; this.conversations=conversations; this.conversationRepo=conversationRepo;
    this.preferences=preferences; this.sets=sets; this.stock=stock; this.orders=orders; this.menu=menu;
  }
  @Transactional
  public OrderView place(PlaceOrder request) {
    users.require(request.userId());
    if (request.requestId() == null || request.conversationId() == null || request.recommendationSetId() == null
        || request.items() == null || request.items().isEmpty() || request.items().size() > 3
        || request.items().stream().anyMatch(i -> i == null || i.menuItemId() == null || i.menuItemId().isBlank()
            || i.menuItemId().length() > 100 || i.quantity() < 1 || i.quantity() > 99)
        || request.items().stream().map(OrderItem::menuItemId).distinct().count() != request.items().size()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose 1–3 distinct recommended items, quantities 1–99.");
    }
    var sorted = request.items().stream().sorted(Comparator.comparing(OrderItem::menuItemId)).toList();
    String fingerprint = request.conversationId()+":"+request.recommendationSetId()+":"+
        sorted.stream().map(i -> i.menuItemId().length()+":"+i.menuItemId()+":"+i.quantity()).collect(java.util.stream.Collectors.joining(","));
    preferences.lockUser(request.userId());
    var existing = orders.findByUserIdAndRequestId(request.userId(), request.requestId());
    if (existing.isPresent()) {
      if (!existing.get().requestFingerprint.equals(fingerprint))
        throw new OrderConflictException("REQUEST_REUSED", "This request ID already belongs to a different order.");
      return view(existing.get());
    }
    conversations.owned(request.conversationId(), request.userId());
    conversationRepo.lockById(request.conversationId()).orElseThrow();
    var latest = sets.findFirstByConversationIdOrderByIdDesc(request.conversationId());
    if (latest.isEmpty() || !latest.get().id.equals(request.recommendationSetId()))
      throw new OrderConflictException("STALE_RECOMMENDATIONS", "Ask for fresh recommendations before ordering.");
    var eligible = menu.eligible(preferences.active(request.userId()));
    var order = new CustomerOrder();
    order.userId=request.userId(); order.conversationId=request.conversationId();
    order.recommendationSetId=request.recommendationSetId(); order.requestId=request.requestId();
    order.requestFingerprint=fingerprint; order.total=BigDecimal.ZERO;
    for (var item : sorted) {
      if (!latest.get().itemIds.contains(item.menuItemId()))
        throw new OrderConflictException("NOT_RECOMMENDED", "Only items in your latest recommendations can be ordered.");
      var actual = eligible.stream().filter(m -> m.id().equals(item.menuItemId())).findFirst()
          .orElseThrow(() -> new OrderConflictException("PREFERENCES_CHANGED", "This item no longer meets your saved preferences. Ask for fresh recommendations."));
      var inventory = stock.findById(item.menuItemId()).orElseThrow();
      if (inventory.remainingQuantity < item.quantity())
        throw new OrderConflictException("INSUFFICIENT_STOCK", actual.name()+" has only "+inventory.remainingQuantity+" servings left. Adjust the quantity or ask for new options.");
      inventory.remainingQuantity -= item.quantity();
      var line = new CustomerOrder.Line();
      line.menuItemId=actual.id(); line.name=actual.name(); line.unitPrice=actual.price(); line.quantity=item.quantity();
      line.lineTotal=actual.price().multiply(BigDecimal.valueOf(item.quantity()));
      order.items.add(line); order.total=order.total.add(line.lineTotal);
    }
    // Flush versioned stock first: any conflict aborts the entire transaction, including all lines.
    stock.flush();
    orders.saveAndFlush(order);
    log.info("Order staged userId={} orderId={} lines={}", order.userId, order.id, order.items.size());
    return view(order);
  }
  @Transactional(readOnly=true)
  public List<OrderView> history(Long userId) {
    users.require(userId);
    return orders.findTop20ByUserIdOrderByIdDesc(userId).stream().map(this::view).toList();
  }
  private OrderView view(CustomerOrder order) {
    return new OrderView(order.id, order.createdAt, order.items.stream().sorted(Comparator.comparing(i -> i.menuItemId))
        .map(i -> new OrderLineView(i.menuItemId,i.name,i.unitPrice,i.quantity,i.lineTotal)).toList(),order.total);
  }
}
