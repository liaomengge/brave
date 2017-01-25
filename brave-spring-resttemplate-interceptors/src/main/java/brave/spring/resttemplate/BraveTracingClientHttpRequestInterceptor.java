package brave.spring.resttemplate;

import brave.ClientHandler;
import brave.Span;
import brave.Tracer;
import brave.parser.Parser;
import brave.parser.TagsParser;
import brave.propagation.Propagation;
import brave.propagation.TraceContext;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import zipkin.TraceKeys;

import static com.github.kristofa.brave.internal.Util.checkNotNull;

public final class BraveTracingClientHttpRequestInterceptor
    implements ClientHttpRequestInterceptor {

  /** Creates trace interceptor with defaults. Use {@link #builder(Tracer)} to customize. */
  public static BraveTracingClientHttpRequestInterceptor create(Tracer tracer) {
    return builder(tracer).build();
  }

  public static Builder builder(Tracer tracer) {
    return new Builder(tracer);
  }

  public static final class Builder {
    final Tracer tracer;
    Config config = new Config();

    Builder(Tracer tracer) { // intentionally hidden
      this.tracer = checkNotNull(tracer, "tracer");
    }

    public Builder config(Config config) {
      this.config = checkNotNull(config, "config");
      return this;
    }

    public BraveTracingClientHttpRequestInterceptor build() {
      return new BraveTracingClientHttpRequestInterceptor(this);
    }
  }

  public static class Config extends ClientHandler.Config<HttpRequest, ClientHttpResponse> {

    @Override protected Parser<HttpRequest, String> spanNameParser() {
      return r -> r.getMethod().name();
    }

    @Override protected TagsParser<HttpRequest> requestTagsParser() {
      return (req, span) -> span.tag(TraceKeys.HTTP_URL, req.getURI().toString());
    }

    @Override protected TagsParser<ClientHttpResponse> responseTagsParser() {
      return (res, span) -> {
        try {
          int httpStatus = res.getRawStatusCode();
          if (httpStatus < 200 || httpStatus > 299) {
            span.tag(TraceKeys.HTTP_STATUS_CODE, String.valueOf(httpStatus));
          }
        } catch (IOException e) {
          // don't log a fake value on exception
        }
      };
    }
  }

  final Tracer tracer;
  final ClientHandler<HttpRequest, ClientHttpResponse> clientHandler;
  final TraceContext.Injector<HttpHeaders> injector;

  BraveTracingClientHttpRequestInterceptor(Builder builder) {
    tracer = builder.tracer;
    clientHandler = ClientHandler.create(builder.config);
    injector = Propagation.B3_STRING.injector(HttpHeaders::set);
  }

  @Override
  public ClientHttpResponse intercept(HttpRequest request, byte[] body,
      ClientHttpRequestExecution execution) throws IOException {
    Span span = tracer.nextSpan();
    clientHandler.handleSend(request, span);
    injector.inject(span.context(), request.getHeaders());
    try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
      return clientHandler.handleReceive(execution.execute(request, body), span);
    } catch (RuntimeException e) {
      throw clientHandler.handleError(e, span);
    } catch (IOException e) {
      throw clientHandler.handleError(e, span);
    }
  }
}