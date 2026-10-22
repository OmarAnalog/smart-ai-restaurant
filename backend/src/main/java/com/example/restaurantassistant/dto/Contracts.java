package com.example.restaurantassistant.dto;

import com.example.restaurantassistant.model.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

public final class Contracts {
  private Contracts() {}

  public record Login(
      @NotBlank @Size(max = 64) @Pattern(regexp = "[a-zA-Z0-9_.-]+") String username) {}

  public record NewConversation(@NotNull @Positive Long userId) {}

  public record Chat(
      @NotNull @Positive Long userId,
      @NotNull @Positive Long conversationId,
      @NotBlank @Size(max = 4000) String message) {}

  public record Update(Operation operation, PreferenceType type, String value, double confidence) {}

  public record Intent(List<String> foods, List<String> ingredients, List<String> excludedFoods,
      List<String> excludedIngredients, List<String> allergies, List<String> diets,
      String budgetMax, String spice) {
    public static Intent empty() { return new Intent(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null); }
  }

  public record Extraction(List<Update> updates, Intent intent) {
    public Extraction(List<Update> updates) { this(updates, Intent.empty()); }
  }

  public record Pick(String itemId, String reason) {}

  public record Ranking(String message, List<Pick> recommendations) {}

  public record Turn(String role, String content) {}

  public record HistoryMessage(Long id, Role role, String content, Instant createdAt) {}
  public record ConversationHistory(Long conversationId, List<HistoryMessage> messages) {}

  public record PreferenceView(
      @NotNull PreferenceType type, @NotBlank @Size(max = 100) String value) {}

  public record Onboarding(
      @NotNull @Size(max = 30) List<@NotNull @jakarta.validation.Valid PreferenceView> preferences) {}

  public record Recommendation(
      String id, String name, BigDecimal price, int spiceLevel,
      List<String> ingredients, String reason) {}

  public record ChatResponse(
      String message, List<PreferenceView> preferences, List<Recommendation> recommendations, Long recommendationSetId) {}

  public record OrderItem(@NotBlank @Size(max = 100) String menuItemId, @Min(1) @Max(99) int quantity) {}
  public record PlaceOrder(@NotNull @Positive Long userId, @NotNull @Positive Long conversationId,
      @NotNull @Positive Long recommendationSetId, @NotNull UUID requestId,
      @NotNull @Size(min = 1, max = 3) List<@NotNull @jakarta.validation.Valid OrderItem> items) {}
  public record OrderLineView(String menuItemId, String name, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {}
  public record OrderView(Long id, Instant createdAt, List<OrderLineView> items, BigDecimal total) {}
}
