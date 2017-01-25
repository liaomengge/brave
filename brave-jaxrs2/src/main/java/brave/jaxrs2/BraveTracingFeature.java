package brave.jaxrs2;

import brave.Tracer;
import javax.ws.rs.core.Feature;
import javax.ws.rs.core.FeatureContext;
import javax.ws.rs.ext.Provider;

import static com.github.kristofa.brave.internal.Util.checkNotNull;

@Provider
public final class BraveTracingFeature implements Feature {

  /** Creates a tracing feature with defaults. Use {@link #builder(Tracer)} to customize. */
  public static BraveTracingFeature create(Tracer tracer) {
    return new Builder(tracer).build();
  }

  public static Builder builder(Tracer tracer) {
    return new Builder(tracer);
  }

  public static final class Builder {
    final Tracer tracer;
    BraveTracingClientFilter.Config clientConfig = new BraveTracingClientFilter.Config();
    BraveTracingContainerFilter.Config containerConfig = new BraveTracingContainerFilter.Config();

    Builder(Tracer tracer) { // intentionally hidden
      this.tracer = checkNotNull(tracer, "tracer");
    }

    public Builder clientConfig(BraveTracingClientFilter.Config clientConfig) {
      this.clientConfig = checkNotNull(clientConfig, "clientConfig");
      return this;
    }

    public Builder containerConfig(BraveTracingContainerFilter.Config containerConfig) {
      this.containerConfig = checkNotNull(containerConfig, "containerConfig");
      return this;
    }

    public BraveTracingFeature build() {
      return new BraveTracingFeature(this);
    }
  }

  final Tracer tracer;
  final BraveTracingClientFilter.Config clientConfig;
  final BraveTracingContainerFilter.Config containerConfig;

  BraveTracingFeature(Builder b) { // intentionally hidden
    tracer = b.tracer;
    clientConfig = b.clientConfig;
    containerConfig = b.containerConfig;
  }

  @Override
  public boolean configure(FeatureContext context) {
    context.register(BraveTracingClientFilter.builder(tracer).config(clientConfig).build());
    context.register(BraveTracingContainerFilter.builder(tracer).config(containerConfig).build());
    return true;
  }
}
