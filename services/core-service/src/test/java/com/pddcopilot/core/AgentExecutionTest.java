package com.pddcopilot.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pddcopilot.core.api.dto.AgentExecutionInput;
import com.pddcopilot.core.repository.AgentExecutionRepository;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.service.AgentExecutionService;
import com.pddcopilot.core.support.ShopScope;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.server.ResponseStatusException;

/** Real SQL transactions without platform, model, Redis or application scheduler dependencies. */
class AgentExecutionTest {
  private AnnotationConfigApplicationContext context;
  private AgentExecutionService executions;
  private JdbcTemplate jdbc;

  @Configuration
  @EnableTransactionManagement
  @org.mybatis.spring.annotation.MapperScan("com.pddcopilot.core.mapper")
  @Import({AgentExecutionRepository.class, AgentExecutionService.class})
  static class TestConfig {
    @Bean
    DataSource dataSource() {
      String url = System.getenv("AGENT_TEST_DATABASE_URL");
      if (url != null && url.startsWith("jdbc:postgresql:")) {
        String schema = "agent_execution_test_" + UUID.randomUUID().toString().replace("-", "");
        var source =
            new DriverManagerDataSource(
                url,
                System.getenv("AGENT_TEST_DATABASE_USER"),
                System.getenv("AGENT_TEST_DATABASE_PASSWORD"));
        new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
        source.setUrl(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        return source;
      }
      return new DriverManagerDataSource(
          "jdbc:h2:mem:execution-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
          "sa",
          "");
    }

    @Bean
    JdbcTemplate jdbc(DataSource source) {
      return new JdbcTemplate(source);
    }

    @Bean
    PlatformTransactionManager transactions(DataSource source) {
      return new DataSourceTransactionManager(source);
    }

    @Bean
    org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource source)
        throws Exception {
      var factory = new org.mybatis.spring.SqlSessionFactoryBean();
      factory.setDataSource(source);
      factory.setMapperLocations(
          new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
              .getResources("classpath*:mapper/*.xml"));
      var config = new org.apache.ibatis.session.Configuration();
      config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
      config.setCacheEnabled(false);
      config.setCallSettersOnNulls(true);
      config.setReturnInstanceForEmptyRow(true);
      config.setJdbcTypeForNull(org.apache.ibatis.type.JdbcType.NULL);
      factory.setConfiguration(config);
      return factory.getObject();
    }

    @Bean
    BusinessStore store(com.pddcopilot.core.mapper.BusinessMapper mapper) {
      return new BusinessStore(mapper, new ObjectMapper(), "shop-one");
    }
  }

  @BeforeEach
  void setup() {
    context = new AnnotationConfigApplicationContext(TestConfig.class);
    executions = context.getBean(AgentExecutionService.class);
    jdbc = context.getBean(JdbcTemplate.class);
    jdbc.execute("CREATE TABLE conversations(id VARCHAR PRIMARY KEY,shop_id VARCHAR)");
    jdbc.execute("CREATE TABLE messages(id VARCHAR PRIMARY KEY,conversation_id VARCHAR)");
    jdbc.execute(
        "CREATE TABLE agent_runs(id VARCHAR PRIMARY KEY,message_id VARCHAR,"
            + "knowledge_version VARCHAR,conversation_revision BIGINT,state VARCHAR)");
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V23__agent_execution_results.sql"))
        .execute(context.getBean(DataSource.class));
    jdbc.update("INSERT INTO conversations VALUES ('conversation-one','shop-one')");
    jdbc.update("INSERT INTO messages VALUES ('message-one','conversation-one')");
    jdbc.update("INSERT INTO agent_runs VALUES ('run-one','message-one','kb-one',1,'running')");
  }

  @AfterEach
  void close() {
    if (System.getenv("AGENT_TEST_DATABASE_URL") != null) {
      String schema = jdbc.queryForObject("SELECT current_schema()", String.class);
      if (schema != null && schema.matches("agent_execution_test_[a-f0-9]{32}"))
        jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }
    context.close();
  }

