package brave.resteasy;

import brave.ClientHandler;
import brave.Span;
import brave.Tracer;
import brave.parser.Parser;
import brave.parser.TagsParser;
import brave.propagation.Propagation;
import brave.propagation.TraceContext;
import javax.ws.rs.ext.Provider;
import org.jboss.resteasy.annotations.interception.ClientInterceptor;
import org.jboss.resteasy.client.ClientRequest;
import org.jboss.resteasy.client.ClientResponse;
import org.jboss.resteasy.spi.interception.ClientExecutionContext;
import org.jboss.resteasy.spi.interception.ClientExecutionInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import zipkin.TraceKeys;

import static com.github.kristofa.brave.internal.Util.checkNotNull;

@Component
@Provider
@ClientInterceptor
public class BraveTracingClientInterceptor implements ClientExecutionInterceptor {

  /** Creates a tracing interceptor with defaults. Use {@link #builder(Tracer)} to customize. */
  public static BraveTracingClientInterceptor create(Tracer tracer) {
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

    public BraveTracingClientInterceptor build() {
      return new BraveTracingClientInterceptor(this);
    }
  }

  // Not final so it can be overridden to customize tags
  public static class Config extends ClientHandler.Config<ClientRequest, ClientResponse> {

    @Override protected Parser<ClientRequest, String> spanNameParser() {
      return ClientRequest::getHttpMethod;
    }

    @Override protected TagsParser<ClientRequest> requestTagsParser() {
      return (req, span) -> {
        try {
          span.tag(TraceKeys.HTTP_URL, req.getUri().toString());
        } catch (Exception e) {
        }
      };
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
  final TraceContext.Injector<ClientRequest> injector;

  @Autowired // internal
  BraveTracingClientInterceptor(Tracer tracer, Config config) {
    this(builder(tracer).config(config));
  }

  BraveTracingClientInterceptor(Builder builder) {
    tracer = builder.tracer;
    clientHandler = ClientHandler.create(builder.config);
    injector = Propagation.B3_STRING.injector(ClientRequest::header);
  }

  @Override
  public ClientResponse<?> execute(ClientExecutionContext ctx) throws Exception {
    ClientRequest request = ctx.getRequest();
    Span span = tracer.nextSpan();
    clientHandler.handleSend(request, span);
    injector.inject(span.context(), request);
    try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
      return clientHandler.handleReceive(ctx.proceed(), span);
    } catch (Exception e) {
      throw clientHandler.handleError(e, span);
    }
  }
}