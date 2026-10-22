package com.example.restaurantassistant.service;

import com.example.restaurantassistant.dto.Contracts.PreferenceView;
import com.example.restaurantassistant.dto.Contracts.Update;
import com.example.restaurantassistant.entity.UserPreference;
import com.example.restaurantassistant.model.*;
import com.example.restaurantassistant.repository.AppUserRepository;
import com.example.restaurantassistant.repository.UserPreferenceRepository;
import com.example.restaurantassistant.service.menu.MenuService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PreferenceService {
  private static final Logger log = LoggerFactory.getLogger(PreferenceService.class);
  private final UserPreferenceRepository preferences;
  private final AppUserRepository users;

  public PreferenceService(UserPreferenceRepository preferences, AppUserRepository users) {
    this.preferences = preferences;
    this.users = users;
  }

  public List<UserPreference> active(Long userId) {
    return preferences.findByUserIdAndStatusOrderByIdAsc(userId, PreferenceStatus.ACTIVE);
  }

  public List<PreferenceView> views(Long userId) {
    return active(userId).stream().map(p -> new PreferenceView(p.type, p.value)).toList();
  }

  @Transactional
  public void lockUser(Long userId) {
    users.lockById(userId).orElseThrow();
  }

  public static void validate(List<Update> updates) {
    if (updates == null || updates.size() > 30) {
      throw new IllegalArgumentException("Invalid preference updates");
    }
    for (var update : updates) {
      if (update == null || update.type() == null || update.operation() == null
          || update.value() == null || update.value().isBlank() || update.value().length() > 100
          || !Double.isFinite(update.confidence())
          || update.confidence() < 0 || update.confidence() > 1) {
        throw new IllegalArgumentException("Invalid preference update");
      }
      if (update.type() == PreferenceType.BUDGET_MAX) {
        if (!update.value().matches("[0-9]{1,6}(\\.[0-9]{1,2})?")) {
          throw new IllegalArgumentException("Budget must be a decimal amount with at most two fractional digits");
        }
        try {
          var budget = new BigDecimal(update.value());
          if (budget.signum() <= 0 || budget.compareTo(new BigDecimal("100000")) > 0) {
            throw new IllegalArgumentException("Invalid budget");
          }
        } catch (NumberFormatException exception) {
          throw new IllegalArgumentException("Invalid budget", exception);
        }
      }
    }
  }

  private boolean opposite(PreferenceType first, PreferenceType second) {
    return switch (first) {
      case INGREDIENT_LIKE -> second == PreferenceType.INGREDIENT_DISLIKE;
      case INGREDIENT_DISLIKE -> second == PreferenceType.INGREDIENT_LIKE;
      case FOOD_LIKE -> second == PreferenceType.FOOD_DISLIKE;
      case FOOD_DISLIKE -> second == PreferenceType.FOOD_LIKE;
      default -> false;
    };
  }

  @Transactional
  public void apply(Long userId, Long messageId, List<Update> updates) {
    validate(updates);
    lockUser(userId);
    for (var update : updates) {
      String value = MenuService.canonical(update.value());
      if (update.type() == PreferenceType.BUDGET_MAX) {
        value = new BigDecimal(value).stripTrailingZeros().toPlainString();
      }
      boolean upsert = update.operation() == Operation.UPSERT;
      boolean singleton = update.type() == PreferenceType.BUDGET_MAX
          || update.type() == PreferenceType.SPICE_PREFERENCE;

      for (var previous : active(userId)) {
        boolean sameValue = previous.value.equals(value);
        boolean replacesSameType = previous.type == update.type() && (sameValue || singleton && upsert);
        boolean replacesOpposite = upsert && sameValue && opposite(previous.type, update.type());
        if (replacesSameType || replacesOpposite) {
          previous.status = PreferenceStatus.INACTIVE;
          previous.updatedAt = Instant.now();
          preferences.save(previous);
        }
      }
      if (upsert) {
        var preference = new UserPreference();
        preference.userId = userId;
        preference.type = update.type();
        preference.value = value;
        preference.confidence = update.confidence();
        preference.sourceMessageId = messageId;
        preferences.save(preference);
      }
      log.info("Staged preference operation={} type={} userId={}",
          update.operation(), update.type(), userId);
    }
  }
}
