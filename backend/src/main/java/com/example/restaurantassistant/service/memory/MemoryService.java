package com.example.restaurantassistant.service.memory;

import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Structured preferences are authoritative; these are historical utterances, not facts. */
@Service
public class MemoryService {
  public record MemoryView(long id, String content, String status, int attempts, String errorCategory) {}
  private final JdbcTemplate jdbc;
  private final EmbeddingService embeddings;
  private final boolean enabled, retrieval;
  public MemoryService(JdbcTemplate jdbc, EmbeddingService embeddings,
      @Value("${app.memory.enabled}") boolean enabled,
      @Value("${app.memory.retrieval-enabled:true}") boolean retrieval) {
    this.jdbc=jdbc; this.embeddings=embeddings; this.enabled=enabled; this.retrieval=retrieval;
  }
  public void invalidate(Long userId, Set<String> correctedValues, Set<Long> sourceMessages, long version) {
    if(!enabled) return;
    // Caller holds the user lock. Invalidate source-linked/explicitly mentioned corrected facts,
    // while preserving unrelated history and allowing unaffected in-flight jobs to finish.
    var rows=jdbc.query("SELECT id,source_message_id,content FROM contextual_memories WHERE user_id=? AND status<>'INACTIVE'",
        (rs,n)->new Historical(rs.getLong(1),rs.getLong(2),rs.getString(3)),userId);
    for(var row:rows) {
      String content=com.example.restaurantassistant.service.menu.MenuService.canonical(row.content());
      boolean affected=sourceMessages.contains(row.sourceMessageId()) || correctedValues.stream().anyMatch(value->
          java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])"+java.util.regex.Pattern.quote(value)+"(?![\\p{L}\\p{N}])").matcher(content).find());
      if(affected)jdbc.update("UPDATE contextual_memories SET status='INACTIVE',claim_token=NULL,lease_until=NULL,embedding=NULL WHERE id=?",row.id());
    }
    jdbc.update("UPDATE contextual_memories SET memory_version=? WHERE user_id=? AND status<>'INACTIVE'",version,userId);
  }
  private record Historical(long id,long sourceMessageId,String content) {}
  public void stage(Long userId, Long sourceMessageId, String content) {
    if(!enabled || content.isBlank()) return;
    Long version=jdbc.queryForObject("SELECT memory_version FROM app_users WHERE id=?",Long.class,userId);
    Long id=jdbc.queryForObject("INSERT INTO contextual_memories(user_id,source_message_id,memory_version,content) VALUES(?,?,?,?) RETURNING id",
        Long.class,userId,sourceMessageId,version,content);
    jdbc.update("INSERT INTO memory_outbox(memory_id) VALUES(?)",id);
  }
  public List<MemoryView> list(Long userId) {
    if(!enabled) return List.of();
    return jdbc.query("SELECT id,content,status,attempts,error_category FROM contextual_memories WHERE user_id=? AND status<>'INACTIVE' ORDER BY id DESC LIMIT 10",
        (rs,n)->new MemoryView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getString(5)),userId);
  }
  public List<String> relevant(Long userId, Long conversationId, String currentMessage) {
    if(!enabled || !retrieval) return List.of();
    // Same-conversation memories are eligible too, including turns outside recent chat history.
    var count=jdbc.queryForObject("SELECT count(*) FROM contextual_memories m JOIN app_users u ON u.id=m.user_id WHERE m.user_id=? AND m.status='ACTIVE' AND m.memory_version=u.memory_version AND m.embedding_model=?",
        Long.class,userId,embeddings.model());
    if(count==null || count==0) return List.of();
    try {
      String vector=vector(embeddings.embed(currentMessage,true));
      var matches=jdbc.query("SELECT m.id,m.content,1-(m.embedding <=> CAST(? AS public.vector)) AS similarity FROM contextual_memories m JOIN app_users u ON u.id=m.user_id WHERE m.user_id=? AND m.status='ACTIVE' AND m.memory_version=u.memory_version AND m.embedding_model=? ORDER BY m.embedding <=> CAST(? AS public.vector),m.id DESC LIMIT 3",
          (rs,n)->new Match(rs.getLong(1),rs.getString(2),rs.getDouble(3)),vector,userId,embeddings.model(),vector);
      LoggerFactory.getLogger(getClass()).info("Memory retrieval userId={} conversationId={} matches={}",userId,conversationId,
          matches.stream().map(m->Map.of("id",m.id(),"similarity",m.similarity())).toList());
      return matches.stream().map(Match::content).toList();
    } catch(EmbeddingService.EmbeddingFailure e) {
      LoggerFactory.getLogger(getClass()).warn("Optional memory retrieval unavailable userId={}",userId);
      return List.of();
    }
  }
  private record Match(long id,String content,double similarity) {}
  public static String vector(float[] values) {
    if(values==null || values.length!=768) throw new EmbeddingService.EmbeddingFailure(false);
    var parts=new ArrayList<String>(); double norm=0;
    for(float value:values) {if(!Float.isFinite(value)) throw new EmbeddingService.EmbeddingFailure(false); parts.add(Float.toString(value));norm+=value*value;}
    if(norm==0) throw new EmbeddingService.EmbeddingFailure(false);
    return "["+String.join(",",parts)+"]";
  }
}
