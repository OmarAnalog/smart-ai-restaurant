package com.example.restaurantassistant.service.memory;

import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Component
@ConditionalOnProperty(name="app.memory.enabled",havingValue="true")
public class MemoryJobs {
  public static final String QUEUE="restaurant.memory.index";
  private final JdbcTemplate jdbc;
  private final RabbitTemplate rabbit;
  private final EmbeddingService embeddings;
  private final TransactionTemplate tx;
  public MemoryJobs(JdbcTemplate jdbc,RabbitTemplate rabbit,EmbeddingService embeddings,PlatformTransactionManager manager) {
    this.jdbc=jdbc;this.rabbit=rabbit;this.embeddings=embeddings;this.tx=new TransactionTemplate(manager);
  }
  @Bean org.springframework.amqp.core.Queue memoryQueue() {return QueueBuilder.durable(QUEUE).build();}
  record Delivery(long memoryId,String token) {}
  record Job(long id,long userId,long version,String content,String token,int attempts) {}

  @Scheduled(fixedDelayString="${app.memory.poll-ms:1000}",initialDelayString="${app.memory.initial-delay-ms:1000}")
  public void publish() {
    try {
      Delivery delivery=tx.execute(status->{
        jdbc.update("UPDATE contextual_memories SET status='FAILED',error_category='LEASE_EXHAUSTED',claim_token=NULL WHERE status='PROCESSING' AND lease_until<CURRENT_TIMESTAMP AND attempts>=3");
        jdbc.update("DELETE FROM memory_outbox WHERE memory_id IN (SELECT id FROM contextual_memories WHERE status IN ('ACTIVE','FAILED','INACTIVE'))");
        var ids=jdbc.queryForList("SELECT o.memory_id FROM memory_outbox o JOIN contextual_memories m ON m.id=o.memory_id WHERE o.next_publish_at<=CURRENT_TIMESTAMP AND (o.lease_until IS NULL OR o.lease_until<CURRENT_TIMESTAMP) AND ((m.status='PENDING' AND m.next_attempt_at<=CURRENT_TIMESTAMP) OR (m.status='PROCESSING' AND m.lease_until<CURRENT_TIMESTAMP)) ORDER BY o.memory_id LIMIT 1 FOR UPDATE OF o SKIP LOCKED",Long.class);
        if(ids.isEmpty()) return null;
        String token=UUID.randomUUID().toString();
        jdbc.update("UPDATE memory_outbox SET claim_token=?,lease_until=CURRENT_TIMESTAMP+INTERVAL '60 seconds' WHERE memory_id=?",token,ids.getFirst());
        return new Delivery(ids.getFirst(),token);
      });
      if(delivery==null)return;
      // The DB transaction is already committed. Broker confirmation is outside database locks.
      rabbit.invoke(operations->{
        operations.convertAndSend("",QUEUE,Long.toString(delivery.memoryId()),message->{message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);return message;});
        operations.waitForConfirmsOrDie(5000);return null;
      });
      jdbc.update("UPDATE memory_outbox SET lease_until=NULL,next_publish_at=CURRENT_TIMESTAMP+INTERVAL '60 seconds' WHERE memory_id=? AND claim_token=?",delivery.memoryId(),delivery.token());
    } catch(Exception e) {
      LoggerFactory.getLogger(getClass()).warn("Memory dispatch deferred error={}",e.getClass().getSimpleName());
      // The expiring claim retries broker failures; a confirmed delivery may safely be delivered again.
    }
  }

  @RabbitListener(queues=QUEUE)
  public void consume(String body) {
    long id;
    try { id=Long.parseLong(body); } catch(NumberFormatException e) {return;}
    Job job=claim(id);
    if(job==null)return;
    try {
      float[] vector=embeddings.embed(job.content(),false);
      activate(job,vector);
    } catch(EmbeddingService.EmbeddingFailure e) {fail(job,e.retryable);
    } catch(RuntimeException e) {
      // If persistence fails, leave the processing lease to expire; the outbox redelivers.
      LoggerFactory.getLogger(getClass()).warn("Memory completion deferred memoryId={} error={}",id,e.getClass().getSimpleName());
    }
  }
  Job claim(long id) {
    return tx.execute(status->{
      var rows=jdbc.query("SELECT id,user_id,memory_version,content,attempts FROM contextual_memories WHERE id=? AND attempts<3 AND ((status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP) OR (status='PROCESSING' AND lease_until<CURRENT_TIMESTAMP)) FOR UPDATE SKIP LOCKED",
          (rs,n)->new Job(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),UUID.randomUUID().toString(),rs.getInt(5)+1),id);
      if(rows.isEmpty())return null;
      Job job=rows.getFirst();
      jdbc.update("UPDATE contextual_memories SET status='PROCESSING',attempts=?,claim_token=?,lease_until=CURRENT_TIMESTAMP+INTERVAL '60 seconds' WHERE id=?",job.attempts(),job.token(),id);
      return job;
    });
  }
  void activate(Job job,float[] embedding) {
    String vector=MemoryService.vector(embedding);
    tx.executeWithoutResult(status->{
      // Same lock order as chat: user first. A correction cannot race vector activation.
      Long version=jdbc.queryForObject("SELECT memory_version FROM app_users WHERE id=? FOR UPDATE",Long.class,job.userId());
      if(version==null)return;
      int changed=jdbc.update("UPDATE contextual_memories SET status='ACTIVE',embedding=CAST(? AS public.vector),embedding_model=?,claim_token=NULL,lease_until=NULL,error_category=NULL WHERE id=? AND status='PROCESSING' AND claim_token=? AND memory_version=? AND lease_until>CURRENT_TIMESTAMP",
          vector,embeddings.model(),job.id(),job.token(),version);
      if(changed==1)jdbc.update("DELETE FROM memory_outbox WHERE memory_id=?",job.id());
    });
  }
  void fail(Job job,boolean retryable) {
    tx.executeWithoutResult(status->{
      String state=retryable && job.attempts()<3?"PENDING":"FAILED";
      int changed=jdbc.update("UPDATE contextual_memories SET status=?,error_category=?,claim_token=NULL,lease_until=NULL,next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '5 seconds' WHERE id=? AND status='PROCESSING' AND claim_token=?",
          state,retryable?"TRANSIENT":"PERMANENT",job.id(),job.token());
      if(changed==1)jdbc.update("UPDATE memory_outbox SET next_publish_at=CURRENT_TIMESTAMP+INTERVAL '5 seconds',lease_until=NULL,claim_token=NULL WHERE memory_id=?",job.id());
      LoggerFactory.getLogger(getClass()).warn("Memory embedding memoryId={} status={} attempts={}",job.id(),state,job.attempts());
    });
  }
}
