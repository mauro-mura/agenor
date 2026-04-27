package dev.jentic.examples.discovery;

import dev.jentic.core.Message;
import dev.jentic.core.annotations.JenticAgent;
import dev.jentic.core.annotations.JenticBehavior;
import dev.jentic.core.annotations.JenticMessageHandler;
import dev.jentic.runtime.agent.BaseAgent;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import static dev.jentic.core.BehaviorType.CYCLIC;

/**
 * Agent that collects simulated sensor data and publishes it.
 */
@JenticAgent(value = "data-collector", 
             type = "sensor", 
             capabilities = {"data-collection", "sensors", "telemetry"},
             autoStart = true)
public class DataCollectorAgent extends BaseAgent {
    
    private final Random random = ThreadLocalRandom.current();
    private int dataPoints = 0;
    
    public DataCollectorAgent() {
        super("data-collector", "Data Collector");
    }
    
    @JenticBehavior(type = CYCLIC, interval = "6s", autoStart = true)
    public void collectSensorData() {
        dataPoints++;
        
        // Simulate various sensor readings
        double temperature = 20 + random.nextGaussian() * 5;  // ~20°C ± 5°C
        double humidity = 50 + random.nextGaussian() * 15;    // ~50% ± 15%
        double pressure = 1013 + random.nextGaussian() * 10; // ~1013 hPa ± 10
        
        SensorData data = new SensorData(
            "sensor-" + (random.nextInt(3) + 1),
            temperature,
            humidity,
            pressure,
            java.time.LocalDateTime.now()
        );
        
        Message dataMessage = Message.builder()
            .topic("sensor.data")
            .senderId(getAgentId())
            .content(data)
            .header("sensor-id", data.sensorId())
            .header("data-point", String.valueOf(dataPoints))
            .header("data-type", "environmental")
            .build();
        
        log.info("📈 Collected data point #{}: {} - {:.1f}°C, {:.1f}% humidity, {:.1f} hPa", 
                dataPoints, data.sensorId(), temperature, humidity, pressure);
        
        getMessageDispatcher().publish(dataMessage.topic(), dataMessage);

        // Occasionally send alerts for extreme values
        if (temperature > 35 || temperature < 5 || humidity > 90) {
            sendAlert(data, determineAlertLevel(temperature, humidity));
        }
    }
    
    @JenticMessageHandler("sensor.request")
    public void handleDataRequest(Message message) {
        String requestedSensor = message.headers().get("sensor-id");
        
        log.info("📡 Received data request for sensor: {}", requestedSensor);
        
        // Simulate immediate reading
        double temperature = 20 + random.nextGaussian() * 5;
        double humidity = 50 + random.nextGaussian() * 15;
        double pressure = 1013 + random.nextGaussian() * 10;
        
        SensorData data = new SensorData(
            requestedSensor != null ? requestedSensor : "sensor-on-demand",
            temperature,
            humidity,
            pressure,
            java.time.LocalDateTime.now()
        );
        
        Message response = message.reply(data)
            .topic("sensor.response")
            .senderId(getAgentId())
            .header("requested-sensor", requestedSensor)
            .build();
        
        getMessageDispatcher().publish(response.topic(), response);
        log.info("📤 Sent on-demand data for sensor: {}", data.sensorId());
    }
    
    private void sendAlert(SensorData data, String alertLevel) {
        Message alert = Message.builder()
            .topic("system.alert")
            .senderId(getAgentId())
            .content(String.format("Sensor %s reading outside normal range: %.1f°C, %.1f%% humidity", 
                                  data.sensorId(), data.temperature(), data.humidity()))
            .header("level", alertLevel)
            .header("sensor-id", data.sensorId())
            .header("temperature", String.valueOf(data.temperature()))
            .header("humidity", String.valueOf(data.humidity()))
            .build();
        
        log.warn("⚠️  Sending {} alert for sensor {}", alertLevel, data.sensorId());
        getMessageDispatcher().publish(alert.topic(), alert);
    }
    
    private String determineAlertLevel(double temperature, double humidity) {
        if (temperature > 40 || temperature < 0 || humidity > 95) {
            return "CRITICAL";
        } else if (temperature > 35 || temperature < 5 || humidity > 90) {
            return "WARNING";
        } else {
            return "INFO";
        }
    }
    
    @Override
    protected void onStart() {
        log.info("📊 Data Collector started - monitoring environmental sensors");
    }
    
    @Override
    protected void onStop() {
        log.info("📊 Data Collector stopped - collected {} data points", dataPoints);
    }
    
    // Sensor data record
    public record SensorData(
        String sensorId,
        double temperature,
        double humidity,
        double pressure,
        java.time.LocalDateTime timestamp
    ) {}
}