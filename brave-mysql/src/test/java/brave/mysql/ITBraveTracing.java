package brave.mysql;

import brave.Tracer;
import brave.internal.StrictCurrentTraceContext;
import brave.sampler.Sampler;
import com.github.kristofa.brave.internal.Util;
import com.mysql.jdbc.jdbc2.optional.MysqlDataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import zipkin.Constants;
import zipkin.Endpoint;
import zipkin.Span;
import zipkin.TraceKeys;
import zipkin.storage.InMemoryStorage;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;
import static zipkin.internal.Util.envOr;

public class ITBraveTracing {
  static final String QUERY = "select 'hello world'";

  Endpoint local = Endpoint.builder().serviceName("local").ipv4(127 << 24 | 1).port(100).build();
  InMemoryStorage storage = new InMemoryStorage();

  Tracer tracer = tracerBuilder(Sampler.ALWAYS_SAMPLE).build();
  Connection connection;

  @Before public void init() throws SQLException {
    StringBuilder url = new StringBuilder("jdbc:mysql://");
    url.append(envOr("MYSQL_HOST", "localhost"));
    url.append(":").append(envOr("MYSQL_TCP_PORT", 3306));
    String db = envOr("MYSQL_DB", null);
    if (db != null) url.append("/").append(db);
    url.append("?statementInterceptors=").append(BraveTracingStatementInterceptor.class.getName());

    MysqlDataSource dataSource = new MysqlDataSource();
    dataSource.setUrl(url.toString());

    dataSource.setUser(System.getenv("MYSQL_USER"));
    assumeTrue("Minimally, the environment variable MYSQL_USER must be set",
        dataSource.getUser() != null);
    dataSource.setPassword(envOr("MYSQL_PASS", ""));
    connection = dataSource.getConnection();
    storage.clear(); // clear any spans for test statements
  }

  @After public void close() throws SQLException {
    Tracer.current().close();
    if (connection != null) connection.close();
  }

  @Test
  public void makesChildOfCurrentSpan() throws Exception {
    brave.Span parent = tracer.newTrace().name("test").start();
    try (Tracer.SpanInScope ws = tracer.withSpanInScope(parent)) {
      prepareExecuteSelect(QUERY);
    } finally {
      parent.finish();
    }

    assertThat(collectedSpans())
        .hasSize(2);
  }

  @Test
  public void reportsClientAnnotationsToZipkin() throws Exception {
    prepareExecuteSelect(QUERY);

    assertThat(collectedSpans())
        .flatExtracting(s -> s.annotations)
        .extracting(a -> a.value)
        .containsExactly("cs", "cr");
  }

  @Test
  public void defaultSpanNameIsOperationName() throws Exception {
    prepareExecuteSelect(QUERY);

    assertThat(collectedSpans())
        .extracting(s -> s.name)
        .containsExactly("select");
  }

  @Test
  public void addsQueryTag() throws Exception {
    prepareExecuteSelect(QUERY);

    assertThat(collectedSpans())
        .flatExtracting(s -> s.binaryAnnotations)
        .filteredOn(a -> a.key.equals(TraceKeys.SQL_QUERY))
        .extracting(a -> new String(a.value, Util.UTF_8))
        .containsExactly(QUERY);
  }

  @Test
  public void reportsServerAddress() throws Exception {
    prepareExecuteSelect(QUERY);

    assertThat(collectedSpans())
        .flatExtracting(s -> s.binaryAnnotations)
        .extracting(b -> b.key)
        .contains(Constants.SERVER_ADDR);
  }

  void prepareExecuteSelect(String query) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(query)) {
      try (ResultSet resultSet = ps.executeQuery()) {
        while (resultSet.next()) {
          resultSet.getString(1);
        }
      }
    }
  }

  Tracer.Builder tracerBuilder(Sampler sampler) {
    return Tracer.newBuilder()
        .reporter(s -> storage.spanConsumer().accept(asList(s)))
        .currentTraceContext(new StrictCurrentTraceContext())
        .localEndpoint(Endpoint.builder()
            .ipv4(local.ipv4)
            .ipv6(local.ipv6)
            .port(local.port)
            .serviceName(local.serviceName)
            .build())
        .sampler(sampler);
  }

  List<Span> collectedSpans() {
    List<List<Span>> result = storage.spanStore().getRawTraces();
    assertThat(result).hasSize(1);
    return result.get(0);
  }
}
