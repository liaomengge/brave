package brave.p6spy;

import brave.Tracer;
import brave.internal.StrictCurrentTraceContext;
import brave.sampler.Sampler;
import com.github.kristofa.brave.internal.Util;
import com.github.kristofa.brave.p6spy.DerbyUtils;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import zipkin.Endpoint;
import zipkin.Span;
import zipkin.TraceKeys;
import zipkin.storage.InMemoryStorage;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

public class ITBraveTracing {
  static final String URL = "jdbc:p6spy:derby:memory:p6spy;create=true";
  static final String QUERY = "SELECT 1 FROM SYSIBM.SYSDUMMY1";

  //Get rid of annoying derby.log
  static {
    DerbyUtils.disableLog();
  }

  Endpoint local = Endpoint.builder().serviceName("local").ipv4(127 << 24 | 1).port(100).build();
  InMemoryStorage storage = new InMemoryStorage();

  Tracer tracer = tracerBuilder(Sampler.ALWAYS_SAMPLE).build();
  Connection connection;

  @Before
  public void setup() throws Exception {
    DriverManager.getDriver(URL);
    connection = DriverManager.getConnection(URL, "foo", "bar");
  }

  @After
  public void close() throws Exception {
    Tracer.current().close();
    connection.close();
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
