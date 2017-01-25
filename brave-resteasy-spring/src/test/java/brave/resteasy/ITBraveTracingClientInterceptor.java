package brave.resteasy;

import brave.http.ITHttpClient;
import brave.parser.Parser;
import java.io.IOException;
import java.util.function.Supplier;
import javax.ws.rs.core.UriBuilder;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.HttpClients;
import org.jboss.resteasy.client.ClientRequest;
import org.jboss.resteasy.client.ClientRequestFactory;
import org.jboss.resteasy.client.core.executors.ApacheHttpClient4Executor;
import org.junit.AssumptionViolatedException;

public class ITBraveTracingClientInterceptor extends ITHttpClient<ClientRequestFactory> {

  @Override protected ClientRequestFactory newClient(int port) {
    return configureClient(port, BraveTracingClientInterceptor.create(tracer));
  }

  ClientRequestFactory configureClient(int port, BraveTracingClientInterceptor interceptor) {
    HttpClient httpClient = HttpClients.custom().disableAutomaticRetries().build();

    ApacheHttpClient4Executor clientExecutor = new ApacheHttpClient4Executor(httpClient);
    ClientRequestFactory crf = new ClientRequestFactory(clientExecutor,
        UriBuilder.fromUri("http://localhost:" + port).build());

    crf.getPrefixInterceptors().getExecutionInterceptorList().add(interceptor);
    return crf;
  }

  @Override
  protected ClientRequestFactory newClient(int port, Supplier<String> spanName) {
    return configureClient(port, BraveTracingClientInterceptor.builder(tracer)
        .config(new BraveTracingClientInterceptor.Config() {
          @Override protected Parser<ClientRequest, String> spanNameParser() {
            return c -> spanName.get();
          }
        }).build());
  }

  @Override protected void closeClient(ClientRequestFactory client) throws IOException {
    // noop
  }

  @Override protected void get(ClientRequestFactory client, String pathIncludingQuery)
      throws Exception {
    client.createRelativeRequest(pathIncludingQuery).get().releaseConnection();
  }

  @Override protected void getAsync(ClientRequestFactory client, String pathIncludingQuery) {
    throw new AssumptionViolatedException("TODO: how does resteasy 1.x do async?");
  }
}
