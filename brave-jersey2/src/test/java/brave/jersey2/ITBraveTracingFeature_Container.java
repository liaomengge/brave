package brave.jersey2;

import brave.Tracer;
import brave.http.ITServletContainer;
import brave.jaxrs2.BraveTracingContainerFilter;
import brave.jaxrs2.BraveTracingFeature;
import brave.parser.Parser;
import java.io.IOException;
import java.util.function.Supplier;
import javax.servlet.ServletContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.CompletionCallback;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.junit.AssumptionViolatedException;
import org.junit.Test;

public class ITBraveTracingFeature_Container extends ITServletContainer {

  @Override @Test public void reportsClientAddress() {
    throw new AssumptionViolatedException("ContainerRequestContext doesn't include remote address");
  }

  @Path("")
  public static class TestResource {
    final Tracer tracer;

    public TestResource(@Context ServletContext context) {
      this.tracer = (Tracer) context.getAttribute("tracer");
    }

    @GET
    @Path("foo")
    public Response get() {
      return Response.status(200).build();
    }

    @GET
    @Path("child")
    public Response child() {
      tracer.nextSpan().name("child").start().finish();
      return Response.status(200).build();
    }

    @GET
    @Path("async")
    public void async(@Suspended AsyncResponse response) throws IOException {
      new Thread(() -> response.resume("ok")).start();
    }

    @GET
    @Path("disconnect")
    public Response disconnect() throws IOException {
      throw new IOException();
    }

    @GET
    @Path("disconnectAsync")
    public void disconnectAsync(@Suspended AsyncResponse response) throws IOException {
      new Thread(() -> response.resume(new IOException())).start();
    }
  }

  /**
   * {@link ContainerResponseFilter} has no means to handle uncaught exceptions. Unless you provide
   * a catch-all exception mapper, requests that result in unhandled exceptions will leak until they
   * are eventually flushed.
   */
  @Provider
  public static class CatchAllExceptions implements ExceptionMapper<Exception> {

    @Override
    public Response toResponse(Exception e) {
      if (e instanceof WebApplicationException) {
        return ((WebApplicationException) e).getResponse();
      }

      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("Internal error")
          .type("text/plain")
          .build();
    }
  }

  @Override
  public void init(ServletContextHandler handler, Tracer tracer, Supplier<String> spanName) {

    ResourceConfig config = new ResourceConfig()
        .register(TestResource.class)
        .register(CatchAllExceptions.class)
        .register(BraveTracingFeature.builder(tracer)
            .containerConfig(new BraveTracingContainerFilter.Config() {
              @Override protected Parser<ContainerRequestContext, String> spanNameParser() {
                return spanName != null ? req -> spanName.get() : super.spanNameParser();
              }
            })
            .build()
        );

    handler.addServlet(new ServletHolder(new ServletContainer(config)), "/*");
    handler.setAttribute("tracer", tracer); // for TestResource
  }
}
