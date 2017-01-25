package brave.spring.servlet;

import brave.Tracer;
import brave.http.ITServletContainer;
import brave.parser.Parser;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import javax.servlet.http.HttpServletRequest;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.AssumptionViolatedException;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

public class ITBraveTracingHandlerInterceptor extends ITServletContainer {

  @Override @Test public void addsStatusCode_notFound() throws Exception {
    throw new AssumptionViolatedException("TODO: fix error reporting");
  }

  @Controller
  static class TestController {
    final Tracer tracer;

    @Autowired TestController(Tracer tracer) {
      this.tracer = tracer;
    }

    @RequestMapping(value = "/foo")
    public ResponseEntity<Void> foo() throws IOException {
      return ResponseEntity.ok().build();
    }

    @RequestMapping(value = "/child")
    public ResponseEntity<Void> child() {
      tracer.nextSpan().name("child").start().finish();
      return ResponseEntity.ok().build();
    }

    @RequestMapping(value = "/async")
    public Callable<ResponseEntity<Void>> async() throws IOException {
      return () -> ResponseEntity.ok().build();
    }

    @RequestMapping(value = "/disconnect")
    public ResponseEntity<Void> disconnect() throws IOException {
      throw new IOException();
    }

    @RequestMapping(value = "/disconnectAsync")
    public Callable<ResponseEntity<Void>> disconnectAsync() throws IOException {
      return () -> {
        throw new IOException();
      };
    }
  }

  @Configuration
  @EnableWebMvc
  @Import(BraveTracingHandlerInterceptor.class)
  static class TracingConfig extends WebMvcConfigurerAdapter {

    @Autowired
    private BraveTracingHandlerInterceptor interceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
      registry.addInterceptor(interceptor);
    }
  }

  @Override
  public void init(ServletContextHandler handler, Tracer tracer, Supplier<String> spanName) {
    BraveTracingHandlerInterceptor.Config config = new BraveTracingHandlerInterceptor.Config() {
      @Override protected Parser<HttpServletRequest, String> spanNameParser() {
        return spanName != null ? req -> spanName.get() : super.spanNameParser();
      }
    };
    AnnotationConfigWebApplicationContext appContext =
        new AnnotationConfigWebApplicationContext() {
          // overriding this allows us to register dependencies of BraveTracingHandlerInterceptor
          // without passing static state to a configuration class.
          @Override protected void loadBeanDefinitions(DefaultListableBeanFactory beanFactory) {
            beanFactory.registerSingleton("tracer", tracer);
            beanFactory.registerSingleton("config", config);
            super.loadBeanDefinitions(beanFactory);
          }
        };

    appContext.register(TestController.class); // the test resource
    appContext.register(TracingConfig.class); // generic tracing setup
    handler.addServlet(new ServletHolder(new DispatcherServlet(appContext)), "/*");
  }
}
