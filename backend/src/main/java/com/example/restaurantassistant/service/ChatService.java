package com.example.restaurantassistant.service;

import com.example.restaurantassistant.dto.Contracts.*;
import com.example.restaurantassistant.entity.Message;
import com.example.restaurantassistant.entity.RecommendationSet;
import com.example.restaurantassistant.repository.RecommendationSetRepository;
import com.example.restaurantassistant.service.menu.IntentFilter;
import com.example.restaurantassistant.exception.LlmException;
import com.example.restaurantassistant.exception.LlmUnavailableException;
import com.example.restaurantassistant.model.Role;
import com.example.restaurantassistant.repository.ConversationRepository;
import com.example.restaurantassistant.repository.MessageRepository;
import com.example.restaurantassistant.service.llm.LlmService;
import com.example.restaurantassistant.service.menu.MenuService;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatService {
  private static final Logger log = LoggerFactory.getLogger(ChatService.class);
  private final ConversationService conversations;
  private final ConversationRepository conversationRepository;
  private final MessageRepository messages;
  private final PreferenceService preferences;
  private final MenuService menu;
  private final LlmService llm;
  private final RecommendationService recommendations;
  private final int recentCount;
  private final IntentFilter intentFilter;
  private final StockService stock;
  private final com.example.restaurantassistant.service.memory.MemoryService memories;
  private final RecommendationSetRepository sets;

  public ChatService(
      ConversationService conversations,
      ConversationRepository conversationRepository,
      MessageRepository messages,
      PreferenceService preferences,
      MenuService menu,
      LlmService llm,
      RecommendationService recommendations,
      IntentFilter intentFilter, StockService stock, RecommendationSetRepository sets,
      com.example.restaurantassistant.service.memory.MemoryService memories,
      @Value("${app.recent-messages}") int recentCount) {
    this.conversations = conversations;
    this.conversationRepository = conversationRepository;
    this.messages = messages;
    this.preferences = preferences;
    this.menu = menu;
    this.llm = llm;
    this.recommendations = recommendations;
    this.memories=memories;
    this.intentFilter=intentFilter; this.stock=stock; this.sets=sets;
    this.recentCount = Math.clamp(recentCount, 0, 50);
  }

  @Transactional
  public ChatResponse chat(Chat request) {
    conversations.owned(request.conversationId(), request.userId());
    // Always acquire locks in this order, including across separate conversations.
    preferences.lockUser(request.userId());
    conversationRepository.lockById(request.conversationId()).orElseThrow();
    log.info("Chat userId={} conversationId={}", request.userId(), request.conversationId());

    var recentMessages = recentHistory(request.conversationId());
    var userMessage = saveMessage(request.conversationId(), Role.USER, request.message());
    messages.flush();

    var extracted = llm.extractPreferences(request.message(), preferences.views(request.userId()), recentMessages);
    try {
      if (extracted == null) {
        throw new IllegalArgumentException("Missing extraction");
      }
      IntentFilter.validate(extracted.intent());
      preferences.apply(request.userId(), userMessage.id, extracted.updates());
    } catch (IllegalArgumentException exception) {
      throw new LlmException("AI returned invalid preference changes.", exception);
    }
    log.info("Preference extraction staged userId={} updates={}", request.userId(), extracted.updates().size());

    var activePreferences = preferences.views(request.userId());
    var eligible = stock.available(intentFilter.filter(menu.eligible(preferences.active(request.userId())), extracted.intent()));
    log.info("Eligible menu candidates userId={} count={}", request.userId(), eligible.size());

    String text;
    List<Recommendation> picks;
    if (eligible.isEmpty()) {
      text = "I couldn't find an available menu item matching your current request and saved constraints. "
          + "Try a different food request or explicitly update a saved preference. I'll keep your allergies and dietary restrictions in place.";
      picks = List.of();
    } else {
      var context = new LlmService.Context(
          request.message(), activePreferences, recentMessages, eligible,
          memories.relevant(request.userId(), request.conversationId(), request.message()));
      Ranking ranking;
      try {
        ranking = llm.generateRecommendation(context);
      } catch (LlmUnavailableException exception) {
        // Extraction succeeded and constraints were applied: only this stage can safely fall back.
        log.warn("Using filtered menu fallback userId={} conversationId={} cause={}",
            request.userId(), request.conversationId(), exception.getClass().getSimpleName());
        ranking = recommendations.fallback(eligible, activePreferences);
      }
      picks = recommendations.validate(ranking, eligible);
      text = ranking.message();
    }

    // Persist the rendered menu choices too, so follow-up questions have useful context.
    String storedContent = text + picks.stream()
        .map(pick -> "\n" + pick.name() + " ($" + pick.price() + "): " + pick.reason())
        .collect(Collectors.joining());
    var assistantMessage=saveMessage(request.conversationId(), Role.ASSISTANT, storedContent);
    var set=new RecommendationSet();
    set.conversationId=request.conversationId(); set.assistantMessageId=assistantMessage.id;
    picks.forEach(pick->set.itemIds.add(pick.id()));
    sets.save(set);
    memories.stage(request.userId(), userMessage.id, request.message());
    return new ChatResponse(text, activePreferences, picks, set.id);
  }

  private List<Turn> recentHistory(Long conversationId) {
    if (recentCount == 0) {
      return List.of();
    }
    var recent = messages.findByConversationIdOrderByIdDesc(
        conversationId, PageRequest.of(0, recentCount));
    var turns = new ArrayList<Turn>();
    for (var message : recent.reversed()) {
      turns.add(new Turn(message.role.name(), message.content));
    }
    return turns;
  }

  private Message saveMessage(Long conversationId, Role role, String content) {
    var message = new Message();
    message.conversationId = conversationId;
    message.role = role;
    message.content = content;
    return messages.save(message);
  }
}
