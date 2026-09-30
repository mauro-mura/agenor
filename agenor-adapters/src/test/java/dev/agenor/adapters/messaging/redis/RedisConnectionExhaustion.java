package dev.agenor.adapters.messaging.redis;

import io.lettuce.core.RedisClient;

/**
 * Makes a real Redis-protocol server refuse the next connection, so a test can watch what a
 * transport does when {@code newConsumerConnection()} fails against a live server rather than
 * against a mock of the transport's own client.
 *
 * <p>The server's client limit is lowered to the number of connections already open plus this
 * helper's own, which leaves the transport's write connection in place and refuses any further
 * one. {@link #close()} lifts the limit again through the connection that still holds a slot.
 */
final class RedisConnectionExhaustion implements AutoCloseable {

    private static final String UNLIMITED = "10000";

    private final RedisClient client;
    private final io.lettuce.core.api.StatefulRedisConnection<String, String> conn;

    private RedisConnectionExhaustion(RedisClient client,
                                      io.lettuce.core.api.StatefulRedisConnection<String, String> conn) {
        this.client = client;
        this.conn   = conn;
    }

    /** Call once the factory under test is built: its write connection must already be open. */
    static RedisConnectionExhaustion afterWriteConnection(String uri) {
        var client = RedisClient.create(uri);
        var conn   = client.connect();
        // One slot for the factory's write connection, one for this helper.
        conn.sync().configSet("maxclients", "2");
        return new RedisConnectionExhaustion(client, conn);
    }

    @Override
    public void close() {
        try {
            conn.sync().configSet("maxclients", UNLIMITED);
        } finally {
            conn.close();
            client.shutdown();
        }
    }
}
