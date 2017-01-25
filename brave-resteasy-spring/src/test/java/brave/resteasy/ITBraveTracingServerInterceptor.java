package brave.resteasy;

import brave.Tracer;
import brave.http.ITServletContainer;
import brave.parser.Parser;
import java.io.IOException;
import java.util.function.Supplier;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.jboss.resteasy.annotations.Suspend;
import org.jboss.resteasy.spi.AsynchronousResponse;
import org.jboss.resteasy.spi.HttpRequest;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.junit.AssumptionViolatedException;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportResource;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

/** This class uses servlet 2.5, so needs jetty 7 dependencies. */
public class ITBraveTracingServerInterceptor extends ITServletContainer {
  @Override @Test public void reportsSpanOnTransportException() throws Exception {
    throw new AssumptionViolatedException("TODO: unhandled synchronous exception mapping");
  }

  @Override @Test public void addsErrorTagOnTransportException() throws Exception {
    throw new AssumptionViolatedException("TODO: error tagging");
  }

  @Override @Test public void reportsClientAddress() {
    throw new AssumptionViolatedException("PostProcessInterceptor doesn't include remote address");
  }

  @Override @Test public void reportsClientAddress_XForwardedFor() {
    throw new AssumptionViolatedException("PostProcessInterceptor doesn't include remote address");
  }

  @Override @Test public void addsStatusCode_notFound() {
    throw new AssumptionViolatedException("NotFoundException can't be caught by an interceptor");
  }

  @Override @Test public void samplingDisabled() {
    throw new AssumptionViolatedException("not reloading server context");
  }

  @Path("")
  public static class TestResource {
    final Tracer tracer;

    @Autowired
    public TestResource(Tracer tracer) {
      this.tracer = tracer;
    }

    @GET
    @Path("foo")
    public Response get() {
      return Response.status(200).build();
    }

    @GET
    @Path("async")
    public void async(@Suspend AsynchronousResponse response) throws IOException {
      new Thread(() -> response.setResponse(Response.status(200).build())).start();
    }

    @GET
    @Path("child")
    public Response child() {
      tracer.nextSpan().name("child").start().finish();
      return Response.status(200).build();
    }

    @GET
    @Path("disconnect")
    public Response disconnect() throws IOException {
      throw new IOException();
    }
  }

  @Configuration
  @EnableWebMvc
  @ImportResource({"classpath:springmvc-resteasy.xml"})
  @Import({BraveTracingServerInterceptor.class})
  static class TracingConfig extends WebMvcConfigurerAdapter {
  }

  @Override
  public void init(ServletContextHandler handler, Tracer tracer, Supplier<String> spanName) {
    // Reset Resteasy's static state so that we don't end up with duplicate filter registrations
    ResteasyProviderFactory.setInstance(null);

    AnnotationConfigWebApplicationContext appContext =
        new AnnotationConfigWebApplicationContext() {
          // overriding this allows us to register dependencies of ServletHandlerInterceptor
          // without passing static state to a configuration class.
          @Override protected void loadBeanDefinitions(DefaultListableBeanFactory beanFactory) {
            beanFactory.registerSingleton("tracer", tracer);
            beanFactory.registerSingleton("config", new BraveTracingServerInterceptor.Config() {
              @Override protected Parser<HttpRequest, String> spanNameParser() {
                return spanName != null ? req -> spanName.get() : super.spanNameParser();
              }
            });
            super.loadBeanDefinitions(beanFactory);
          }
        };

    appContext.register(TestResource.class); // the test resource
    appContext.register(TracingConfig.class); // generic tracing setup
    handler.addServlet(new ServletHolder(new DispatcherServlet(appContext)), "/*");
  }
}
