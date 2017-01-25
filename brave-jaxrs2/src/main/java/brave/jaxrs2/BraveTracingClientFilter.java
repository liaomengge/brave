package brave.jaxrs2;

import brave.ClientHandler;
import brave.Span;
import brave.Tracer;
import brave.Tracer.SpanInScope;
import brave.parser.Parser;
import brave.parser.TagsParser;
import brave.propagation.Propagation;
import brave.propagation.TraceContext;
import javax.annotation.Priority;
import javax.ws.rs.ConstrainedTo;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.client.ClientResponseContext;
import javax.ws.rs.client.ClientResponseFilter;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.Provider;
import zipkin.TraceKeys;

import static com.github.kristofa.brave.internal.Util.checkNotNull;
import static javax.ws.rs.RuntimeType.CLIENT;

/**
 * This filter is set at highest priority which means it executes before other filters. The impact
 * is that other filters can see the span created here via {@link Tracer#currentSpan()}. Another
 * impact is that the span will not see modifications to the request made by downstream filters.
 */
// If tags for the request are added on response, they might include changes made by other filters..
// However, the response callback isn't invoked on error, so this choice could be worse.
@Provider
@ConstrainedTo(CLIENT)
@Priority(0) // to make the span in scope visible to other filters
public class BraveTracingClientFilter implements ClientRequestFilter, ClientResponseFilter {

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

  // Not final so it can be overridden to customize tags
  public static class Config
      extends ClientHandler.Config<ClientRequestContext, ClientResponseContext> {

    @Override protected Parser<ClientRequestContext, String> spanNameParser() {
      return ClientRequestContext::getMethod;
    }

    @Override protected TagsParser<ClientRequestContext> requestTagsParser() {
      return (req, span) -> span.tag(TraceKeys.HTTP_URL, req.getUri().toString());
    }

    @Override protected TagsParser<ClientResponseContext> responseTagsParser() {
      return (res, span) -> {
        int httpStatus = res.getStatus();
        if (httpStatus < 200 || httpStatus > 299) {
          span.tag(TraceKeys.HTTP_STATUS_CODE, String.valueOf(httpStatus));
        }
      };
    }
  }

  final Tracer tracer;
  final ClientHandler<ClientRequestContext, ClientResponseContext> clientHandler;
  final TraceContext.Injector<MultivaluedMap> injector;

  BraveTracingClientFilter(Builder builder) {
    tracer = builder.tracer;
    clientHandler = ClientHandler.create(builder.config);
    injector = Propagation.B3_STRING.injector(MultivaluedMap::putSingle);
  }

  @Override
  public void filter(ClientRequestContext request) {
    Span span = tracer.nextSpan();
    clientHandler.handleSend(request, span);
    injector.inject(span.context(), request.getHeaders());
    request.setProperty(Span.class.getName(), span);
    request.setProperty(SpanInScope.class.getName(), tracer.withSpanInScope(span));
  }

  @Override
  public void filter(ClientRequestContext request, ClientResponseContext response) {
    Span span = (Span) request.getProperty(Span.class.getName());
    SpanInScope spanInScope = (SpanInScope) request.getProperty(SpanInScope.class.getName());
    clientHandler.handleReceive(response, span);
    spanInScope.close();
  }
}