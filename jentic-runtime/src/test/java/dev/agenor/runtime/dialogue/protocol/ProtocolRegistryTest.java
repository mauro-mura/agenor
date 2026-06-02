package dev.agenor.runtime.dialogue.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolRegistryTest {

    private ProtocolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ProtocolRegistry();
    }

    @Test
    void shouldRegisterBuiltInProtocols() {
        assertThat(registry.isRegistered("request")).isTrue();
        assertThat(registry.isRegistered("query")).isTrue();
        assertThat(registry.isRegistered("contract-net")).isTrue();
    }

    @Test
    void shouldGetProtocolById() {
        var request = registry.get("request");
        assertThat(request).isPresent();
        assertThat(request.get()).isInstanceOf(RequestProtocol.class);
    }

    @Test
    void shouldReturnEmptyForUnknownProtocol() {
        var unknown = registry.get("unknown");
        assertThat(unknown).isEmpty();
    }

    @Test
    void shouldReturnAllProtocols() {
        var all = registry.getAll();
        assertThat(all).hasSize(3);
        assertThat(all.keySet()).containsExactlyInAnyOrder("request", "query", "contract-net");
    }

    @Test
    void shouldRegisterCustomProtocol() {
        var custom = new QueryProtocol() {
            @Override
            public String getId() {
                return "custom-query";
            }
        };

        registry.register(custom);

        assertThat(registry.isRegistered("custom-query")).isTrue();
        assertThat(registry.get("custom-query")).isPresent();
    }
}
