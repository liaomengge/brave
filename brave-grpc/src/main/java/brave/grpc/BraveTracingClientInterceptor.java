package brave.grpc;

import brave.ClientHandler;
import brave.Span;
import brave.Tracer;
import brave.parser.Parser;
import brave.parser.TagsParser;
import brave.propagation.Propagation;
import brave.propagation.TraceContext;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import zipkin.Constants;

import static com.github.kristofa.brave.internal.Util.checkNotNull;

/** This interceptor traces outbound calls */
public final class BraveTracingClientInterceptor implements ClientInterceptor {

  /** Creates a tracing interceptor with defaults. Use {@link #builder(Tracer)} to customize. */
  public static BraveTracingClientInterceptor create(Tracer tracer) {
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

    public BraveTracingClientInterceptor build() {
      return new BraveTracingClientInterceptor(this);
    }
  }

  // Not final so it can be overridden to customize tags
  // anonymous classes vs lambdas as retrolambda fails on GRPC classes in circleci
  public static class Config extends ClientHandler.Config<MethodDescriptor, Status> {

    @Override protected Parser<MethodDescriptor, String> spanNameParser() {
      return new Parser<MethodDescriptor, String>() {
        @Override public String parse(MethodDescriptor methodDescriptor) {
          return methodDescriptor.getFullMethodName();
        }
      };
    }

    @Override protected TagsParser<MethodDescriptor> requestTagsParser() {
      return new TagsParser<MethodDescriptor>() {
        @Override public void addTagsToSpan(MethodDescriptor req, Span span) {
        }
      };
    }

    @Override protected TagsParser<Status> responseTagsParser() {
      return new TagsParser<Status>() {
        @Override public void addTagsToSpan(Status status, Span span) {
          if (!status.getCode().equals(Status.Code.OK)) {
            span.tag(Constants.ERROR, String.valueOf(status.getCode()));
          }
        }
      };
    }
  }

  final Tracer tracer;
  final ClientHandler<MethodDescriptor, Status> clientHandler;
  final TraceContext.Injector<Metadata> injector;

  BraveTracingClientInterceptor(Builder builder) {
    tracer = builder.tracer;
    clientHandler = ClientHandler.create(builder.config);
    injector = Propagation.Factory.B3.create(AsciiMetadataKeyFactory.INSTANCE)
        .injector(new Propagation.Setter<Metadata, Metadata.Key<String>>() { // retrolambda no like
          @Override public void put(Metadata metadata, Metadata.Key<String> key, String value) {
            metadata.put(key, value);
          }
        });
  }

  /**
   * This sets as span in scope both for the interception and for the start of the request. It does
   * not set a span in scope during the response listener as it is unexpected it would be used at
   * that fine granularity. If users want access to the span in a response listener, they will need
   * to wrap the executor with one that's aware of the current context.
   */
  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      final MethodDescriptor<ReqT, RespT> method, final CallOptions callOptions,
      final Channel next) {
    Span span = tracer.nextSpan();
    try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
      return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          clientHandler.handleSend(method, span);
          injector.inject(span.context(), headers);
          try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
            super.start(new SimpleForwardingClientCallListener<RespT>(responseListener) {
              @Override public void onClose(Status status, Metadata trailers) {
                clientHandler.handleReceive(status, span);
                super.onClose(status, trailers);
              }
            }, headers);
          }
        }
      };
    }
  }
}