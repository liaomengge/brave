package brave.jersey2;

import brave.http.ITHttpClient;
import brave.jaxrs2.BraveTracingClientFilter;
import brave.jaxrs2.BraveTracingFeature;
import brave.parser.Parser;
import java.io.IOException;
import java.util.function.Supplier;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ITBraveTracingFeature_Client extends ITHttpClient<Client> {

  @Override protected Client newClient(int port) {
    return configureClient(BraveTracingFeature.create(tracer));
  }

  Client configureClient(BraveTracingFeature feature) {
    ClientConfig clientConfig = new ClientConfig();
    clientConfig.register(feature);
    clientConfig.property(ClientProperties.CONNECT_TIMEOUT, 1000);
    clientConfig.property(ClientProperties.READ_TIMEOUT, 1000);
    return ClientBuilder.newClient(clientConfig);
  }

  @Override protected Client newClient(int port, Supplier<String> spanNamer) {
    return configureClient(BraveTracingFeature.builder(tracer)
        .clientConfig(new BraveTracingClientFilter.Config() {
          @Override protected Parser<ClientRequestContext, String> spanNameParser() {
            return ctx -> spanNamer.get();
          }
        }).build());
  }

  @Override protected void closeClient(Client client) throws IOException {
    client.close();
  }

  @Override protected void get(Client client, String pathIncludingQuery) throws IOException {
    client.target(server.url(pathIncludingQuery).uri()).request().buildGet().invoke().close();
  }

  @Override protected void getAsync(Client client, String pathIncludingQuery) throws Exception {
    client.target(server.url(pathIncludingQuery).uri()).request().async().get();
  }

  @Test
  public void currentSpanVisibleToUserFilters() throws Exception {
    server.enqueue(new MockResponse());
    closeClient(client);

    ClientConfig clientConfig = new ClientConfig();
    clientConfig.register(BraveTracingFeature.create(tracer));
    clientConfig.register((ClientRequestFilter) requestContext ->
        requestContext.getHeaders().putSingle("my-id", tracer.currentSpan().context().traceIdString())
    );
    client = ClientBuilder.newClient(clientConfig);

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

  @Override
  @Test(expected = AssertionError.class) // #289 attach local span to jersey threadpool
  public void usesParentFromInvocationTime() throws Exception {
    super.usesParentFromInvocationTime();
  }
}
