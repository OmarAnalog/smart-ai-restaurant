package com.example.restaurantassistant.service.llm;

import com.example.restaurantassistant.dto.Contracts.*;
import com.example.restaurantassistant.model.MenuItem;
import java.util.List;

public interface LlmService {
  Extraction extractPreferences(String message,List<PreferenceView> existingPreferences);
  default Extraction extractPreferences(String message, List<PreferenceView> preferences, List<Turn> recent) {
    return extractPreferences(message, preferences);
  }
  Ranking generateRecommendation(Context context);
  record Context(String currentMessage, List<PreferenceView> preferences, List<Turn> recentMessages,
      List<MenuItem> candidates, List<String> contextualMemories) {
    public Context(String message,List<PreferenceView> preferences,List<Turn> recent,List<MenuItem> candidates) {
      this(message,preferences,recent,candidates,List.of());
    }
  }
}
