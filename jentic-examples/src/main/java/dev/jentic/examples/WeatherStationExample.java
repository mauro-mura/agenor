package dev.jentic.examples;

import dev.jentic.core.Message;
import dev.jentic.core.annotations.JenticAgent;
import dev.jentic.core.annotations.JenticBehavior;
import dev.jentic.core.annotations.JenticMessageHandler;
import dev.jentic.runtime.agent.BaseAgent;
import dev.jentic.runtime.JenticRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import static dev.jentic.core.BehaviorType.CYCLIC;

/**
 * Weather station example with data collector and data processor agents.
 */
public class WeatherStationExample {
    
    private static final Logger log = LoggerFactory.getLogger(WeatherStationExample.class);
    
    public static void main(String[] args) throws InterruptedException {
        log.info("=== Jentic Weather Station Example ===");
        
        JenticRuntime runtime = JenticRuntime.builder().build();
        
        // Register agents
        runtime.registerAgent(new WeatherCollectorAgent());
        runtime.registerAgent(new WeatherProcessorAgent());
        runtime.registerAgent(new WeatherDisplayAgent());
        
        // Start runtime
        runtime.start().join();
        
        log.info("Weather station started with {} agents", runtime.getAgents().size());
        
        // Run for 30 seconds
        Thread.sleep(30_000);
        
        // Stop runtime
        log.info("Stopping weather station...");
        runtime.stop().join();
        
        log.info("=== Weather Station Example completed ===");
    }
    
    // Weather data record
    public record WeatherData(
        String location,
        double temperature,
        double humidity,
        String condition,
        LocalDateTime timestamp
    ) {
        @Override
        public String toString() {
            return String.format("%s: %.1f°C, %.1f%% humidity, %s at %s",
                location, temperature, humidity, condition, 
                timestamp.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        }
    }
    
    /**
     * Agent that collects weather data periodically
     */
    @JenticAgent(value = "weather-collector", 
                 type = "sensor", 
                 capabilities = {"data-collection", "weather"},
                 autoStart = true)
    public static class WeatherCollectorAgent extends BaseAgent {
        
        private final Random random = new Random();
        private final String[] locations = {"Rome", "Milan", "Naples", "Turin", "Florence"};
        private final String[] conditions = {"Sunny", "Cloudy", "Rainy", "Partly Cloudy", "Windy"};
        
        public WeatherCollectorAgent() {
            super("weather-collector", "Weather Collector");
        }
        
        @JenticBehavior(type = CYCLIC, interval = "5s", autoStart = true)
        public void collectWeatherData() {
            String location = locations[random.nextInt(locations.length)];
            double temperature = 15 + random.nextDouble() * 20; // 15-35°C
            double humidity = 30 + random.nextDouble() * 70;    // 30-100%
            String condition = conditions[random.nextInt(conditions.length)];
            
            WeatherData data = new WeatherData(
                location, temperature, humidity, condition, LocalDateTime.now());
            
            Message message = Message.builder()
                .topic("weather.raw-data")
                .senderId(getAgentId())
                .content(data)
                .header("location", location)
                .header("data-type", "weather-reading")
                .build();
            
            log.info("Collected: {}", data);
            getMessageDispatcher().publish(message.topic(), message);
        }
        
        @Override
        protected void onStart() {
            log.info("Weather Collector started, monitoring {} locations", locations.length);
        }
    }
    
    /**
     * Agent that processes raw weather data
     */
    @JenticAgent(value = "weather-processor", 
                 type = "processor", 
                 capabilities = {"data-processing", "analytics"},
                 autoStart = true)
    public static class WeatherProcessorAgent extends BaseAgent {
        
        public WeatherProcessorAgent() {
            super("weather-processor", "Weather Processor");
        }
        
        @JenticMessageHandler("weather.raw-data")
        public void processWeatherData(Message message) {
            WeatherData rawData = message.getContent(WeatherData.class);
            
            // Simple processing: add alerts for extreme conditions
            String alert = null;
            if (rawData.temperature() > 30) {
                alert = "HIGH_TEMP";
            } else if (rawData.temperature() < 5) {
                alert = "LOW_TEMP";
            } else if (rawData.humidity() > 90) {
                alert = "HIGH_HUMIDITY";
            }
            
            // Create processed message
            Message.MessageBuilder processedMessage = Message.builder()
                .topic("weather.processed-data")
                .senderId(getAgentId())
                .content(rawData)
                .header("processed-by", getAgentId())
                .header("processing-time", LocalDateTime.now().toString());
            
            if (alert != null) {
                processedMessage.header("alert", alert);
                log.warn("Weather Alert: {} for {}", alert, rawData.location());
            }
            
            log.debug("Processed weather data for {}", rawData.location());
            var built = processedMessage.build();
            getMessageDispatcher().publish(built.topic(), built);
        }
        
        @Override
        protected void onStart() {
            log.info("Weather Processor ready to process raw weather data");
        }
    }
    
    /**
     * Agent that displays processed weather data
     */
    @JenticAgent(value = "weather-display", 
                 type = "display", 
                 capabilities = {"data-display", "alerts"},
                 autoStart = true)
    public static class WeatherDisplayAgent extends BaseAgent {
        
        public WeatherDisplayAgent() {
            super("weather-display", "Weather Display");
        }
        
        @JenticMessageHandler("weather.processed-data")
        public void displayWeatherData(Message message) {
            WeatherData data = message.getContent(WeatherData.class);
            String alert = message.headers().get("alert");
            
            if (alert != null) {
                log.info("🚨 ALERT - {}: {} [{}]", data.location(), data, alert);
            } else {
                log.info("📊 {}", data);
            }
        }
        
        @Override
        protected void onStart() {
            log.info("Weather Display ready to show weather information");
        }
    }
}