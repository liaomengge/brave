package brave.p6spy;

import com.p6spy.engine.event.JdbcEventListener;
import com.p6spy.engine.spy.P6Factory;
import com.p6spy.engine.spy.P6LoadableOptions;
import com.p6spy.engine.spy.P6SpyOptions;
import com.p6spy.engine.spy.option.P6OptionsRepository;

public final class BraveTracingP6Factory implements P6Factory {

  @Override public P6LoadableOptions getOptions(P6OptionsRepository repository) {
    return new P6SpyOptions(repository);
  }

  @Override public JdbcEventListener getJdbcEventListener() {
    return new BraveTracingJdbcEventListener();
  }
}