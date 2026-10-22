package com.example.restaurantassistant;

import com.example.restaurantassistant.dto.Contracts.*;
import com.example.restaurantassistant.entity.*;
import com.example.restaurantassistant.exception.*;
import com.example.restaurantassistant.model.*;
import com.example.restaurantassistant.repository.*;
import com.example.restaurantassistant.service.*;
import com.example.restaurantassistant.service.llm.LlmService;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"gemini.api-key=test-unused"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="DATABASE_INTEGRATION",matches="true")
class OrderIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",()->System.getenv("TEST_DATABASE_URL"));
    r.add("spring.datasource.username",()->"restaurant");
    r.add("spring.datasource.password",()->"test-only");
  }
  @TestConfiguration static class Config { @Bean @Primary StubLlm stub() { return new StubLlm(); } }
  static class StubLlm implements LlmService {
    List<Update> updates=List.of(); Intent intent=Intent.empty(); boolean invalid,unavailable;
    List<Turn> recent; Context context;
    public Extraction extractPreferences(String m,List<PreferenceView> p) { return new Extraction(updates,intent); }
    public Extraction extractPreferences(String m,List<PreferenceView> p,List<Turn> history) { recent=history;return extractPreferences(m,p); }
    public Ranking generateRecommendation(Context c) {
      context=c;
      if(unavailable) throw new LlmUnavailableException("test timeout");
      return new Ranking("These options fit.",c.candidates().stream().limit(3)
          .map(i->new Pick(invalid?"invented":i.id(),"Matches your request")).toList());
    }
  }
  @Autowired UserService users;
  @Autowired ConversationService conversations;
  @Autowired ChatService chat;
  @Autowired OrderService orders;
  @Autowired PreferenceService preferences;
  @Autowired RecommendationSetRepository sets;
  @Autowired CustomerOrderRepository orderRepo;
  @Autowired JdbcTemplate jdbc;
  @Autowired StubLlm llm;
  @Autowired MockMvc mvc;
  @MockitoSpyBean MenuStockRepository stock;
  @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;
  @BeforeEach void reset() {
    org.mockito.Mockito.reset(stock);
    jdbc.execute("TRUNCATE TABLE app_users RESTART IDENTITY CASCADE");
    jdbc.update("UPDATE menu_stock SET remaining_quantity=10, version=version+1");
    llm.updates=List.of();llm.intent=Intent.empty();llm.invalid=false;llm.unavailable=false;llm.context=null;llm.recent=null;
  }
  record Session(AppUser user,Conversation conversation,ChatResponse response) {}
  Session session() {
    var u=users.login("u"+UUID.randomUUID().toString().replace("-",""));var c=conversations.create(u.id);
    return new Session(u,c,chat.chat(new Chat(u.id,c.id,"What do you recommend?")));
  }
  PlaceOrder request(Session s,String id,int quantity) {
    return new PlaceOrder(s.user.id,s.conversation.id,s.response.recommendationSetId(),UUID.randomUUID(),List.of(new OrderItem(id,quantity)));
  }
  int remaining(String id) { return jdbc.queryForObject("SELECT remaining_quantity FROM menu_stock WHERE menu_item_id=?",Integer.class,id); }
  @Test void temporaryIntentIsFilteredButNotSavedAndHistoryReachesExtraction() {
    llm.intent=new Intent(List.of("bowl"),List.of("chicken"),List.of(),List.of(),List.of(),List.of(),"15","spicy");
    var s=session();
    assertThat(s.response.recommendations()).extracting(Recommendation::id).containsExactly("spicy-chicken-bowl");
    assertThat(preferences.active(s.user.id)).isEmpty();
    chat.chat(new Chat(s.user.id,s.conversation.id,"Make it a bowl again"));
    assertThat(llm.recent).hasSize(2);
  }
  @Test void enduringFactsAreSavedAcrossConversationsAndRestrictCurrentIntent() {
    llm.updates=List.of(new Update(Operation.UPSERT,PreferenceType.BUDGET_MAX,"12",.99));
    llm.intent=new Intent(List.of(),List.of("chicken"),List.of(),List.of(),List.of(),List.of(),"20",null);
    var s=session();
    assertThat(s.response.recommendations()).isEmpty();
    llm.updates=List.of();llm.intent=Intent.empty();
    var c=conversations.create(s.user.id);chat.chat(new Chat(s.user.id,c.id,"What now?"));
    assertThat(preferences.views(s.user.id)).containsExactly(new PreferenceView(PreferenceType.BUDGET_MAX,"12"));
    assertThat(llm.context.candidates()).allMatch(i->i.price().compareTo(new BigDecimal("12"))<=0);
  }
  @Test void placedOrderHasAuthoritativeSnapshotsAndIsIsolatedAndIdempotent() {
    var s=session();var req=request(s,"classic-beef-burger",2);
    var first=orders.place(req);var second=orders.place(req);
    assertThat(second.id()).isEqualTo(first.id());assertThat(remaining("classic-beef-burger")).isEqualTo(8);
    assertThat(first.total()).isEqualByComparingTo("28.00");
    assertThat(first.items().getFirst().name()).isEqualTo("Classic Beef Burger");
    chat.chat(new Chat(s.user.id,s.conversation.id,"new recommendations"));
    assertThat(orders.place(req).id()).isEqualTo(first.id()); // Replay survives a newer recommendation set.
    var other=users.login("other");assertThat(orders.history(other.id)).isEmpty();
    assertThat(orders.history(s.user.id).getFirst().items().getFirst().unitPrice()).isEqualByComparingTo("14");
    assertThatThrownBy(()->orders.place(new PlaceOrder(req.userId(),req.conversationId(),req.recommendationSetId(),req.requestId(),List.of(new OrderItem("classic-beef-burger",1)))))
        .isInstanceOf(OrderConflictException.class).hasMessageContaining("different order");
  }
  @Test void rejectUnrecommendedAndStaleAndForeignConversation() throws Exception {
    var s=session();
    assertThatThrownBy(()->orders.place(request(s,"garden-salad",1))).isInstanceOf(OrderConflictException.class);
    var other=users.login("other");
    mvc.perform(post("/api/orders").contentType("application/json").content("{\"userId\":"+other.id+",\"conversationId\":"+s.conversation.id+",\"recommendationSetId\":"+s.response.recommendationSetId()+",\"requestId\":\""+UUID.randomUUID()+"\",\"items\":[{\"menuItemId\":\"classic-beef-burger\",\"quantity\":1}]}"))
        .andExpect(status().isForbidden());
    chat.chat(new Chat(s.user.id,s.conversation.id,"new options"));
    assertThatThrownBy(()->orders.place(request(s,"classic-beef-burger",1))).isInstanceOf(OrderConflictException.class).hasMessageContaining("fresh");
    assertThat(orderRepo.count()).isZero();
  }
  @Test void emptySetSupersedesOldButFailedChatDoesNot() {
    var s=session();llm.invalid=true;
    assertThatThrownBy(()->chat.chat(new Chat(s.user.id,s.conversation.id,"hi"))).isInstanceOf(LlmException.class);
    assertThat(sets.findFirstByConversationIdOrderByIdDesc(s.conversation.id).orElseThrow().id).isEqualTo(s.response.recommendationSetId());
    llm.invalid=false;llm.intent=new Intent(List.of("sushi"),List.of(),List.of(),List.of(),List.of(),List.of(),null,null);
    var empty=chat.chat(new Chat(s.user.id,s.conversation.id,"sushi"));assertThat(empty.recommendations()).isEmpty();
    assertThatThrownBy(()->orders.place(request(s,"classic-beef-burger",1))).isInstanceOf(OrderConflictException.class);
  }
  @Test void fallbackRecommendationsAreOrderable() {
    llm.unavailable=true;var s=session();
    var pick=s.response.recommendations().getFirst();
    assertThat(orders.place(request(s,pick.id(),1)).items().getFirst().menuItemId()).isEqualTo(pick.id());
  }
  @Test void updatedPreferencesAreRecheckedAtCheckout() {
    var s=session();preferences.apply(s.user.id,null,List.of(new Update(Operation.UPSERT,PreferenceType.DIETARY_RESTRICTION,"vegan",1)));
    assertThatThrownBy(()->orders.place(request(s,"classic-beef-burger",1))).isInstanceOf(OrderConflictException.class).hasMessageContaining("preferences");
    assertThat(remaining("classic-beef-burger")).isEqualTo(10);assertThat(orderRepo.count()).isZero();
  }
  @Test void multiItemInsufficientStockRollsBackEarlierDeduction() {
    var s=session();jdbc.update("UPDATE menu_stock SET remaining_quantity=0 WHERE menu_item_id='jalapeno-chicken-burger'");
    var req=new PlaceOrder(s.user.id,s.conversation.id,s.response.recommendationSetId(),UUID.randomUUID(),List.of(new OrderItem("classic-beef-burger",2),new OrderItem("jalapeno-chicken-burger",1)));
    assertThatThrownBy(()->orders.place(req)).isInstanceOf(OrderConflictException.class);
    assertThat(remaining("classic-beef-burger")).isEqualTo(10);assertThat(orderRepo.count()).isZero();
  }
  @Test void soldOutItemsAreNotRecommended() {
    jdbc.update("UPDATE menu_stock SET remaining_quantity=0 WHERE menu_item_id='classic-beef-burger'");
    assertThat(session().response.recommendations()).noneMatch(i->i.id().equals("classic-beef-burger"));
  }
  @Test void rejectInvalidQuantitiesDuplicatesAndUnknownItems() throws Exception {
    var s=session();
    for(var items:List.of("[]","[{\"menuItemId\":\"classic-beef-burger\",\"quantity\":0}]","[{\"menuItemId\":\"classic-beef-burger\",\"quantity\":100}]","[{\"menuItemId\":\"classic-beef-burger\",\"quantity\":1},{\"menuItemId\":\"classic-beef-burger\",\"quantity\":2}]")) {
      mvc.perform(post("/api/orders").contentType("application/json").content("{\"userId\":"+s.user.id+",\"conversationId\":"+s.conversation.id+",\"recommendationSetId\":"+s.response.recommendationSetId()+",\"requestId\":\""+UUID.randomUUID()+"\",\"items\":"+items+"}"))
          .andExpect(status().isBadRequest());
    }
    assertThatThrownBy(()->orders.place(request(s,"invented",1))).isInstanceOf(OrderConflictException.class);
    assertThat(orderRepo.count()).isZero();
  }
  // Force both transactions to read the same version before either writes.
  void synchronizeStockReads(String id) {
    var barrier=new CyclicBarrier(2);
    doAnswer(invocation->{ var value=Optional.ofNullable(entityManager.find(MenuStock.class,id));barrier.await(10,TimeUnit.SECONDS);return value; }).when(stock).findById(id);
  }
  List<Object> concurrent(PlaceOrder a,PlaceOrder b) throws Exception {
    var pool=Executors.newFixedThreadPool(2);
    try {
      Callable<Object> first=()->{try{return orders.place(a);}catch(Exception e){return e;}};
      Callable<Object> second=()->{try{return orders.place(b);}catch(Exception e){return e;}};
      var futures=pool.invokeAll(List.of(first,second),20,TimeUnit.SECONDS);
      return List.of(futures.get(0).get(),futures.get(1).get());
    } finally {pool.shutdownNow();}
  }
  @Test void lastServingHasOneWinnerAndOptimisticConflict() throws Exception {
    var a=session();var b=session();jdbc.update("UPDATE menu_stock SET remaining_quantity=1 WHERE menu_item_id='classic-beef-burger'");
    synchronizeStockReads("classic-beef-burger");
    var results=concurrent(request(a,"classic-beef-burger",1),request(b,"classic-beef-burger",1));
    assertThat(results.stream().filter(OrderView.class::isInstance)).hasSize(1);
    assertThat(results.stream().filter(org.springframework.orm.ObjectOptimisticLockingFailureException.class::isInstance)).hasSize(1);
    assertThat(remaining("classic-beef-burger")).isZero();assertThat(orderRepo.count()).isEqualTo(1);
  }
  @Test void multiItemOptimisticConflictRollsBackWholeOrder() throws Exception {
    var a=session();var b=session();
    synchronizeStockReads("jalapeno-chicken-burger");
    var req=new PlaceOrder(a.user.id,a.conversation.id,a.response.recommendationSetId(),UUID.randomUUID(),List.of(new OrderItem("classic-beef-burger",2),new OrderItem("jalapeno-chicken-burger",1)));
    var results=concurrent(req,request(b,"jalapeno-chicken-burger",1));
    assertThat(results.stream().filter(OrderView.class::isInstance)).hasSize(1);
    assertThat(remaining("jalapeno-chicken-burger")).isEqualTo(9);
    boolean multiWon=results.getFirst() instanceof OrderView;
    assertThat(remaining("classic-beef-burger")).isEqualTo(multiWon?8:10);
    assertThat(orderRepo.count()).isEqualTo(1);
  }
  @Test void stockConflictIsHttp409AndRollsBack() throws Exception {
    var s=session();
    doThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(MenuStock.class,"classic-beef-burger")).when(stock).flush();
    mvc.perform(post("/api/orders").contentType("application/json").content("{\"userId\":"+s.user.id+",\"conversationId\":"+s.conversation.id+",\"recommendationSetId\":"+s.response.recommendationSetId()+",\"requestId\":\""+UUID.randomUUID()+"\",\"items\":[{\"menuItemId\":\"classic-beef-burger\",\"quantity\":1}]}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STOCK_CONFLICT"));
    assertThat(remaining("classic-beef-burger")).isEqualTo(10);assertThat(orderRepo.count()).isZero();
  }
  @Test void sufficientStockAfterConcurrentVersionConflictCanBeRetriedExplicitly() throws Exception {
    var a=session();var b=session();
    var reqA=request(a,"classic-beef-burger",1);var reqB=request(b,"classic-beef-burger",1);
    synchronizeStockReads("classic-beef-burger");
    var results=concurrent(reqA,reqB);
    assertThat(results.stream().filter(OrderView.class::isInstance)).hasSize(1);
    org.mockito.Mockito.reset(stock);
    var retry=results.getFirst() instanceof OrderView?reqB:reqA;
    orders.place(retry);
    assertThat(remaining("classic-beef-burger")).isEqualTo(8);assertThat(orderRepo.count()).isEqualTo(2);
  }
  @Test void sufficientStockAllowsTwoSequentialConcurrentSubmissions() throws Exception {
    var a=session();var b=session();
    var results=concurrent(request(a,"classic-beef-burger",1),request(b,"jalapeno-chicken-burger",1));
    assertThat(results).allMatch(OrderView.class::isInstance);
    assertThat(remaining("classic-beef-burger")).isEqualTo(9);assertThat(remaining("jalapeno-chicken-burger")).isEqualTo(9);
  }
}
