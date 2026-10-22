package com.example.restaurantassistant.service;

import com.example.restaurantassistant.dto.Contracts.*;
import com.example.restaurantassistant.model.Operation;
import com.example.restaurantassistant.repository.AppUserRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OnboardingService {
  private final AppUserRepository users;
  private final PreferenceService preferences;

  public OnboardingService(AppUserRepository users, PreferenceService preferences) {
    this.users = users;
    this.preferences = preferences;
  }

  @Transactional
  public List<PreferenceView> complete(Long userId, List<PreferenceView> selections) {
    var user = users.lockById(userId).orElseThrow(
        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    // A retry must not overwrite preferences subsequently corrected in chat.
    if (user.onboardingCompleted) {
      return preferences.views(userId);
    }
    var updates = selections.stream()
        .map(selection -> new Update(Operation.UPSERT, selection.type(), selection.value(), 1.0))
        .toList();
    try {
      preferences.apply(userId, null, updates);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Check your preferences. Budget must be a positive amount up to $100000 with at most two decimal places.");
    }
    user.onboardingCompleted = true;
    users.save(user);
    return preferences.views(userId);
  }
}
