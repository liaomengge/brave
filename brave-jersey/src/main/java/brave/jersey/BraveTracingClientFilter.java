package brave.jersey;

import brave.ClientHandler;
import brave.Span;
import brave.Tracer;
import brave.parser.Parser;
import brave.parser.TagsParser;
import brave.propagation.Propagation;
import brave.propagation.TraceContext;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientRequest;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.filter.ClientFilter;
import javax.inject.Singleton;
import javax.ws.rs.core.MultivaluedMap;
import zipkin.TraceKeys;

import static com.github.kristofa.brave.internal.Util.checkNotNull;

/**
 * NOTE: For other interceptors to see the {@link Tracer#currentSpan()} representing this operation,
 * this filter needs to be added last.
 */
@Singleton
public class BraveTracingClientFilter extends ClientFilter {

  /** Creates a tracing filter with defaults. Use {@link #builder(Tracer)} to customize. */
  public static BraveTracingClientFilter create(Tracer tracer) {
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

    public BraveTracingClientFilter build() {
      return new BraveTracingClientFilter(this);
    }
  }

  public static class Config extends ClientHandler.Config<ClientRequest, ClientResponse> {

    @Override protected Parser<ClientRequest, String> spanNameParser() {
      return ClientRequest::getMethod;
    }

    @Override protected TagsParser<ClientRequest> requestTagsParser() {
      return (req, span) -> span.tag(TraceKeys.HTTP_URL, req.getURI().toString());
    }

    @Override protected TagsParser<ClientResponse> responseTagsParser() {
      return (res, span) -> {
        int httpStatus = res.getStatus();
        if (httpStatus < 200 || httpStatus > 299) {
          span.tag(TraceKeys.HTTP_STATUS_CODE, String.valueOf(httpStatus));
        }
      };
    }
  }

  final Tracer tracer;
  final ClientHandler<ClientRequest, ClientResponse> clientHandler;
  final TraceContext.Injector<MultivaluedMap> injector;

  BraveTracingClientFilter(Builder builder) {
    tracer = builder.tracer;
    clientHandler = ClientHandler.create(builder.config);
    injector = Propagation.B3_STRING.injector(MultivaluedMap::putSingle);
  }

  @Override
  public ClientResponse handle(ClientRequest request) throws ClientHandlerException {
    Span span = tracer.nextSpan();
    clientHandler.handleSend(request, span);
    injector.inject(span.context(), request.getHeaders());
    try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
      return clientHandler.handleReceive(getNext().handle(request), span);
    } catch (ClientHandlerException e) {
      throw clientHandler.handleError(e, span);
    }
  }
}