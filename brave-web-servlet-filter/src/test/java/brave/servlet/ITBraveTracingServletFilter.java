package brave.servlet;

import brave.Tracer;
import brave.http.ITServletContainer;
import brave.parser.Parser;
import java.io.IOException;
import java.util.EnumSet;
import java.util.function.Supplier;
import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.AssumptionViolatedException;
import org.junit.Test;

public class ITBraveTracingServletFilter extends ITServletContainer {

  @Override @Test public void addsStatusCode_notFound() throws Exception {
    throw new AssumptionViolatedException("404 is handled upstream to the servlet filter");
  }

  @Override @Test public void addsErrorTagOnTransportException_async() throws Exception {
    throw new AssumptionViolatedException("TODO: implement async filtering");
  }

  @Override @Test public void reportsSpanOnTransportException_async() throws Exception {
    throw new AssumptionViolatedException("TODO: implement async filtering");
  }

  static class FooServlet extends HttpServlet {
    @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
      resp.setStatus(200);
    }
  }

  static class ChildServlet extends HttpServlet {
    final Tracer tracer;

    ChildServlet(Tracer tracer) {
      this.tracer = tracer;
    }

    @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
      tracer.nextSpan().name("child").start().finish();
      resp.setStatus(200);
    }
  }

  static class AsyncServlet extends HttpServlet {
    @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
      AsyncContext ctx = req.startAsync();
      ctx.start(ctx::complete);
    }
  }

  static class DisconnectServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      throw new IOException(); // null exception message!
    }
  }

  static class DisconnectAsyncServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      AsyncContext ctx = req.startAsync();
      ctx.start(() -> {
        try {
          // TODO: see if there's another way to abend an async servlet
          ((HttpServletResponse) ctx.getResponse()).sendError(500);
        } catch (IOException e) {
        }
        ctx.complete();
      });
    }
  }

  @Override
  public void init(ServletContextHandler handler, Tracer tracer, Supplier<String> spanName) {
    // add servlets for the test resource
    handler.addServlet(new ServletHolder(new FooServlet()), "/foo");
    handler.addServlet(new ServletHolder(new ChildServlet(tracer)), "/child");
    handler.addServlet(new ServletHolder(new AsyncServlet()), "/async");
    handler.addServlet(new ServletHolder(new DisconnectServlet()), "/disconnect");
    handler.addServlet(new ServletHolder(new DisconnectAsyncServlet()), "/disconnectAsync");

    // add the trace filter
    BraveTracingServletFilter filter = BraveTracingServletFilter.builder(tracer)
        .config(new BraveTracingServletFilter.Config() {
          @Override protected Parser<HttpServletRequest, String> spanNameParser() {
            return spanName != null ? req -> spanName.get() : super.spanNameParser();
          }
        })
        .build();
    handler.addFilter(new FilterHolder(filter), "/*", EnumSet.of(DispatcherType.REQUEST));
  }
}
