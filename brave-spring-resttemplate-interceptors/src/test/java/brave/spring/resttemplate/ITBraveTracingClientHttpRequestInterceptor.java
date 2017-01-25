package brave.spring.resttemplate;

import brave.http.ITHttpClient;
import brave.parser.Parser;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.Supplier;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.AssumptionViolatedException;
import org.junit.Test;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.OkHttp3ClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

public class ITBraveTracingClientHttpRequestInterceptor
    extends ITHttpClient<OkHttp3ClientHttpRequestFactory> {

  BraveTracingClientHttpRequestInterceptor interceptor;

  @Override protected OkHttp3ClientHttpRequestFactory newClient(int port) {
    return configureClient(BraveTracingClientHttpRequestInterceptor.create(tracer));
  }

  OkHttp3ClientHttpRequestFactory configureClient(
      BraveTracingClientHttpRequestInterceptor interceptor) {
    OkHttp3ClientHttpRequestFactory factory = new OkHttp3ClientHttpRequestFactory();
    factory.setReadTimeout(1000);
    factory.setConnectTimeout(1000);
    this.interceptor = interceptor;
    return factory;
  }

  @Override
  protected OkHttp3ClientHttpRequestFactory newClient(int port, Supplier<String> spanNamer) {
    return configureClient(BraveTracingClientHttpRequestInterceptor.builder(tracer)
        .config(new BraveTracingClientHttpRequestInterceptor.Config() {
          @Override protected Parser<HttpRequest, String> spanNameParser() {
            return ctx -> spanNamer.get();
          }
        }).build());
  }

  @Override protected void closeClient(OkHttp3ClientHttpRequestFactory client) throws IOException {
    client.destroy();
  }

  @Override protected void get(OkHttp3ClientHttpRequestFactory client, String pathIncludingQuery)
      throws Exception {
    RestTemplate restTemplate = new RestTemplate(client);
    restTemplate.setInterceptors(Collections.singletonList(interceptor));
    restTemplate.getForObject(server.url(pathIncludingQuery).toString(), String.class);
  }

  @Override
  protected void getAsync(OkHttp3ClientHttpRequestFactory client, String pathIncludingQuery) {
    throw new AssumptionViolatedException("TODO: async rest template has its own interceptor");
  }

  @Test
  public void currentSpanVisibleToUserInterceptors() throws Exception {
    server.enqueue(new MockResponse());

    RestTemplate restTemplate = new RestTemplate(client);
    restTemplate.setInterceptors(Arrays.asList(interceptor, (request, body, execution) -> {
      request.getHeaders().add("my-id", tracer.currentSpan().context().traceIdString());
      return execution.execute(request, body);
    }));
    restTemplate.getForObject(server.url("/foo").toString(), String.class);

    RecordedRequest request = server.takeRequest();
    assertThat(request.getHeader("x-b3-traceId"))
        .isEqualTo(request.getHeader("my-id"));
  }
}
