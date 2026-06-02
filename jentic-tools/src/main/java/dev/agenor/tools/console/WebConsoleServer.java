package dev.agenor.tools.console;

import dev.agenor.runtime.AgenorRuntime;

/**
 * Backward-compatible alias for JettyWebConsole.
 *
 * @deprecated Use {@link JettyWebConsole} directly
 */
@Deprecated(since = "0.4.0")
public class WebConsoleServer {

    private final JettyWebConsole delegate;

    private WebConsoleServer(Builder builder) {
        this.delegate = JettyWebConsole.builder()
            .port(builder.port)
            .runtime(builder.runtime)
            .build();
    }

    public void start() throws Exception {
        delegate.start().join();
    }

    public void stop() throws Exception {
        delegate.stop().join();
    }

    public boolean isRunning() {
        return delegate.isRunning();
    }

    public int getPort() {
        return delegate.getPort();
    }

    public AgenorRuntime getRuntime() {
        return delegate.getRuntime();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int port = 8080;
        private AgenorRuntime runtime;

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder runtime(AgenorRuntime runtime) {
            this.runtime = runtime;
            return this;
        }

        public WebConsoleServer build() {
            if (runtime == null) {
                throw new IllegalStateException("Runtime required");
            }
            return new WebConsoleServer(this);
        }
    }
}






