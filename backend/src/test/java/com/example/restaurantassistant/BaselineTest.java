package com.example.restaurantassistant;

import com.example.restaurantassistant.dto.Contracts.*;
import com.example.restaurantassistant.entity.*;
import com.example.restaurantassistant.model.*;
import com.example.restaurantassistant.model.Role;
import com.example.restaurantassistant.repository.*;
import com.example.restaurantassistant.service.*;
import com.example.restaurantassistant.service.llm.*;
import com.example.restaurantassistant.service.menu.*;
import com.example.restaurantassistant.exception.LlmException;
import com.example.restaurantassistant.exception.LlmUnavailableException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties= {
  "spring.datasource.url=jdbc:h2:mem:restaurant;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","app.recent-messages=2"
}
) @AutoConfigureMockMvc class BaselineTest {
  @TestConfiguration static class Config {
    @Bean @Primary ScriptedLlm scriptedLlm() {
      return new ScriptedLlm();
    }
  }
  static class ScriptedLlm implements LlmService {
    List<Update> updates=List.of();
    boolean invalid=false,fail=false,failRecommendation=false;
    boolean unavailableRecommendation=false, unavailableExtraction=false;
    Context lastContext;
    public Extraction extractPreferences(String m,List<PreferenceView> p) {
      if (unavailableExtraction) throw new LlmUnavailableException("Gemini request timed out.");
      if(fail)throw new LlmException("test failure");
      return new Extraction(updates);
    }
    public Ranking generateRecommendation(Context c) {
      lastContext=c;
      if (unavailableRecommendation) throw new LlmUnavailableException("Gemini request timed out.");
      if (failRecommendation) throw new LlmException("Gemini is still unavailable after bounded recovery.");
      return new Ranking("These options suit you.",List.of(new Pick(invalid?"invented-dish":c.candidates().getFirst().id(),"Fits your menu preferences.")));
    }
  }
  @Autowired UserService users;
  @Autowired ConversationService conversations;
  @Autowired PreferenceService preferences;
  @Autowired MenuService menu;
  @Autowired ChatService chat;
  @Autowired RecommendationService recommendations;
  @Autowired UserPreferenceRepository preferenceRepo;
  @Autowired MessageRepository messages;
  @Autowired ScriptedLlm llm;
  @Autowired MockMvc mvc;
  @BeforeEach void reset() {
    llm.updates=List.of();
    llm.invalid=false;
    llm.fail=false;
    llm.failRecommendation=false;
    llm.unavailableRecommendation=false;
    llm.unavailableExtraction=false;
    llm.lastContext=null;
  }
  AppUser user() {
    return users.login("u"+UUID.randomUUID().toString().replace("-",""));
  }
  Update update(Operation op,PreferenceType type,String value) {
    return new Update(op,type,value,.99);
  }
  UserPreference preference(PreferenceType t,String v) {
    var p=new UserPreference();
    p.type=t;
    p.value=v;
    return p;
  }
  @Test void allergySafetyAndAliases() {
    for(String allergen:List.of("peanut","peanuts","nuts")) {
      var eligible=menu.eligible(List.of(preference(PreferenceType.ALLERGY,allergen)));
      assertThat(eligible).noneMatch(i->i.id().equals("peanut-tofu-bowl"));
    }
    assertThat(menu.eligible(List.of(preference(PreferenceType.ALLERGY,"dairy")))).allMatch(i->!i.allergens().contains("milk"));
  }
  @Test void budgetBoundary() {
    assertThat(menu.eligible(List.of(preference(PreferenceType.BUDGET_MAX,"15")))).allMatch(i->i.price().doubleValue()<=15).anyMatch(i->i.price().doubleValue()==15);
  }
  @Test void dietsAndIngredientDislikes() {
    assertThat(menu.eligible(List.of(preference(PreferenceType.DIETARY_RESTRICTION,"vegan"),preference(PreferenceType.INGREDIENT_DISLIKE,"mushrooms")))).allMatch(i->i.vegan()&&!i.ingredients().contains("mushroom"));
    assertThat(menu.eligible(List.of(preference(PreferenceType.DIETARY_RESTRICTION,"unknown diet")))).isEmpty();
  }
  @Test void persistenceAcrossConversationsAndUserIsolation() {
    var a=user();
    var b=user();
    var first=conversations.create(a.id);
    llm.updates=List.of(update(Operation.UPSERT,PreferenceType.SPICE_PREFERENCE,"spicy"));
    chat.chat(new Chat(a.id,first.id,"I love spicy food"));
    var second=conversations.create(a.id);
    llm.updates=List.of();
    chat.chat(new Chat(a.id,second.id,"What do you recommend?"));
    assertThat(preferences.views(a.id)).contains(new PreferenceView(PreferenceType.SPICE_PREFERENCE,"spicy"));
    assertThat(llm.lastContext.preferences()).contains(new PreferenceView(PreferenceType.SPICE_PREFERENCE,"spicy"));
    assertThat(llm.lastContext.recentMessages()).isEmpty();
    assertThat(preferences.views(b.id)).isEmpty();
  }
  @Test void correctionsPreserveInactiveHistoryAndRemoveContradiction() {
    var u=user();
    preferences.apply(u.id,null,List.of(update(Operation.UPSERT,PreferenceType.INGREDIENT_DISLIKE,"mushrooms")));
    preferences.apply(u.id,null,List.of(update(Operation.REMOVE,PreferenceType.INGREDIENT_DISLIKE,"mushroom")));
    assertThat(preferences.active(u.id)).isEmpty();
    preferences.apply(u.id,null,List.of(update(Operation.UPSERT,PreferenceType.INGREDIENT_DISLIKE,"mushroom")));
    preferences.apply(u.id,null,List.of(update(Operation.UPSERT,PreferenceType.INGREDIENT_LIKE,"mushroom")));
    assertThat(preferences.views(u.id)).containsExactly(new PreferenceView(PreferenceType.INGREDIENT_LIKE,"mushroom"));
    assertThat(preferenceRepo.findAll().stream().filter(p->p.userId.equals(u.id)&&p.status==PreferenceStatus.INACTIVE)).hasSize(2);
  }
  @Test void budgetReplacementAndExplicitAllergyRetraction() {
    var u=user();
    preferences.apply(u.id,null,List.of(update(Operation.UPSERT,PreferenceType.BUDGET_MAX,"15"),update(Operation.UPSERT,PreferenceType.ALLERGY,"peanut")));
    preferences.apply(u.id,null,List.of(update(Operation.UPSERT,PreferenceType.BUDGET_MAX,"20"),update(Operation.UPSERT,PreferenceType.INGREDIENT_LIKE,"peanut")));
    assertThat(preferences.views(u.id)).contains(new PreferenceView(PreferenceType.ALLERGY,"peanut")).doesNotContain(new PreferenceView(PreferenceType.BUDGET_MAX,"15"));
    preferences.apply(u.id,null,List.of(update(Operation.REMOVE,PreferenceType.ALLERGY,"peanut")));
    assertThat(preferences.views(u.id)).doesNotContain(new PreferenceView(PreferenceType.ALLERGY,"peanut"));
  }
  @Test void menuGroundingRejectsInventedAndExcludedIds() {
    assertThatThrownBy(()->recommendations.validate(new Ranking("Hi",List.of(new Pick("imaginary","nice"))),menu.all())).isInstanceOf(LlmException.class);
    var safe=menu.eligible(List.of(preference(PreferenceType.ALLERGY,"peanut")));
    assertThatThrownBy(()->recommendations.validate(new Ranking("Hi",List.of(new Pick("peanut-tofu-bowl","nice"))),safe)).isInstanceOf(LlmException.class);
  }
  @Test void groundedChatPersistsBothMessagesAndBoundsContext() {
    var u=user();
    var c=conversations.create(u.id);
    for(int i=0;i<3;i++) {
      var response=chat.chat(new Chat(u.id,c.id,"Recommend something"));
      assertThat(response.recommendations()).allMatch(r->menu.all().stream().anyMatch(m->m.id().equals(r.id())&&m.name().equals(r.name())&&m.price().equals(r.price())));
    }
    assertThat(llm.lastContext.recentMessages()).hasSize(2);
    assertThat(messages.findByConversationIdOrderByIdDesc(c.id,org.springframework.data.domain.PageRequest.of(0,10))).hasSize(6);
  }
  @Test void noMatchesDoesNotCallRecommendationLlm() {
    var u=user();
    var c=conversations.create(u.id);
    llm.updates=List.of(update(Operation.UPSERT,PreferenceType.BUDGET_MAX,"1"));
    var response=chat.chat(new Chat(u.id,c.id,"Under $1"));
    assertThat(response.recommendations()).isEmpty();
    assertThat(response.message()).contains("couldn't find");
    assertThat(llm.lastContext).isNull();
    assertThat(preferences.views(u.id)).contains(new PreferenceView(PreferenceType.BUDGET_MAX,"1"));
  }
  @Test void failureRollsBackEntireTurnAndPreferences() {
    var u=user();
    var c=conversations.create(u.id);
    llm.updates=List.of(update(Operation.UPSERT,PreferenceType.ALLERGY,"peanut"));
    llm.invalid=true;
    assertThatThrownBy(()->chat.chat(new Chat(u.id,c.id,"Peanut allergy"))).isInstanceOf(LlmException.class);
    assertThat(preferences.active(u.id)).isEmpty();
    assertThat(messages.findByConversationIdOrderByIdDesc(c.id,org.springframework.data.domain.PageRequest.of(0,10))).isEmpty();
  }
  @Test void malformedExtractionFailsClosed() {
    var u=user();
    var c=conversations.create(u.id);
    llm.updates=List.of(update(Operation.UPSERT,PreferenceType.BUDGET_MAX,"banana"));
    assertThatThrownBy(()->chat.chat(new Chat(u.id,c.id,"Hello"))).isInstanceOf(LlmException.class);
    assertThat(preferences.active(u.id)).isEmpty();
  }
  @Test void ownershipAndValidationErrors() throws Exception {
    var a=user();
    var b=user();
    var c=conversations.create(a.id);
    mvc.perform(post("/api/chat").contentType("application/json").content("{\"userId\":"+b.id+",\"conversationId\":"+c.id+",\"message\":\"Hi\"}")).andExpect(status().isForbidden());
    mvc.perform(post("/api/users/login").contentType("application/json").content("{\"username\":\"  \"}")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/users/999999/preferences")).andExpect(status().isNotFound());
    mvc.perform(post("/api/chat").contentType("application/json").content("{\"userId\":"+a.id+",\"conversationId\":999999,\"message\":\"Hi\"}")).andExpect(status().isNotFound());
  }
  @Test void loginReturnsSameUser() {
    var name="login"+UUID.randomUUID().toString().replace("-","");
    assertThat(users.login(name.toUpperCase()).id).isEqualTo(users.login(name).id);
  }
  @Test
  void sameTurnConstraintsReachTheRankingCall() {
    var user = user();
    var conversation = conversations.create(user.id);
    llm.updates = List.of(
        update(Operation.UPSERT, PreferenceType.ALLERGY, "peanut"),
        update(Operation.UPSERT, PreferenceType.BUDGET_MAX, "15"));
    chat.chat(new Chat(user.id, conversation.id, "I'm allergic to peanuts. Under $15 please."));
    assertThat(llm.lastContext.candidates())
        .allMatch(item -> item.price().doubleValue() <= 15)
        .noneMatch(item -> item.allergens().contains("peanut"));
    assertThat(llm.lastContext.preferences())
        .contains(new PreferenceView(PreferenceType.ALLERGY, "peanut"));
    assertThat(preferences.active(user.id)).allMatch(preference -> preference.sourceMessageId != null);
  }

  @Test
  void firstLoginNeedsOnboardingAndSavingMakesItPersistent() throws Exception {
    var user = user();
    assertThat(users.require(user.id).onboardingCompleted).isFalse();
    mvc.perform(post("/api/users/" + user.id + "/onboarding")
        .contentType("application/json")
        .content("{\"preferences\":[{\"type\":\"ALLERGY\",\"value\":\"peanuts\"},{\"type\":\"BUDGET_MAX\",\"value\":\"15\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].value").value("peanut"));
    mvc.perform(post("/api/users/login").contentType("application/json")
        .content("{\"username\":\"" + user.username + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.onboardingCompleted").value(true));
    assertThat(preferences.views(user.id)).contains(new PreferenceView(PreferenceType.BUDGET_MAX, "15"));
    var conversation = conversations.create(user.id);
    chat.chat(new Chat(user.id, conversation.id, "What do you recommend?"));
    assertThat(llm.lastContext.candidates()).allMatch(item -> item.price().doubleValue() <= 15)
        .noneMatch(item -> item.allergens().contains("peanut"));
  }

  @Test
  void skippingOnboardingIsRememberedWithoutCreatingPreferences() throws Exception {
    var user = user();
    mvc.perform(post("/api/users/" + user.id + "/onboarding")
        .contentType("application/json").content("{\"preferences\":[]}"))
        .andExpect(status().isOk());
    assertThat(users.login(user.username).onboardingCompleted).isTrue();
    assertThat(preferences.active(user.id)).isEmpty();
  }

  @Test
  void invalidOnboardingDoesNotCompleteOrPartiallySave() throws Exception {
    var user = user();
    for (var body : List.of(
        "{\"preferences\":[{\"type\":\"ALLERGY\",\"value\":\"peanut\"},{\"type\":\"BUDGET_MAX\",\"value\":\"bad\"}]}",
        "{\"preferences\":[{\"type\":\"ALLERGY\",\"value\":\"   \"}]}",
        "{\"preferences\":[{\"type\":null,\"value\":\"peanut\"}]}",
        "{\"preferences\":[null]}",
        "{}")) {
      mvc.perform(post("/api/users/" + user.id + "/onboarding")
          .contentType("application/json").content(body)).andExpect(status().isBadRequest());
    }
    assertThat(users.require(user.id).onboardingCompleted).isFalse();
    assertThat(preferences.active(user.id)).isEmpty();
    mvc.perform(post("/api/users/999999/onboarding")
        .contentType("application/json").content("{\"preferences\":[]}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void repeatedOnboardingCannotOverwriteChatCorrectionsOrAnotherUser() throws Exception {
    var first = user();
    var second = user();
    String body = "{\"preferences\":[{\"type\":\"INGREDIENT_DISLIKE\",\"value\":\"mushroom\"}]}";
    mvc.perform(post("/api/users/" + first.id + "/onboarding")
        .contentType("application/json").content(body)).andExpect(status().isOk());
    preferences.apply(first.id, null, List.of(update(Operation.REMOVE, PreferenceType.INGREDIENT_DISLIKE, "mushroom")));
    mvc.perform(post("/api/users/" + first.id + "/onboarding")
        .contentType("application/json").content(body)).andExpect(status().isOk());
    assertThat(preferences.active(first.id)).isEmpty();
    assertThat(preferences.active(second.id)).isEmpty();
    assertThat(users.require(second.id).onboardingCompleted).isFalse();
  }

  @Test
  void recommendationFailureRollsBackNewAndReplacedPreferences() {
    var user = user();
    var conversation = conversations.create(user.id);
    preferences.apply(user.id, null, List.of(update(Operation.UPSERT, PreferenceType.BUDGET_MAX, "15")));
    llm.updates = List.of(update(Operation.UPSERT, PreferenceType.BUDGET_MAX, "20"),
        update(Operation.UPSERT, PreferenceType.SPICE_PREFERENCE, "spicy"));
    llm.failRecommendation = true;
    assertThatThrownBy(() -> chat.chat(new Chat(user.id, conversation.id, "Spicy and under $20")))
        .isInstanceOf(LlmException.class).hasMessageContaining("unavailable");
    assertThat(preferences.views(user.id)).containsExactly(new PreferenceView(PreferenceType.BUDGET_MAX, "15"));
    assertThat(preferenceRepo.findAll().stream().filter(preference -> preference.userId.equals(user.id))).hasSize(1);
    assertThat(messages.findByConversationIdOrderByIdDesc(conversation.id,
        org.springframework.data.domain.PageRequest.of(0, 10))).isEmpty();
  }

  @Test
  void recommendationOutageUsesFilteredFallbackAndCommitsExtractedMemory() {
    var user = user();
    var anotherUser = user();
    var conversation = conversations.create(user.id);
    llm.updates = List.of(
        update(Operation.UPSERT, PreferenceType.ALLERGY, "peanut"),
        update(Operation.UPSERT, PreferenceType.DIETARY_RESTRICTION, "vegan"),
        update(Operation.UPSERT, PreferenceType.INGREDIENT_DISLIKE, "mushroom"),
        update(Operation.UPSERT, PreferenceType.BUDGET_MAX, "15"),
        update(Operation.UPSERT, PreferenceType.SPICE_PREFERENCE, "spicy"));
    llm.unavailableRecommendation = true;
    var response = chat.chat(new Chat(user.id, conversation.id, "Something spicy and vegan, under $15. Peanut allergy."));
    assertThat(response.message()).contains("couldn't generate a personalized reply");
    assertThat(response.recommendations()).hasSize(3);
    assertThat(response.recommendations().getFirst().id()).isEqualTo("fire-veggie-pizza");
    var eligible = menu.eligible(preferences.active(user.id));
    assertThat(response.recommendations()).allSatisfy(recommendation -> {
      var actual = eligible.stream().filter(item -> item.id().equals(recommendation.id())).findFirst().orElseThrow();
      assertThat(actual.vegan()).isTrue();
      assertThat(actual.allergens()).doesNotContain("peanut");
      assertThat(actual.ingredients()).doesNotContain("mushroom");
      assertThat(actual.price()).isLessThanOrEqualTo(new java.math.BigDecimal("15"));
      assertThat(recommendation.name()).isEqualTo(actual.name());
      assertThat(recommendation.price()).isEqualTo(actual.price());
    });
    assertThat(preferences.views(user.id)).contains(new PreferenceView(PreferenceType.ALLERGY, "peanut"));
    assertThat(preferences.views(anotherUser.id)).isEmpty();
    assertThat(messages.findByConversationIdOrderByIdDesc(conversation.id,
        org.springframework.data.domain.PageRequest.of(0, 10))).hasSize(2);
    conversations.create(user.id);
    assertThat(preferences.views(user.id)).contains(new PreferenceView(PreferenceType.SPICE_PREFERENCE, "spicy"));
  }

  @Test
  void extractionOutageNeverFallsBackWithPotentiallyStaleConstraints() {
    var user = user();
    var conversation = conversations.create(user.id);
    llm.unavailableExtraction = true;
    assertThatThrownBy(() -> chat.chat(new Chat(user.id, conversation.id, "I am allergic to peanuts")))
        .isInstanceOf(LlmUnavailableException.class);
    assertThat(llm.lastContext).isNull();
    assertThat(preferences.active(user.id)).isEmpty();
    assertThat(messages.findByConversationIdOrderByIdDesc(conversation.id,
        org.springframework.data.domain.PageRequest.of(0, 10))).isEmpty();
  }
  @Test void latestConversationReturnsOnlyLastFiveMessagesChronologically() throws Exception {
    var owner = user();
    var older = conversations.create(owner.id);
    var latest = conversations.create(owner.id);
    var otherUser = user();
    var other = conversations.create(otherUser.id);
    for (var conversation : List.of(older, latest, other)) {
      for (int n = 1; n <= 8; n++) {
        var message = new Message();
        message.conversationId = conversation.id;
        message.role = n % 2 == 0 ? Role.ASSISTANT : Role.USER;
        message.content = "conversation-" + conversation.id + "-message-" + n;
        messages.save(message);
      }
    }
    mvc.perform(get("/api/users/" + owner.id + "/conversations/latest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conversationId").value(latest.id))
        .andExpect(jsonPath("$.messages.length()").value(5))
        .andExpect(jsonPath("$.messages[0].content").value("conversation-" + latest.id + "-message-4"))
        .andExpect(jsonPath("$.messages[4].content").value("conversation-" + latest.id + "-message-8"))
        .andExpect(jsonPath("$.messages[0].role").value("ASSISTANT"));
  }

  @Test void latestConversationHandlesNewAndUnknownUsers() throws Exception {
    var owner = user();
    mvc.perform(get("/api/users/" + owner.id + "/conversations/latest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conversationId").isEmpty())
        .andExpect(jsonPath("$.messages.length()").value(0));
    mvc.perform(get("/api/users/999999/conversations/latest")).andExpect(status().isNotFound());
  }

  @Test void newConversationStaysEmptyAndKeepsPreferences() {
    var owner = user();
    var old = conversations.create(owner.id);
    var message = new Message(); message.conversationId = old.id; message.role = Role.USER; message.content = "Old chat";
    messages.save(message);
    preferences.apply(owner.id, null, List.of(update(Operation.UPSERT, PreferenceType.ALLERGY, "peanut")));
    var fresh = conversations.create(owner.id);
    var history = conversations.latest(owner.id);
    assertThat(history.conversationId()).isEqualTo(fresh.id);
    assertThat(history.messages()).isEmpty();
    assertThat(preferences.views(owner.id)).contains(new PreferenceView(PreferenceType.ALLERGY, "peanut"));
  }

}
