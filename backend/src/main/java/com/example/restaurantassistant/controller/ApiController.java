package com.example.restaurantassistant.controller;

import com.example.restaurantassistant.dto.Contracts.*;
import com.example.restaurantassistant.model.MenuItem;
import com.example.restaurantassistant.service.*;
import com.example.restaurantassistant.service.menu.MenuService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final UserService users;
  private final ConversationService conversations;
  private final PreferenceService preferences;
  private final ChatService chat;
  private final MenuService menu;
  private final OnboardingService onboarding;

  public ApiController(UserService users, ConversationService conversations,
      PreferenceService preferences, ChatService chat, MenuService menu, OnboardingService onboarding) {
    this.users = users;
    this.conversations = conversations;
    this.preferences = preferences;
    this.chat = chat;
    this.menu = menu;
    this.onboarding = onboarding;
  }

  @PostMapping("/users/login")
  public Map<String, Object> login(@Valid @RequestBody Login request) {
    var user = users.login(request.username());
    return Map.of("id", user.id, "username", user.username, "onboardingCompleted", user.onboardingCompleted);
  }

  @PostMapping("/users/{id}/onboarding")
  public List<PreferenceView> completeOnboarding(
      @PathVariable Long id, @Valid @RequestBody Onboarding request) {
    return onboarding.complete(id, request.preferences());
  }

  @PostMapping("/conversations")
  public Map<String, Long> create(@Valid @RequestBody NewConversation request) {
    return Map.of("conversationId", conversations.create(request.userId()).id);
  }

  @GetMapping("/users/{id}/conversations/latest")
  public ConversationHistory latestConversation(@PathVariable Long id) {
    return conversations.latest(id);
  }

  @PostMapping("/chat")
  public ChatResponse chat(@Valid @RequestBody Chat request) {
    return chat.chat(request);
  }

  @GetMapping("/users/{id}/preferences")
  public List<PreferenceView> preferences(@PathVariable Long id) {
    users.require(id);
    return preferences.views(id);
  }

  @GetMapping("/menu")
  public List<MenuItem> menu() {
    return menu.all();
  }
}
