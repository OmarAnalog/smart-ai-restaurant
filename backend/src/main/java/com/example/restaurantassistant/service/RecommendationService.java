package com.example.restaurantassistant.service;

import com.example.restaurantassistant.dto.Contracts.*;
import com.example.restaurantassistant.exception.LlmException;
import com.example.restaurantassistant.model.MenuItem;
import com.example.restaurantassistant.model.PreferenceType;
import com.example.restaurantassistant.service.menu.MenuService;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class RecommendationService {
  public Ranking fallback(List<MenuItem> eligible, List<PreferenceView> preferences) {
    var picks = eligible.stream()
        .sorted(Comparator.<MenuItem>comparingInt(item -> fallbackScore(item, preferences)).reversed()
            .thenComparing(MenuItem::price).thenComparing(MenuItem::id))
        .limit(3)
        .map(item -> new Pick(item.id(), "Meets your saved menu filters. Spice: " + item.spiceLevel() + "/5."))
        .toList();
    return new Ranking("I couldn't generate a personalized reply just now. "
        + "Here are some menu options that meet your saved constraints.", picks);
  }

  private int fallbackScore(MenuItem item, List<PreferenceView> preferences) {
    int score = 0;
    for (var preference : preferences) {
      String value = MenuService.canonical(preference.value());
      if (preference.type() == PreferenceType.SPICE_PREFERENCE) {
        int target = switch (value) {
          case "mild" -> 0;
          case "medium" -> 2;
          case "spicy" -> 4;
          default -> -1;
        };
        if (target >= 0) score += 5 - Math.abs(item.spiceLevel() - target);
      } else if (preference.type() == PreferenceType.FOOD_LIKE
          || preference.type() == PreferenceType.INGREDIENT_LIKE) {
        String facts = MenuService.canonical(
            item.name() + " " + item.category() + " " + String.join(" ", item.ingredients()));
        if (facts.contains(value)) score += 5;
      }
    }
    return score;
  }

  public List<Recommendation> validate(Ranking ranking, List<MenuItem> candidates) {
    if (ranking == null || ranking.message() == null || ranking.message().isBlank()
        || ranking.message().length() > 6000 || ranking.recommendations() == null
        || ranking.recommendations().size() > 3) {
      throw new LlmException("AI returned an invalid recommendation.");
    }
    var byId = new HashMap<String, MenuItem>();
    candidates.forEach(item -> byId.put(item.id(), item));
    var seen = new HashSet<String>();
    var results = new ArrayList<Recommendation>();
    for (var pick : ranking.recommendations()) {
      if (pick == null || !byId.containsKey(pick.itemId()) || !seen.add(pick.itemId())
          || pick.reason() == null || pick.reason().isBlank() || pick.reason().length() > 1500) {
        throw new LlmException("AI returned an invalid menu selection.");
      }
      var item = byId.get(pick.itemId());
      results.add(new Recommendation(item.id(), item.name(), item.price(), item.spiceLevel(),
          item.ingredients(), pick.reason()));
    }
    return List.copyOf(results);
  }
}
