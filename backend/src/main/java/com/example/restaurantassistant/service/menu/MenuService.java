package com.example.restaurantassistant.service.menu;

import com.example.restaurantassistant.entity.UserPreference;
import com.example.restaurantassistant.model.MenuItem;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class MenuService {
  private final List<MenuItem> items;

  public MenuService(ObjectMapper mapper) throws IOException {
    try (var input = new ClassPathResource("menu.json").getInputStream()) {
      items = List.copyOf(mapper.readValue(input, new TypeReference<List<MenuItem>>() {}));
    }
    if (items.stream().map(MenuItem::id).distinct().count() != items.size()) {
      throw new IllegalStateException("Duplicate menu IDs");
    }
  }

  public List<MenuItem> all() {
    return items;
  }

  public static String canonical(String value) {
    String normalized = value.toLowerCase(Locale.ROOT).trim()
        .replace("mushrooms", "mushroom")
        .replace("peanuts", "peanut")
        .replace("jalapeño", "jalapeno");
    return switch (normalized) {
      case "dairy", "lactose" -> "milk";
      case "gluten" -> "wheat";
      case "eggs" -> "egg";
      case "soya" -> "soy";
      case "tree nuts", "tree nut", "nuts" -> "nut";
      case "groundnut", "groundnuts" -> "peanut";
      default -> normalized;
    };
  }

  private boolean contains(MenuItem item, String value) {
    String needle = canonical(value);
    if (needle.equals("seafood")) {
      return contains(item, "fish") || contains(item, "shellfish");
    }
    return Stream.concat(item.ingredients().stream(), item.allergens().stream())
        .map(MenuService::canonical)
        .anyMatch(ingredient -> ingredient.contains(needle));
  }

  public List<MenuItem> eligible(List<UserPreference> preferences) {
    return items.stream()
        .filter(item -> preferences.stream().allMatch(preference -> satisfies(item, preference)))
        .toList();
  }

  private boolean satisfies(MenuItem item, UserPreference preference) {
    return switch (preference.type) {
      case ALLERGY, INGREDIENT_DISLIKE -> !contains(item, preference.value);
      case FOOD_DISLIKE -> !canonical(item.name()).contains(canonical(preference.value))
          && !canonical(item.category()).contains(canonical(preference.value))
          && !contains(item, preference.value);
      case BUDGET_MAX -> item.price().compareTo(new BigDecimal(preference.value)) <= 0;
      case DIETARY_RESTRICTION -> switch (canonical(preference.value)) {
        case "vegan" -> item.vegan();
        case "vegetarian" -> item.vegetarian();
        case "gluten-free" -> !contains(item, "wheat");
        case "dairy-free" -> !contains(item, "milk");
        default -> false;
      };
      default -> true;
    };
  }
}
