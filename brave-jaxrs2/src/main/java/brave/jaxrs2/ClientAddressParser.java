package brave.jaxrs2;

import brave.parser.Parser;
import java.nio.ByteBuffer;
import javax.ws.rs.container.ContainerRequestContext;
import zipkin.Endpoint;

import static com.github.kristofa.brave.internal.InetAddresses.ipStringToBytes;

final class ClientAddressParser implements Parser<ContainerRequestContext, Endpoint> {
  final String serviceName;

  ClientAddressParser(String serviceName) {
    this.serviceName = serviceName;
  }

  @Override public Endpoint parse(ContainerRequestContext request) {
    byte[] addressBytes = ipStringToBytes(request.getHeaderString("X-Forwarded-For"));
    if (addressBytes == null) return null;
    Endpoint.Builder builder = Endpoint.builder().serviceName(serviceName);
    if (addressBytes.length == 4) {
      builder.ipv4(ByteBuffer.wrap(addressBytes).getInt());
    } else if (addressBytes.length == 16) {
      builder.ipv6(addressBytes);
    }
    return builder.build();
  }
}
