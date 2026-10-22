package com.example.restaurantassistant.service.memory;

import com.example.restaurantassistant.dto.Contracts.*;
import com.example.restaurantassistant.entity.*;
import com.example.restaurantassistant.model.*;
import com.example.restaurantassistant.model.Role;
import com.example.restaurantassistant.repository.MessageRepository;
import com.example.restaurantassistant.service.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"app.memory.enabled=true","app.memory.initial-delay-ms=600000","spring.rabbitmq.listener.simple.auto-startup=false"})
@EnabledIfEnvironmentVariable(named="DATABASE_INTEGRATION",matches="true")
class MemoryIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",()->System.getenv("TEST_DATABASE_URL"));
    r.add("spring.datasource.username",()->"restaurant");r.add("spring.datasource.password",()->"test-only");
    r.add("spring.rabbitmq.port",()->System.getenv("TEST_RABBITMQ_PORT"));
  }
  @TestConfiguration static class Config { @Bean @Primary FakeEmbedding fakeEmbedding() {return new FakeEmbedding();} }
  static class FakeEmbedding implements EmbeddingService {
    int calls; boolean fail, permanent;
    public String model(){return "test-embedding";}
    public float[] embed(String text,boolean query){calls++;if(fail)throw new EmbeddingFailure(!permanent);return vector();}
  }
  static float[] vector(){float[] v=new float[768];v[0]=1;return v;}
  @Autowired JdbcTemplate jdbc;
  @Autowired UserService users;
  @Autowired ConversationService conversations;
  @Autowired PreferenceService preferences;
  @Autowired MessageRepository messages;
  @Autowired MemoryService memories;
  @Autowired MemoryJobs jobs;
  @Autowired FakeEmbedding embedding;
  @Autowired RabbitTemplate rabbit;
  @Autowired AmqpAdmin admin;
  @Autowired PlatformTransactionManager manager;
  @BeforeEach void reset(){jdbc.update("DELETE FROM memory_outbox");jdbc.update("DELETE FROM contextual_memories");embedding.calls=0;embedding.fail=false;embedding.permanent=false;admin.declareQueue(new org.springframework.amqp.core.Queue(MemoryJobs.QUEUE,true));admin.purgeQueue(MemoryJobs.QUEUE,false);}
  AppUser user(){return users.login("m"+UUID.randomUUID().toString().replace("-",""));}
  long stage(AppUser user,String text){
    return new TransactionTemplate(manager).execute(status->{
      var conversation=conversations.create(user.id);
      var message=new Message();message.conversationId=conversation.id;message.role=Role.USER;message.content=text;messages.saveAndFlush(message);
      memories.stage(user.id,message.id,text);
      return jdbc.queryForObject("SELECT id FROM contextual_memories WHERE source_message_id=?",Long.class,message.id);
    });
  }
  String state(long id){return jdbc.queryForObject("SELECT status FROM contextual_memories WHERE id=?",String.class,id);}
  void expire(long id){jdbc.update("UPDATE contextual_memories SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second',next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",id);}

  @Test void outboxBrokerEmbeddingAndUserScopedVectorRetrieval(){
    var a=user();var b=user();long id=stage(a,"I enjoy lighter meals after my evening workout.");
    assertThat(state(id)).isEqualTo("PENDING");assertThat(embedding.calls).isZero();
    jobs.publish();Object body=rabbit.receiveAndConvert(MemoryJobs.QUEUE,3000);assertThat(body).isEqualTo(Long.toString(id));
    jobs.consume((String)body);assertThat(state(id)).isEqualTo("ACTIVE");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_outbox WHERE memory_id=?",Long.class,id)).isZero();
    assertThat(memories.relevant(a.id,conversations.create(a.id).id,"What should I eat after training?")).containsExactly("I enjoy lighter meals after my evening workout.");
    assertThat(memories.relevant(b.id,conversations.create(b.id).id,"What should I eat?")).isEmpty();
    jobs.consume(Long.toString(id));assertThat(embedding.calls).isEqualTo(2); // one document, one query; duplicate delivery does nothing
  }
  @Test void sameConversationGymMemorySurvivesNewAndRepeatedLikes(){
    var user=user();long id=stage(user,"I love eating beef after the gym.");jobs.consume(Long.toString(id));
    long conversation=jdbc.queryForObject("SELECT s.conversation_id FROM contextual_memories m JOIN messages s ON s.id=m.source_message_id WHERE m.id=?",Long.class,id);
    preferences.apply(user.id,null,List.of(new Update(Operation.UPSERT,PreferenceType.INGREDIENT_LIKE,"beef",1)));
    preferences.apply(user.id,null,List.of(new Update(Operation.UPSERT,PreferenceType.INGREDIENT_LIKE,"beef",1)));
    preferences.apply(user.id,null,List.of(new Update(Operation.UPSERT,PreferenceType.INGREDIENT_LIKE,"chicken",1)));
    assertThat(state(id)).isEqualTo("ACTIVE");
    assertThat(memories.relevant(user.id,conversation,"I just finished training")).contains("I love eating beef after the gym.");
  }
  @Test void unrelatedInFlightMemoryCanFinishAfterCorrection(){
    var user=user();long beef=stage(user,"I love beef after finishing my workout.");var beefJob=jobs.claim(beef);
    long other=stage(user,"I enjoy a smaller salad for lunch.");var otherJob=jobs.claim(other);
    preferences.apply(user.id,null,List.of(new Update(Operation.REMOVE,PreferenceType.INGREDIENT_LIKE,"beef",1)));
    jobs.activate(beefJob,vector());jobs.activate(otherJob,vector());
    assertThat(state(beef)).isEqualTo("INACTIVE");assertThat(state(other)).isEqualTo("ACTIVE");
  }
  @Test void oppositePreferenceInvalidatesRelatedContextOnly(){
    var user=user();preferences.apply(user.id,null,List.of(new Update(Operation.UPSERT,PreferenceType.INGREDIENT_LIKE,"beef",1)));
    long beef=stage(user,"I love beef after the gym.");jobs.consume(Long.toString(beef));
    long other=stage(user,"I like a lighter salad for lunch.");jobs.consume(Long.toString(other));
    preferences.apply(user.id,null,List.of(new Update(Operation.UPSERT,PreferenceType.INGREDIENT_DISLIKE,"beef",1)));
    assertThat(state(beef)).isEqualTo("INACTIVE");assertThat(state(other)).isEqualTo("ACTIVE");
  }
  @Test void shortContextIsIndexedAndLowerSimilarityIsNotSilentlyDiscarded(){
    var user=user();long id=stage(user,"beef after gym");
    float[] document=vector();document[0]=0.2f;document[1]=1;
    jobs.activate(jobs.claim(id),document);
    assertThat(memories.relevant(user.id,conversations.create(user.id).id,"finished training")).containsExactly("beef after gym");
  }

  @Test void concurrentClaimsAllowOneWorker() throws Exception {
    long id=stage(user(),"I enjoyed a light chicken meal after training.");
    var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
    try {
      Callable<MemoryJobs.Job> call=()->{start.await();return jobs.claim(id);};
      var a=pool.submit(call);var b=pool.submit(call);start.countDown();
      assertThat(java.util.stream.Stream.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS)).filter(Objects::nonNull).count()).isEqualTo(1);
    } finally {pool.shutdownNow();}
  }
  @Test void correctionInvalidatesActiveAndInFlightResults(){
    var user=user();long id=stage(user,"I enjoyed mushroom pizza last weekend.");var job=jobs.claim(id);
    long active=stage(user,"I enjoyed a small salad after football.");jobs.consume(Long.toString(active));assertThat(state(active)).isEqualTo("ACTIVE");
    preferences.apply(user.id,null,List.of(new Update(Operation.REMOVE,PreferenceType.INGREDIENT_LIKE,"mushroom",1)));
    jobs.activate(job,vector());assertThat(state(id)).isEqualTo("INACTIVE");assertThat(state(active)).isEqualTo("ACTIVE");assertThat(memories.list(user.id)).extracting(MemoryService.MemoryView::id).containsExactly(active);
    assertThat(preferences.views(user.id)).doesNotContain(new PreferenceView(PreferenceType.INGREDIENT_LIKE,"mushroom"));
  }
  @Test void leaseRecoveryRejectsOldClaim(){
    long id=stage(user(),"I enjoy smaller meals late in the evening.");
    var old=jobs.claim(id);expire(id);jobs.activate(old,vector());assertThat(state(id)).isEqualTo("PROCESSING");var replacement=jobs.claim(id);
    assertThat(replacement.token()).isNotEqualTo(old.token());
    jobs.activate(old,vector());assertThat(state(id)).isEqualTo("PROCESSING");
    jobs.activate(replacement,vector());assertThat(state(id)).isEqualTo("ACTIVE");
  }
  @Test void transientFailuresStopAfterThreeAttempts(){
    long id=stage(user(),"I enjoy a warm bowl after playing football.");embedding.fail=true;
    for(int i=0;i<3;i++){jobs.consume(Long.toString(id));if(i<2)expire(id);}
    assertThat(state(id)).isEqualTo("FAILED");assertThat(embedding.calls).isEqualTo(3);jobs.consume(Long.toString(id));assertThat(embedding.calls).isEqualTo(3);
  }
  @Test void permanentFailureAndRetrievalOutageDoNotChangePreferences(){
    var user=user();long id=stage(user,"I enjoy lighter meals after my workout.");jobs.consume(Long.toString(id));
    embedding.fail=true;
    assertThat(memories.relevant(user.id,conversations.create(user.id).id,"Dinner?")).isEmpty();assertThat(state(id)).isEqualTo("ACTIVE");
    long second=stage(user,"I sometimes prefer a small lunch at work.");embedding.permanent=true;jobs.consume(Long.toString(second));assertThat(state(second)).isEqualTo("FAILED");
  }
  @Test void rolledBackStageDoesNotLeaveOutboxOrMemory(){
    assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(status->{stage(user(),"I enjoy light meals after evening exercise.");throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM contextual_memories",Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_outbox",Long.class)).isZero();
  }
  @Test void brokerOutagePreservesWorkForRecovery(){
    long id=stage(user(),"I prefer lighter meals after evening exercise.");
    var unavailable=org.mockito.Mockito.mock(RabbitTemplate.class);
    org.mockito.Mockito.when(unavailable.invoke(org.mockito.ArgumentMatchers.any())).thenThrow(new org.springframework.amqp.AmqpException("broker unavailable"));
    new MemoryJobs(jdbc,unavailable,embedding,manager).publish();
    assertThat(state(id)).isEqualTo("PENDING");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_outbox WHERE memory_id=?",Long.class,id)).isEqualTo(1);
    jdbc.update("UPDATE memory_outbox SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE memory_id=?",id);
    jobs.publish();var body=rabbit.receiveAndConvert(MemoryJobs.QUEUE,3000);assertThat(body).isEqualTo(Long.toString(id));
    jobs.consume((String)body);assertThat(state(id)).isEqualTo("ACTIVE");
  }

  @Test void fastConsumerFailureKeepsRetryScheduleWhenPublishFinishes(){
    long id=stage(user(),"I enjoy smaller lunches after morning exercise.");embedding.fail=true;
    var transport=org.mockito.Mockito.mock(RabbitTemplate.class);
    org.mockito.Mockito.when(transport.invoke(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{jobs.consume(Long.toString(id));return null;});
    new MemoryJobs(jdbc,transport,embedding,manager).publish();
    assertThat(state(id)).isEqualTo("PENDING");
    assertThat(jdbc.queryForObject("SELECT next_publish_at<CURRENT_TIMESTAMP+INTERVAL '10 seconds' FROM memory_outbox WHERE memory_id=?",Boolean.class,id)).isTrue();
  }

  @Test void exhaustedCrashedWorkerIsRecoveredToFailed(){
    long id=stage(user(),"I enjoy chicken after playing football.");
    for(int i=0;i<3;i++){assertThat(jobs.claim(id)).isNotNull();expire(id);}
    jobs.publish();assertThat(state(id)).isEqualTo("FAILED");
  }
}
