package com.example.restaurantassistant;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="DATABASE_INTEGRATION",matches="true")
class MigrationIntegrationTest {
  String url=System.getenv("TEST_DATABASE_URL");
  void upgrade() throws Exception {
    String schema="legacy_"+UUID.randomUUID().toString().replace("-","");
    try(var c=DriverManager.getConnection(url,"restaurant","test-only");var statement=c.createStatement()) {
      var original=Flyway.configure().dataSource(url,"restaurant","test-only").schemas(schema).defaultSchema(schema)
          .locations("classpath:db/migration").target("2").load();
      original.migrate();
      statement.execute("SET search_path TO "+schema+",public");
      statement.execute("INSERT INTO app_users(id,username,created_at,onboarding_completed) VALUES(100,'legacy',CURRENT_TIMESTAMP,true)");
      statement.execute("INSERT INTO conversations(id,user_id,created_at) VALUES(100,100,CURRENT_TIMESTAMP)");
      statement.execute("INSERT INTO messages(id,conversation_id,role,content,created_at) VALUES(100,100,'USER','Old message',CURRENT_TIMESTAMP)");
      statement.execute("INSERT INTO preferences(user_id,type,preference_value,status,confidence,source_message_id,created_at,updated_at) VALUES(100,'ALLERGY','peanut','ACTIVE',1,100,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
      var current=Flyway.configure().dataSource(url,"restaurant","test-only").schemas(schema).defaultSchema(schema)
          .locations("classpath:db/migration").load();
      current.migrate();
      try(var rs=statement.executeQuery("SELECT preference_value FROM preferences WHERE user_id=100")) { assertThat(rs.next()).isTrue();assertThat(rs.getString(1)).isEqualTo("peanut"); }
      try(var rs=statement.executeQuery("SELECT content FROM messages WHERE id=100")) { assertThat(rs.next()).isTrue();assertThat(rs.getString(1)).isEqualTo("Old message"); }
      try(var rs=statement.executeQuery("SELECT COUNT(*) FROM menu_stock WHERE remaining_quantity=10")) {rs.next();assertThat(rs.getInt(1)).isEqualTo(16);}
      statement.execute("UPDATE menu_stock SET remaining_quantity=3 WHERE menu_item_id='classic-beef-burger'");
      current.migrate();
      try(var rs=statement.executeQuery("SELECT remaining_quantity FROM menu_stock WHERE menu_item_id='classic-beef-burger'")) {rs.next();assertThat(rs.getInt(1)).isEqualTo(3);}
      try(var rs=statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"+schema+"' AND table_name IN ('outbox_events','semantic_memories','semantic_memory_vectors')")) {rs.next();assertThat(rs.getInt(1)).isZero();}
    } finally {
      try(var c=DriverManager.getConnection(url,"restaurant","test-only");var statement=c.createStatement()) {statement.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
    }
  }
  @Test void upgradeFromV2PreservesDataAndRestartDoesNotResetStock() throws Exception {upgrade();}
}
