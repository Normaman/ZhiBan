package com.pddcopilot.core;

import static org.junit.jupiter.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class WorkspaceMigrationTest {
  @Test
  void upgradePreservesLegacyAccountsKnowledgeMessagesApprovalsAndPendingJobs() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:legacy-upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    Flyway.configure().dataSource(source).target("8").load().migrate();
    var db = new JdbcTemplate(source);
    db.update(
        "INSERT INTO shops(id,name,connector_account_id,platform_shop_id,mode) VALUES"
            + " ('legacy','已有店铺','account-fixture','platform-fixture','live')");
    db.update(
        "INSERT INTO app_users VALUES"
            + " ('admin-id','legacy-admin','fixture-hash','legacy','ADMIN',TRUE,1),('staff-id','legacy-staff','fixture-hash','legacy','REVIEWER',TRUE,2)");
    db.update("INSERT INTO knowledge_versions VALUES ('existing-kb','legacy','[]',1)");
    db.update("UPDATE shops SET knowledge_version='existing-kb' WHERE id='legacy'");
    db.update(
        "INSERT INTO conversations VALUES ('conversation','legacy','fixture-buyer',3,'open',1)");
    db.update(
        "INSERT INTO"
            + " messages(id,shop_id,conversation_id,platform_message_id,event_id,direction,message_type,content,received_at,payload_hash,state,created_at)"
            + " VALUES"
            + " ('message','legacy','conversation','platform-message','event','inbound','text','合成测试咨询','2026-10-27T00:00:00Z','fixture-hash','agent_running',1)");
    db.update("INSERT INTO agent_runs VALUES ('run','message',3,'existing-kb','queued',NULL,1)");
    db.update(
        "INSERT INTO drafts VALUES"
            + " ('draft','message','run',1,3,'approved','合成测试回复','[]','[]','existing-kb','draft',1)");
    db.update(
        "INSERT INTO decisions VALUES"
            + " ('approval','draft',1,'approve','人工核对后的合成回复','legacy-admin',1)");
    db.update(
        "INSERT INTO send_commands(id,approval_id,draft_id,revision,state,updated_at) VALUES"
            + " ('command','approval','draft',1,'approved',1)");
    db.update(
        "INSERT INTO jobs(id,kind,reference_id,state,available_at) VALUES"
            + " ('generation-job','generate','run','pending',1),('send-job','send','command','pending',1)");
    Flyway.configure().dataSource(source).load().migrate();
    assertEquals(2, db.queryForObject("SELECT count(*) FROM app_users", Integer.class));
    assertEquals(
        2,
        db.queryForObject(
            "SELECT count(*) FROM workspace_memberships WHERE workspace_id='legacy'",
            Integer.class));
    assertEquals(
        "ADMIN",
        db.queryForObject(
            "SELECT role FROM workspace_memberships WHERE user_id='admin-id'", String.class));
    assertEquals(
        "REVIEWER",
        db.queryForObject(
            "SELECT role FROM workspace_memberships WHERE user_id='staff-id'", String.class));
    assertEquals(
        "admin-id",
        db.queryForObject("SELECT owner_user_id FROM workspaces WHERE id='legacy'", String.class));
    assertEquals(
        "legacy",
        db.queryForObject("SELECT workspace_id FROM shops WHERE id='legacy'", String.class));
    assertEquals(
        "existing-kb",
        db.queryForObject(
            "SELECT version_label FROM knowledge_versions WHERE id='existing-kb'", String.class));
    assertEquals(
        "existing-kb",
        db.queryForObject("SELECT knowledge_version FROM shops WHERE id='legacy'", String.class));
    assertEquals(
        2, db.queryForObject("SELECT count(*) FROM jobs WHERE shop_id='legacy'", Integer.class));
    assertEquals(
        "人工核对后的合成回复",
        db.queryForObject("SELECT final_content FROM decisions WHERE id='approval'", String.class));
    assertEquals(
        "legacy",
        db.queryForObject(
            "SELECT shop_id FROM connector_bindings WHERE platform_shop_id='platform-fixture'",
            String.class));
    assertEquals(1, db.queryForObject("SELECT count(*) FROM messages", Integer.class));
    assertEquals(1, db.queryForObject("SELECT count(*) FROM conversations", Integer.class));
  }
}
