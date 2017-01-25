package brave.spring.servlet;

import brave.ServerHandler;
import brave.Span;
import brave.Tracer;
import brave.Tracer.SpanInScope;
import brave.propagation.Propagation;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.servlet.BraveTracingServletFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import static com.github.kristofa.brave.internal.Util.checkNotNull;

@Configuration
public class BraveTracingHandlerInterceptor
    extends HandlerInterceptorAdapter { // not final because of @Configuration

  /** Creates a tracing filter with defaults. Use {@link #builder(Tracer)} to customize. */
  public static BraveTracingHandlerInterceptor create(Tracer tracer) {
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

    public BraveTracingHandlerInterceptor build() {
      return new BraveTracingHandlerInterceptor(this);
    }
  }

  // Not final so it can be overridden to customize tags
  public static class Config extends BraveTracingServletFilter.Config {
  }

  final Tracer tracer;
  final ServerHandler<HttpServletRequest, HttpServletResponse> serverHandler;
  final TraceContext.Extractor<HttpServletRequest> contextExtractor;

  @Autowired // internal
  BraveTracingHandlerInterceptor(Tracer tracer, Config config) {
    this(builder(tracer).config(config));
  }

  BraveTracingHandlerInterceptor(Builder builder) {
    tracer = builder.tracer;
    serverHandler = ServerHandler.create(builder.config);
    contextExtractor = Propagation.B3_STRING.extractor(HttpServletRequest::getHeader);
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
      Object handler) {
    if (request.getAttribute(Span.class.getName()) != null) {
      return true; // already handled (possibly due to async request)
    }

    TraceContextOrSamplingFlags contextOrFlags = contextExtractor.extract(request);
    Span span = contextOrFlags.context() != null
        ? tracer.joinSpan(contextOrFlags.context())
        : tracer.newTrace(contextOrFlags.samplingFlags());
    try {
      serverHandler.handleReceive(request, span);
      request.setAttribute(Span.class.getName(), span);
      request.setAttribute(SpanInScope.class.getName(), tracer.withSpanInScope(span));
    } catch (RuntimeException e) {
      throw serverHandler.handleError(e, span);
    }
    return true;
  }

  @Override
  public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
      Object handler, Exception ex) {
    Span span = (Span) request.getAttribute(Span.class.getName());
    ((SpanInScope) request.getAttribute(SpanInScope.class.getName())).close();
    if (ex != null) {
      serverHandler.handleError(ex, span);
    } else {
      serverHandler.handleSend(response, span);
    }
  }
}