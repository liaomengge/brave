package brave.grpc;

import brave.Tracer;
import brave.grpc.BraveTracingClientInterceptor.Config;
import brave.parser.Parser;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.sampler.Sampler;
import com.github.kristofa.brave.internal.Util;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GraterGrpc;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import zipkin.Constants;
import zipkin.Endpoint;
import zipkin.Span;
import zipkin.storage.InMemoryStorage;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;

public class ITBraveTracingClientInterceptor {
  static final HelloRequest HELLO_REQUEST = HelloRequest.newBuilder()
      .setName("tracer")
      .build();

  Endpoint local = Endpoint.builder().serviceName("local").ipv4(127 << 24 | 1).port(100).build();
  InMemoryStorage storage = new InMemoryStorage();

  Tracer tracer;
  TestServer server = new TestServer();
  ManagedChannel client;

  @Before
  public void setup() throws IOException {
    server.start();
    tracer = tracerBuilder(Sampler.ALWAYS_SAMPLE).build();
    client = newClient();
  }

  @After
  public void close() throws Exception {
    closeClient(client);
    server.stop();
  }

  ManagedChannel newClient() {
    return newClient(BraveTracingClientInterceptor.create(tracer));
  }

  ManagedChannel newClient(ClientInterceptor... clientInterceptors) {
    return ManagedChannelBuilder.forAddress("localhost", server.port())
        .intercept(clientInterceptors)
        .usePlaintext(true)
        .build();
  }

  void closeClient(ManagedChannel client) throws Exception {
    client.shutdown();
    client.awaitTermination(1, TimeUnit.SECONDS);
  }

  @Test
  public void propagatesSpan() throws Exception {
    GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);