  AgentExecutionInput.Request request() {
    return new AgentExecutionInput.Request(
        "run-one", "shop-one", "conversation-one", "message-one", "kb-one");
  }

  Map<String, Object> result() {
    return Map.of(
        "run_id",
        "run-one",
        "message_id",
        "message-one",
        "knowledge_version",
        "kb-one",
        "conversation_revision",
        1,
        "outcome",
        "draft",
        "content",
        "已核实的测试资料。",
        "citations",
        List.of(),
        "risk_codes",
        List.of());
  }

  String claim() {
    return executions.claim("run-one", request()).get("lease_id").toString();
  }

  @Test
  void completedResultSurvivesServiceReplacementAndCannotBeOverwritten() {
    String lease = claim();
    var completion = new AgentExecutionInput.Completion(lease, result());
    executions.complete("run-one", completion);
    executions.complete("run-one", completion);
    var replacement =
        new AgentExecutionService(
            context.getBean(AgentExecutionRepository.class), context.getBean(BusinessStore.class));
    assertEquals(result(), replacement.claim("run-one", request()).get("result"));
    var changed = new HashMap<>(result());
    changed.put("content", "different");
    assertThrows(
        ResponseStatusException.class,
        () -> executions.complete("run-one", new AgentExecutionInput.Completion(lease, changed)));
  }

  @Test
  void concurrentClaimsHaveOneOwner() throws Exception {
    var executor = Executors.newFixedThreadPool(8);
    try {
      var calls = new ArrayList<Callable<Map<String, Object>>>();
      for (int i = 0; i < 16; i++) calls.add(() -> executions.claim("run-one", request()));
      int claimed = 0, running = 0;
      for (var future : executor.invokeAll(calls)) {
        String state = future.get().get("state").toString();
        if (state.equals("claimed")) claimed++;
        if (state.equals("running")) running++;
      }
      assertEquals(1, claimed);
      assertEquals(15, running);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void expiredLeaseCannotCompleteOrReleaseSuccessor() {
    String old = claim();
    jdbc.update("UPDATE agent_execution_results SET lease_until=0");
    String successor = claim();
    assertNotEquals(old, successor);
    executions.release("run-one", new AgentExecutionInput.Release(old));
    assertEquals("running", executions.claim("run-one", request()).get("state"));
    assertThrows(
        ResponseStatusException.class,
        () -> executions.complete("run-one", new AgentExecutionInput.Completion(old, result())));
    executions.complete("run-one", new AgentExecutionInput.Completion(successor, result()));
  }

  @Test
  void failedExecutionCanBeClaimedAgainAndReleaseKeepsCommittedResult() {
    String first = claim();
    executions.release("run-one", new AgentExecutionInput.Release(first));
    String second = claim();
    assertNotEquals(first, second);
    executions.complete("run-one", new AgentExecutionInput.Completion(second, result()));
    executions.release("run-one", new AgentExecutionInput.Release(second));
    assertEquals("completed", executions.claim("run-one", request()).get("state"));
  }

  @Test
  void identityAndShopAreValidatedEvenForCachedResults() {
    executions.complete("run-one", new AgentExecutionInput.Completion(claim(), result()));
    assertThrows(
        ResponseStatusException.class,
        () ->
            executions.claim(
                "run-one",
                new AgentExecutionInput.Request(
                    "run-one", "shop-one", "conversation-other", "message-one", "kb-one")));
    try (var scope = ShopScope.open("another-shop")) {
      assertThrows(ResponseStatusException.class, () -> executions.claim("run-one", request()));
    }
  }

  @Test
  void invalidResultCannotPoisonCache() {
    String lease = claim();
    var invalid = new HashMap<>(result());
    invalid.remove("conversation_revision");
    assertThrows(
        ResponseStatusException.class,
        () -> executions.complete("run-one", new AgentExecutionInput.Completion(lease, invalid)));
    assertEquals("running", executions.claim("run-one", request()).get("state"));
  }
}
