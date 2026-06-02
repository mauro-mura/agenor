package dev.agenor.examples.cli;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

import dev.agenor.core.BehaviorType;
import dev.agenor.core.Message;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.runtime.agent.BaseAgent;

@Agent(
    value = "sensor-agent"
)
public class TemperatureSensorAgent extends BaseAgent {

    private final Random random = new Random();
    private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");

    public TemperatureSensorAgent() {
        super("sensor-agent", "Temperature Sensor");
    }

    @Behavior(
        type = BehaviorType.CYCLIC,
        interval = "5s"
    )
    public void readTemperature() {
        double temp = 20.0 + random.nextDouble() * 15.0; // 20-35°C
        String timestamp = LocalDateTime.now().format(fmt);
        String tempFormatted = String.format("%.1f", temp);

        log.info("[{}] Temperature: {}°C", timestamp, tempFormatted);

        // Send temperature reading
        var tempMsg = Message.builder()
                .topic("sensor.temperature")
                .senderId(getAgentId())
                .content(new TemperatureReading(temp, timestamp))
                .header("unit", "celsius")
                .build();
        getMessageDispatcher().publish(tempMsg);

        // Trigger alert if too high
        if (temp > 30.0) {
            var alertMsg = Message.builder()
                    .topic("sensor.alert.temperature")
                    .senderId(getAgentId())
                    .content("High temperature alert: " + tempFormatted + "°C")
                    .header("severity", "WARNING")
                    .build();
            getMessageDispatcher().publish(alertMsg);
        }
    }

    public record TemperatureReading(double value, String timestamp) {}
}
