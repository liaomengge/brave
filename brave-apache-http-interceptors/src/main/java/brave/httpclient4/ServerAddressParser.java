package brave.httpclient4;

import brave.parser.Parser;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import org.apache.http.HttpHost;
import org.apache.http.client.protocol.HttpClientContext;
import zipkin.Endpoint;

final class ServerAddressParser implements Parser<HttpClientContext, Endpoint> {
  final String serviceName;

  ServerAddressParser(String serviceName) {
    this.serviceName = serviceName;
  }

  @Override public Endpoint parse(HttpClientContext source) {
    HttpHost host = source.getTargetHost();
    if (host == null) return null;
    InetAddress addr = host.getAddress();
    if (addr == null) return null;
    Endpoint.Builder builder = Endpoint.builder().serviceName(serviceName);
    byte[] addressBytes = addr.getAddress();
    if (addressBytes.length == 4) {
      builder.ipv4(ByteBuffer.wrap(addressBytes).getInt());
    } else if (addressBytes.length == 16) {
      builder.ipv6(addressBytes);
    }
    int port = host.getPort();
    if (port != -1) builder.port(port);
    return builder.build();
  }
}
