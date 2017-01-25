package brave.resteasy3;

import brave.http.ITHttpClient;
import brave.jaxrs2.BraveTracingClientFilter;
import brave.jaxrs2.BraveTracingFeature;
import brave.parser.Parser;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.client.InvocationCallback;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ITBraveTracingFeature_Client extends ITHttpClient<ResteasyClient> {

  ExecutorService executor = Executors.newSingleThreadExecutor();

  @Override protected ResteasyClient newClient(int port) {
    return configureClient(BraveTracingFeature.create(tracer));
  }

  ResteasyClient configureClient(BraveTracingFeature feature) {
    return new ResteasyClientBuilder()
        .socketTimeout(1, TimeUnit.SECONDS)
        .establishConnectionTimeout(1, TimeUnit.SECONDS)
        .asyncExecutor(currentTraceContext.executorService(executor))
        .register(feature)
        .build();
  }

  @Override protected ResteasyClient newClient(int port, Supplier<String> spanNamer) {
    return configureClient(BraveTracingFeature.builder(tracer)
        .clientConfig(new BraveTracingClientFilter.Config() {
          @Override protected Parser<ClientRequestContext, String> spanNameParser() {
            return ctx -> spanNamer.get();
          }
        }).build());
  }

  @Override protected void closeClient(ResteasyClient client) throws IOException {
    if (client != null) client.close();
    executor.shutdownNow();
  }

  @Override protected void get(ResteasyClient client, String pathIncludingQuery)
      throws IOException {
    client.target(server.url(pathIncludingQuery).uri()).request().buildGet().invoke().close();
  }

  @Override protected void getAsync(ResteasyClient client, String pathIncludingQuery) {
    client.target(server.url(pathIncludingQuery).uri()).request().async().get(
        new InvocationCallback<Void>() {
          @Override public void completed(Void o) {
          }

          @Override public void failed(Throwable throwable) {
            throwable.printStackTrace();
          }
        });
  }

  @Test
  public void currentSpanVisibleToUserFilters() throws Exception {
    server.enqueue(new MockResponse());
    closeClient(client);

    client = new ResteasyClientBuilder()
        .register(BraveTracingFeature.create(tracer))
        .register((ClientRequestFilter) requestContext -> requestContext.getHeaders()
            .putSingle("my-id", tracer.currentSpan().context().traceIdString())
        )
        .build();

    get(client, "/foo");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getHeader("x-b3-traceId"))
        .isEqualTo(request.getHeader("my-id"));
  }

  @Override
  @Test(expected = AssertionError.class) // doesn't yet close a span on exception
  public void reportsSpanOnTransportException() throws Exception {
    super.reportsSpanOnTransportException();
  }

  @Override
  @Test(expected = AssertionError.class) // doesn't yet close a span on exception
  public void addsErrorTagOnTransportException() throws Exception {
    super.addsErrorTagOnTransportException();
  }
}
