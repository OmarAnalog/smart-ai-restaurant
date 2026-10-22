package com.example.restaurantassistant.service.memory;

import com.example.restaurantassistant.entity.Message;
import com.example.restaurantassistant.model.Role;
import com.example.restaurantassistant.repository.MessageRepository;
import com.example.restaurantassistant.service.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"app.memory.enabled=true","app.memory.initial-delay-ms=100","app.memory.poll-ms=100","spring.rabbitmq.listener.simple.auto-startup=true"})
@Import(MemoryIntegrationTest.Config.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="DATABASE_INTEGRATION",matches="true")
class MemorySchedulingIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",()->System.getenv("TEST_DATABASE_URL"));
    r.add("spring.datasource.username",()->"restaurant");r.add("spring.datasource.password",()->"test-only");
    r.add("spring.rabbitmq.port",()->System.getenv("TEST_RABBITMQ_PORT"));
  }
  @Autowired UserService users;
  @Autowired ConversationService conversations;
  @Autowired MessageRepository messages;
  @Autowired MemoryService memories;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager manager;
  @Test void scheduledPublisherAndRealListenerIndexWithoutManualDispatch() throws Exception {
    var user=users.login("scheduled"+UUID.randomUUID().toString().replace("-",""));
    long memoryId=new TransactionTemplate(manager).execute(status->{
      var conversation=conversations.create(user.id);var message=new Message();message.conversationId=conversation.id;
      message.role=Role.USER;message.content="I enjoyed a light meal after playing football.";messages.saveAndFlush(message);
      memories.stage(user.id,message.id,message.content);
      return jdbc.queryForObject("SELECT id FROM contextual_memories WHERE source_message_id=?",Long.class,message.id);
    });
    long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
    while(System.nanoTime()<deadline) {
      if("ACTIVE".equals(jdbc.queryForObject("SELECT status FROM contextual_memories WHERE id=?",String.class,memoryId)))return;
      Thread.sleep(100);
    }
    assertThat(jdbc.queryForObject("SELECT status FROM contextual_memories WHERE id=?",String.class,memoryId)).isEqualTo("ACTIVE");
  }
}
