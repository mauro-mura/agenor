package dev.agenor.runtime.dialogue.protocol;

import dev.agenor.core.dialogue.protocol.Protocol;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for interaction protocols.
 *
 * @since 0.5.0
 */
public class ProtocolRegistry {

    private final Map<String, Protocol> protocols = new ConcurrentHashMap<>();

    public ProtocolRegistry() {
        // Register built-in protocols
        register(new RequestProtocol());
        register(new QueryProtocol());
        register(new ContractNetProtocol());
    }

    /**
     * Registers a protocol.
     *
     * @param protocol the protocol to register
     */
    public void register(Protocol protocol) {
        protocols.put(protocol.getId(), protocol);
    }

    /**
     * Gets a protocol by ID.
     *
     * @param protocolId the protocol ID
     * @return the protocol if found
     */
    public Optional<Protocol> get(String protocolId) {
        return Optional.ofNullable(protocols.get(protocolId));
    }

    /**
     * @return all registered protocols
     */
    public Map<String, Protocol> getAll() {
        return Map.copyOf(protocols);
    }

    /**
     * @param protocolId the protocol ID
     * @return true if protocol is registered
     */
    public boolean isRegistered(String protocolId) {
        return protocols.containsKey(protocolId);
    }
}
