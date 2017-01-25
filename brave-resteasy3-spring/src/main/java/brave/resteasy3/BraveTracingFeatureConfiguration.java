package brave.resteasy3;

import brave.jaxrs2.BraveTracingClientFilter;
import brave.jaxrs2.BraveTracingContainerFilter;
import brave.jaxrs2.BraveTracingFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Imports jaxrs2 filters used in resteasy3. */
@Configuration
public class BraveTracingFeatureConfiguration {
  // instead of @Conditional or @ConditionalOnMissingBean in order to support spring 3.x
  @Autowired(required = false)
  BraveTracingClientFilter.Config clientConfig = new BraveTracingClientFilter.Config();
  @Autowired(required = false)
  BraveTracingContainerFilter.Config containerConfig = new BraveTracingContainerFilter.Config();

  @Bean public BraveTracingFeature braveTracingFeature(brave.Tracer tracer) {
    return BraveTracingFeature.builder(tracer)
        .clientConfig(clientConfig)
        .containerConfig(containerConfig).build();
  }
}