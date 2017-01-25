package brave.sparkjava;

import brave.Tracer;
import brave.http.ITHttpServer;
import brave.parser.Parser;
import java.util.function.Supplier;
import javax.servlet.http.HttpServletRequest;
import org.junit.After;
import org.junit.ComparisonFailure;
import org.junit.Test;
import spark.ExceptionHandler;
import spark.Request;
import spark.Response;
import spark.Spark;

public class ITBraveTracing extends ITHttpServer {

  /**
   * Async tests are ignored until https://github.com/perwendel/spark/issues/208
   */
  @Override
  @Test(expected = ComparisonFailure.class)
  public void async() throws Exception {
    super.async();
  }

  @Override
  public void init(Tracer tracer, Supplier<String> spanName) throws Exception {
    stop();

    BraveTracing tracing = BraveTracing.builder(tracer)
        .config(new BraveTracing.Config() {
          @Override protected Parser<HttpServletRequest, String> spanNameParser() {
            return spanName != null ? req -> spanName.get() : super.spanNameParser();
          }
        }).build();

    Spark.before(tracing.before());
    Spark.exception(Exception.class, tracing.exception(new ExceptionHandler() {
      @Override public void handle(Exception exception, Request request, Response response) {
        response.body("exception");
      }
    }));
    Spark.afterAfter(tracing.afterAfter());

    Spark.get("/foo", (req, res) -> "bar");
    Spark.get("/child", (req, res) -> {
      tracer.nextSpan().name("child").start().finish();
      return "happy";
    });
    Spark.get("/disconnect", (req, res) -> {
      throw new Exception();
    });

    Spark.awaitInitialization();
  }

  @Override
  protected String url(String path) {//default port 4567
    return "http://localhost:4567" + path;
  }

  /**
   * Spark stop asynchronously but share one class Instance,
   * so AddressAlreadyUsed Exception may happen.
   * See:https://github.com/perwendel/spark/issues/705 .
   * Just sleep 1 second to avoid this happens,
   * after Spark.awaitStopped add,I will fix it.
   */
  @After
  public void stop() throws InterruptedException {
    Spark.stop();
    Thread.sleep(1000);
  }
}
