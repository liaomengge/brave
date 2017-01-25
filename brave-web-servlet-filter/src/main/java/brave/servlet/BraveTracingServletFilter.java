package brave.servlet;

import brave.ServerHandler;
import brave.Span;
import brave.Tracer;
import brave.parser.Parser;
import brave.parser.TagsParser;
import brave.propagation.Propagation;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import zipkin.TraceKeys;

import static com.github.kristofa.brave.internal.Util.checkNotNull;

public final class BraveTracingServletFilter implements Filter {

  /** Creates a tracing filter with defaults. Use {@link #builder(Tracer)} to customize. */
  public static BraveTracingServletFilter create(Tracer tracer) {
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

    public BraveTracingServletFilter build() {
      return new BraveTracingServletFilter(this);
    }
  }

  // Not final so it can be overridden to customize tags
  public static class Config extends ServerHandler.Config<HttpServletRequest, HttpServletResponse> {

    @Override protected Parser<HttpServletRequest, String> spanNameParser() {
      return HttpServletRequest::getMethod;
    }

    @Override protected Parser<HttpServletRequest, zipkin.Endpoint> requestAddressParser() {
      return new ClientAddressParser("");
    }

    @Override protected TagsParser<HttpServletRequest> requestTagsParser() {
      return (req, span) -> {
        StringBuffer url = req.getRequestURL();
        if (req.getQueryString() != null && !req.getQueryString().isEmpty()) {
          url.append('?').append(req.getQueryString());
        }
        span.tag(TraceKeys.HTTP_URL, url.toString());
      };
    }

    @Override protected TagsParser<HttpServletResponse> responseTagsParser() {
      return (res, span) -> {
        int httpStatus = res.getStatus();
        if (httpStatus < 200 || httpStatus > 299) {
          span.tag(TraceKeys.HTTP_STATUS_CODE, String.valueOf(httpStatus));
        }
      };
    }
  }

  final Tracer tracer;
  final ServerHandler<HttpServletRequest, HttpServletResponse> serverHandler;
  final TraceContext.Extractor<HttpServletRequest> contextExtractor;

  BraveTracingServletFilter(Builder builder) {
    tracer = builder.tracer;
    serverHandler = ServerHandler.create(builder.config);
    contextExtractor = Propagation.B3_STRING.extractor(HttpServletRequest::getHeader);
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain)
      throws IOException, ServletException {

    String alreadyFilteredAttributeName = getAlreadyFilteredAttributeName();
    boolean hasAlreadyFilteredAttribute =
        request.getAttribute(alreadyFilteredAttributeName) != null;

    if (hasAlreadyFilteredAttribute) {
      // Proceed without invoking this filter...
      filterChain.doFilter(request, response);
      return;
    }

    HttpServletRequest httpRequest = (HttpServletRequest) request;
    TraceContextOrSamplingFlags contextOrFlags = contextExtractor.extract(httpRequest);
    Span span = contextOrFlags.context() != null
        ? tracer.joinSpan(contextOrFlags.context())
        : tracer.newTrace(contextOrFlags.samplingFlags());
    try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
      serverHandler.handleReceive(httpRequest, span);
      filterChain.doFilter(request, response);
      serverHandler.handleSend((HttpServletResponse) response, span);
    } catch (IOException e) { // catch repeated because handleError cannot implement multi-catch
      throw serverHandler.handleError(e, span);
    } catch (ServletException e) {
      throw serverHandler.handleError(e, span);
    } catch (RuntimeException e) {
      throw serverHandler.handleError(e, span);
    }
  }

  @Override public void destroy() {
  }

  // TODO: see if the below stuff from the old filter is pulling its weight
  FilterConfig filterConfig;

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    this.filterConfig = filterConfig;
  }

  private String getAlreadyFilteredAttributeName() {
    String name = getFilterName();
    if (name == null) {
      name = getClass().getName();
    }
    return name + ".FILTERED";
  }

  private String getFilterName() {
    return (this.filterConfig != null ? this.filterConfig.getFilterName() : null);
  }
}