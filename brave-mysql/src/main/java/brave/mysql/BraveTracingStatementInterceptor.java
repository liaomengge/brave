package brave.mysql;

import brave.Span;
import brave.Tracer;
import com.mysql.jdbc.Connection;
import com.mysql.jdbc.PreparedStatement;
import com.mysql.jdbc.ResultSetInternalMethods;
import com.mysql.jdbc.Statement;
import com.mysql.jdbc.StatementInterceptorV2;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.Properties;
import zipkin.Constants;
import zipkin.Endpoint;
import zipkin.TraceKeys;

/**
 * A MySQL statement interceptor that will report to Zipkin how long each statement takes.
 *
 * <p>To use it, append <code>?statementInterceptors=brave.mysql.BraveTracingStatementInterceptor</code>
 * to the end of the connection url.
 */
public class BraveTracingStatementInterceptor implements StatementInterceptorV2 {

  private static String SERVICE_NAME_KEY = "zipkinServiceName";

  @Override
  public ResultSetInternalMethods preProcess(String sql, Statement interceptedStatement,
      Connection connection) throws SQLException {
    Tracer tracer = Tracer.current();
    if (tracer == null) return null;

    Span span = tracer.nextSpan();
    // regardless of noop or not, set it in scope so that custom contexts can see it (like slf4j)
    if (!span.isNoop()) {
      // When running a prepared statement, sql will be null and we must fetch the sql from the statement itself
      if (interceptedStatement instanceof PreparedStatement) {
        sql = ((PreparedStatement) interceptedStatement).getPreparedSql();
      }
      span.kind(Span.Kind.CLIENT).name(sql.substring(0, sql.indexOf(' ')));
      span.tag(TraceKeys.SQL_QUERY, sql);
      try {
        span.remoteEndpoint(remoteEndpoint(connection));
      } catch (Exception e) {
        // remote address is optional
      }
      span.start();
    }

    currentSpanInScope.set(tracer.withSpanInScope(span));

    return null;
  }

  /**
   * There's no attribute namespace shared across request and response. Hence, we need to save off
   * a reference to the span in scope, so that we can close it in the response.
   */
  final ThreadLocal<Tracer.SpanInScope> currentSpanInScope = new ThreadLocal<>();

  @Override
  public ResultSetInternalMethods postProcess(String sql, Statement interceptedStatement,
      ResultSetInternalMethods originalResultSet, Connection connection, int warningCount,
      boolean noIndexUsed, boolean noGoodIndexUsed, SQLException statementException)
      throws SQLException {

    Tracer tracer = Tracer.current();
    if (tracer == null) return null;

    Tracer.SpanInScope spanInScope = currentSpanInScope.get();
    if (spanInScope == null) return null;
    Span span = tracer.currentSpan();
    spanInScope.close();

    if (statementException != null) {
      span.tag(Constants.ERROR, Integer.toString(statementException.getErrorCode()));
    }
    span.finish();

    return null;
  }

  /**
   * MySQL exposes the host connecting to, but not the port. This attempts to get the port from the
   * JDBC URL. Ex. 5555 from {@code jdbc:mysql://localhost:5555/isSampled}, or 3306 if absent.
   */
  static Endpoint remoteEndpoint(Connection connection) throws Exception {
    InetAddress address = Inet4Address.getByName(connection.getHost());
    int ipv4 = ByteBuffer.wrap(address.getAddress()).getInt();

    URI url = URI.create(connection.getMetaData().getURL().substring(5)); // strip "jdbc:"
    int port = url.getPort() == -1 ? 3306 : url.getPort();

    Properties props = connection.getProperties();
    String serviceName = props.getProperty(SERVICE_NAME_KEY);
    if (serviceName == null || "".equals(serviceName)) {
      serviceName = "mysql";
      String databaseName = connection.getCatalog();
      if (databaseName != null && !"".equals(databaseName)) {
        serviceName += "-" + databaseName;
      }
    }

    return Endpoint.builder().ipv4(ipv4).port(port).serviceName(serviceName).build();
  }

  @Override public boolean executeTopLevelOnly() {
    return true; // True means that we don't get notified about queries that other interceptors issue
  }

  @Override public void init(Connection conn, Properties props) throws SQLException {
    // Don't care
  }

  @Override public void destroy() {
    // Don't care
  }
}
