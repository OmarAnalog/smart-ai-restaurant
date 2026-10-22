package com.example.restaurantassistant.service;

import com.example.restaurantassistant.entity.Conversation;
import com.example.restaurantassistant.dto.Contracts.*;
import com.example.restaurantassistant.repository.MessageRepository;
import org.springframework.data.domain.PageRequest;
import java.util.List;
import com.example.restaurantassistant.repository.ConversationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationService {
  private final ConversationRepository conversations;
  private final UserService users;
  private final MessageRepository messages;

  public ConversationService(ConversationRepository conversations, UserService users, MessageRepository messages) {
    this.conversations = conversations;
    this.users = users;
    this.messages = messages;
  }

  @Transactional
  public Conversation create(Long userId) {
    users.require(userId);
    var conversation = new Conversation();
    conversation.userId = userId;
    return conversations.save(conversation);
  }

  @Transactional(readOnly = true)
  public ConversationHistory latest(Long userId) {
    users.require(userId);
    var latest = conversations.findFirstByUserIdOrderByIdDesc(userId);
    if (latest.isEmpty()) return new ConversationHistory(null, List.of());
    var conversation = latest.get();
    var recent = messages.findByConversationIdOrderByIdDesc(conversation.id, PageRequest.of(0, 5));
    var history = recent.reversed().stream()
        .map(m -> new HistoryMessage(m.id, m.role, m.content, m.createdAt)).toList();
    return new ConversationHistory(conversation.id, history);
  }

  public Conversation owned(Long id, Long userId) {
    users.require(userId);
    var conversation = conversations.findById(id).orElseThrow(
        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
    if (!conversation.userId.equals(userId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Conversation belongs to another user");
    }
    return conversation;
  }
}
