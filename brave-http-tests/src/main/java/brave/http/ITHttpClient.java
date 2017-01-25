package brave.http;

import brave.Tracer;
import brave.internal.HexCodec;
import brave.internal.StrictCurrentTraceContext;
import brave.propagation.CurrentTraceContext;
import brave.sampler.Sampler;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import zipkin.BinaryAnnotation;
import zipkin.Constants;
import zipkin.Endpoint;
import zipkin.Span;
import zipkin.TraceKeys;
import zipkin.internal.Util;
import zipkin.storage.InMemoryStorage;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

public abstract class ITHttpClient<C> {
  @Rule public ExpectedException thrown = ExpectedException.none();
  @Rule public MockWebServer server = new MockWebServer();

  Endpoint local = Endpoint.builder().serviceName("local").ipv4(127 << 24 | 1).port(100).build();
  InMemoryStorage storage = new InMemoryStorage();

  protected CurrentTraceContext currentTraceContext = new StrictCurrentTraceContext();
  protected Tracer tracer;
  protected C client;

  @Before
  public void setup() {
    tracer = tracerBuilder(Sampler.ALWAYS_SAMPLE).build();
    client = newClient(server.getPort());
  }

  /**
   * Make sure the client you return has retries disabled.
   */
  protected abstract C newClient(int port);

  protected abstract C newClient(int port, Supplier<String> spanName);

  protected abstract void closeClient(C client) throws IOException;

  protected abstract void get(C client, String pathIncludingQuery) throws Exception;

  protected abstract void getAsync(C client, String pathIncludingQuery) throws Exception;

  @After
  public void close() throws IOException {
    closeClient(client);
  }

  @Test
  public void propagatesSpan() throws Exception {
    server.enqueue(new MockResponse());
    get(client, "/foo");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getHeaders().toMultimap())
        .containsKeys("x-b3-traceId", "x-b3-spanId")
        .containsEntry("x-b3-sampled", asList("1"));
  }

  @Test
  public void makesChildOfCurrentSpan() throws Exception {
    server.enqueue(new MockResponse());

    brave.Span parent = tracer.newTrace().name("test").start();
    try (Tracer.SpanInScope ws = tracer.withSpanInScope(parent)) {
      get(client, "/foo");
    } finally {
      parent.finish();
    }

    RecordedRequest request = server.takeRequest();
    assertThat(request.getHeader("x-b3-traceId"))
        .isEqualTo(parent.context().traceIdString());
    assertThat(request.getHeader("x-b3-parentspanid"))
        .isEqualTo(HexCodec.toLowerHex(parent.context().spanId()));
  }

  /**
   * This tests that the parent is determined at the time the request was made, not when the request
   * was executed.
   */
  @Test
  public void usesParentFromInvocationTime() throws Exception {
    server.enqueue(new MockResponse().setBodyDelay(1, TimeUnit.SECONDS));
    server.enqueue(new MockResponse());

    brave.Span parent = tracer.newTrace().name("test").start();
    try (Tracer.SpanInScope ws = tracer.withSpanInScope(parent)) {
      getAsync(client, "/foo");
      getAsync(client, "/foo");
    } finally {
      parent.finish();
    }

    brave.Span otherSpan = tracer.newTrace().name("test2").start();
    try (Tracer.SpanInScope ws = tracer.withSpanInScope(otherSpan)) {
      for (int i = 0; i < 2; i++) {
        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("x-b3-traceId"))
            .isEqualTo(parent.context().traceIdString());
        assertThat(request.getHeader("x-b3-parentspanid"))
            .isEqualTo(HexCodec.toLowerHex(parent.context().spanId()));
      }
    } finally {
      otherSpan.finish();
    }
  }

  /** Unlike Brave 3, Brave 4 propagates trace ids even when unsampled */
  @Test
  public void propagates_sampledFalse() throws Exception {
    tracer = tracerBuilder(Sampler.NEVER_SAMPLE).build();
    close();
    client = newClient(server.getPort());

    server.enqueue(new MockResponse());
    get(client, "/foo");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getHeaders().toMultimap())
        .containsKeys("x-b3-traceId", "x-b3-spanId")
        .doesNotContainKey("x-b3-parentSpanId")
        .containsEntry("x-b3-sampled", asList("0"));
  }

  @Test
  public void reportsClientAnnotationsToZipkin() throws Exception {
    server.enqueue(new MockResponse());
    get(client, "/foo");

    assertThat(collectedSpans())
        .flatExtracting(s -> s.annotations)
        .extracting(a -> a.value)
        .containsExactly("cs", "cr");
  }

  @Test
  public void defaultSpanNameIsMethodName() throws Exception {
    server.enqueue(new MockResponse());
    get(client, "/foo");

    assertThat(collectedSpans())
        .extracting(s -> s.name)
        .containsExactly("get");
  }

  @Test
  public void supportsSpanNameProvider() throws Exception {
    close();
    client = newClient(server.getPort(), () -> "hello there");

    server.enqueue(new MockResponse());
    get(client, "/foo");

    assertThat(collectedSpans())
        .extracting(s -> s.name)
        .containsExactly("hello there");
  }

  @Test
  public void addsStatusCodeWhenNotOk() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(404));

    try {
      get(client, "/foo");
    } catch (RuntimeException e) {
      // some clients think 404 is an error
    }

    assertThat(collectedSpans())
        .flatExtracting(s -> s.binaryAnnotations)
        .contains(BinaryAnnotation.create(TraceKeys.HTTP_STATUS_CODE, "404", local));
  }

  @Test
  public void reportsSpanOnTransportException() throws Exception {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

    try {
      get(client, "/foo");
    } catch (Exception e) {
      // ok, but the span should include an error!
    }

    assertThat(collectedSpans()).hasSize(1);
  }

  @Test
  public void addsErrorTagOnTransportException() throws Exception {
    reportsSpanOnTransportException();

    assertThat(collectedSpans())
        .flatExtracting(s -> s.binaryAnnotations)
        .extracting(b -> b.key)
        .contains(Constants.ERROR);
  }

  @Test
  public void httpUrlTagIncludesQueryParams() throws Exception {
    String path = "/foo?z=2&yAA=1";

    server.enqueue(new MockResponse());
    get(client, path);

    assertThat(collectedSpans())
        .flatExtracting(s -> s.binaryAnnotations)
        .filteredOn(b -> b.key.equals(TraceKeys.HTTP_URL))
        .extracting(b -> new String(b.value, Util.UTF_8))
        .containsExactly(server.url(path).toString());
  }

  Tracer.Builder tracerBuilder(Sampler sampler) {
    return Tracer.newBuilder()
        .reporter(s -> storage.spanConsumer().accept(asList(s)))
        .currentTraceContext(currentTraceContext)
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