    TraceContext context = server.takeRequest().context();
    assertThat(context.parentId()).isNull();
    assertThat(context.sampled()).isTrue();
  }

  @Test
  public void makesChildOfCurrentSpan() throws Exception {
    brave.Span parent = tracer.newTrace().name("test").start();
    try (Tracer.SpanInScope ws = tracer.withSpanInScope(parent)) {
      GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);
    } finally {
      parent.finish();
    }

    TraceContext context = server.takeRequest().context();
    assertThat(context.traceId())
        .isEqualTo(parent.context().traceId());
    assertThat(context.parentId())
        .isEqualTo(parent.context().spanId());
  }

  /**
   * This tests that the parent is determined at the time the request was made, not when the request
   * was executed.
   */
  @Test
  public void usesParentFromInvocationTime() throws Exception {
    server.enqueueDelay(TimeUnit.SECONDS.toMillis(1));
    GreeterGrpc.GreeterFutureStub futureStub = GreeterGrpc.newFutureStub(client);

    brave.Span parent = tracer.newTrace().name("test").start();
    try (Tracer.SpanInScope ws = tracer.withSpanInScope(parent)) {
      futureStub.sayHello(HELLO_REQUEST);
      futureStub.sayHello(HELLO_REQUEST);
    } finally {
      parent.finish();
    }

    brave.Span otherSpan = tracer.newTrace().name("test2").start();
    try (Tracer.SpanInScope ws = tracer.withSpanInScope(otherSpan)) {
      for (int i = 0; i < 2; i++) {
        TraceContext context = server.takeRequest().context();
        assertThat(context.traceId())
            .isEqualTo(parent.context().traceId());
        assertThat(context.parentId())
            .isEqualTo(parent.context().spanId());
      }
    } finally {
      otherSpan.finish();
    }
  }

  /** Unlike Brave 3, Brave 4 propagates trace ids even when unsampled */
  @Test
  public void propagates_sampledFalse() throws Exception {
    tracer = tracerBuilder(Sampler.NEVER_SAMPLE).build();
    closeClient(client);
    client = newClient();

    GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);

    TraceContextOrSamplingFlags context = server.takeRequest();
    assertThat(context.context().sampled()).isFalse();
  }

  @Test
  public void reportsClientAnnotationsToZipkin() throws Exception {
    GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);

    assertThat(collectedSpans())
        .flatExtracting(s -> s.annotations)
        .extracting(a -> a.value)
        .containsExactly("cs", "cr");
  }

  @Test
  public void defaultSpanNameIsMethodName() throws Exception {
    GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);

    assertThat(collectedSpans())
        .extracting(s -> s.name)
        .containsExactly("helloworld.greeter/sayhello");
  }

  @Test
  public void supportsSpanNameProvider() throws Exception {
    closeClient(client);
    client = newClient(BraveTracingClientInterceptor.builder(tracer)
        .config(new Config() {
          @Override protected Parser<MethodDescriptor, String> spanNameParser() {
            return desc -> "hello there";
          }
        }).build());

    GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);

    assertThat(collectedSpans())
        .extracting(s -> s.name)
        .containsExactly("hello there");
  }

  @Test
  public void reportsSpanOnTransportException() throws Exception {
    server.stop();

    try {
      GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);
      failBecauseExceptionWasNotThrown(StatusRuntimeException.class);
    } catch (StatusRuntimeException e) {
    }

    assertThat(collectedSpans()).hasSize(1);
  }

  @Test
  public void addsErrorTag_onUnimplemented() throws Exception {

    try {
      GraterGrpc.newBlockingStub(client).seyHallo(HELLO_REQUEST);
      failBecauseExceptionWasNotThrown(StatusRuntimeException.class);
    } catch (StatusRuntimeException e) {
    }

    assertThat(collectedSpans())
        .flatExtracting(s -> s.binaryAnnotations)
        .filteredOn(a -> a.key.equals(Constants.ERROR))
        .extracting(a -> new String(a.value, Util.UTF_8))
        .containsExactly("UNIMPLEMENTED");
  }

  @Test
  public void addsErrorTag_onTransportException() throws Exception {
    reportsSpanOnTransportException();

    assertThat(collectedSpans())
        .flatExtracting(s -> s.binaryAnnotations)
        .filteredOn(a -> a.key.equals(Constants.ERROR))
        .extracting(a -> new String(a.value, Util.UTF_8))
        .containsExactly("UNAVAILABLE");
  }

  @Test
  public void addsErrorTag_onCanceledFuture() throws Exception {
    server.enqueueDelay(TimeUnit.SECONDS.toMillis(1));

    GreeterGrpc.newFutureStub(client).sayHello(HELLO_REQUEST).cancel(true);

    close(); // blocks until the cancel finished

    assertThat(collectedSpans())
        .flatExtracting(s -> s.binaryAnnotations)
        .filteredOn(a -> a.key.equals(Constants.ERROR))
        .extracting(a -> new String(a.value, Util.UTF_8))
        .containsExactly("CANCELLED");
  }

  /**
   * NOTE: for this to work, the brave interceptor must be last (so that it executes first)
   *
   * <p>Also notice that we are only making the current context available in the request side. The
   */
  @Test public void currentSpanVisibleToUserInterceptors() throws Exception {
    Map<String, String> scopes = new ConcurrentHashMap<>();
    closeClient(client);

    client = newClient(
        new ClientInterceptor() {
          @Override public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
              MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            scopes.put("before", tracer.currentSpan().context().traceIdString());
            return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                next.newCall(method, callOptions)) {
              @Override
              public void start(Listener<RespT> responseListener, Metadata headers) {
                scopes.put("start", tracer.currentSpan().context().traceIdString());
                super.start(responseListener, headers);
              }
            };
          }
        },
        BraveTracingClientInterceptor.create(tracer)
    );

    GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);

    assertThat(scopes)
        .containsKeys("before", "start");
  }

  Tracer.Builder tracerBuilder(Sampler sampler) {
    return Tracer.newBuilder()
        .reporter(s -> storage.spanConsumer().accept(asList(s)))
        // TODO: monkey with grpc executors .currentTraceContext(new StrictCurrentTraceContext())
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
