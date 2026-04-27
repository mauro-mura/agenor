package dev.jentic.examples.llm.tools;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jentic.core.Message;
import dev.jentic.core.annotations.JenticAgent;
import dev.jentic.core.annotations.JenticMessageHandler;
import dev.jentic.core.llm.FunctionCall;
import dev.jentic.core.llm.FunctionDefinition;
import dev.jentic.core.llm.LLMMessage;
import dev.jentic.core.llm.LLMProvider;
import dev.jentic.core.llm.LLMRequest;
import dev.jentic.core.llm.LLMResponse;
import dev.jentic.runtime.agent.BaseAgent;

/**
 * AI Assistant Agent with LLM function calling capabilities.
 * 
 * Demonstrates proper Jentic architecture patterns:
 * - Extends BaseAgent
 * - Uses @JenticAgent annotation
 * - Implements message-driven interaction
 * - Follows dependency injection patterns
 * - Includes comprehensive tool registry
 * 
 * @since 0.4.0
 */
@JenticAgent(
    value = "ai-assistant", 
    type = "ai",
    capabilities = {"llm-reasoning", "function-calling", "tool-execution"},
    autoStart = true
)
public class AIAssistantAgent extends BaseAgent {
    
    private static final Logger log = LoggerFactory.getLogger(AIAssistantAgent.class);
    
    private final LLMProvider llmProvider;
    private final ToolRegistry toolRegistry;
    private final List<LLMMessage> conversationHistory;
    private final int maxHistorySize = 20;
    
    /**
     * Constructor with dependency injection.
     * 
     * @param llmProvider the LLM provider for AI reasoning
     */
    public AIAssistantAgent(LLMProvider llmProvider) {
        super("ai-assistant", "AI Assistant Agent");
        this.llmProvider = Objects.requireNonNull(llmProvider, "LLM provider cannot be null");
        this.toolRegistry = new ToolRegistry();
        this.conversationHistory = new ArrayList<>();
        
        // Register available tools
        registerTools();
    }
        
    @Override
    protected void onStart() {
        log.info("AI Assistant Agent started with {} registered tools", toolRegistry.getToolCount());
        
        // Send startup message
        var startMsg = Message.builder()
            .topic("ai.assistant.started")
            .senderId(getAgentId())
            .content("AI Assistant is ready to help!")
            .build();
        getMessageDispatcher().publish(startMsg.topic(), startMsg);
    }
    
    @Override
    protected void onStop() {
        log.info("AI Assistant Agent stopped");
        
        // Send shutdown message
        var stopMsg = Message.builder()
            .topic("ai.assistant.stopped")
            .senderId(getAgentId())
            .content("AI Assistant is shutting down")
            .build();
        getMessageDispatcher().publish(stopMsg.topic(), stopMsg);
    }
    
    /**
     * Handle chat requests from users or other agents.
     * 
     * @param message the incoming chat message
     */
    @JenticMessageHandler("ai.chat.request")
    public void handleChatRequest(Message message) {
        try {
            String userInput = message.getContent(String.class);
            String correlationId = message.id();
            
            log.info("Processing chat request: {}", userInput);
            
            processUserRequest(userInput, correlationId)
                .thenAccept(response -> {
                    // Send response back
                    Message responseMessage = Message.builder()
                        .topic("ai.chat.response")
                        .correlationId(correlationId)
                        .senderId(getAgentId())
                        .content(response)
                        .build();
                    
                    getMessageDispatcher().publish(responseMessage.topic(), responseMessage);

                    log.info("Chat response sent for request: {}", correlationId);
                })
                .exceptionally(throwable -> {
                    log.error("Error processing chat request: {}", correlationId, throwable);

                    // Send error response
                    Message errorMessage = Message.builder()
                        .topic("ai.chat.error")
                        .correlationId(correlationId)
                        .senderId(getAgentId())
                        .content("I apologize, but I encountered an error processing your request: " +
                                throwable.getMessage())
                        .build();

                    getMessageDispatcher().publish(errorMessage.topic(), errorMessage);
                    return null;
                });
                
        } catch (Exception e) {
            log.error("Error handling chat request", e);
        }
    }
    
    /**
     * Handle tool execution requests from other agents.
     * 
     * @param message the tool execution message
     */
    @JenticMessageHandler("ai.tool.execute")
    public void handleToolExecution(Message message) {
        try {
            ToolExecutionRequest request = message.getContent(ToolExecutionRequest.class);
            String correlationId = message.id();
            
            log.debug("Executing tool: {} with args: {}", request.toolName(), request.arguments());
            
            toolRegistry.executeTool(request.toolName(), request.arguments())
                .thenAccept(result -> {
                    ToolExecutionResponse response = new ToolExecutionResponse(
                        request.toolName(),
                        result,
                        true,
                        null
                    );
                    
                    Message responseMessage = Message.builder()
                        .topic("ai.tool.result")
                        .correlationId(correlationId)
                        .senderId(getAgentId())
                        .content(response)
                        .build();
                    
                    getMessageDispatcher().publish(responseMessage.topic(), responseMessage);
                })
                .exceptionally(throwable -> {
                    ToolExecutionResponse response = new ToolExecutionResponse(
                        request.toolName(),
                        null,
                        false,
                        throwable.getMessage()
                    );

                    Message errorMessage = Message.builder()
                        .topic("ai.tool.result")
                        .correlationId(correlationId)
                        .senderId(getAgentId())
                        .content(response)
                        .build();

                    getMessageDispatcher().publish(errorMessage.topic(), errorMessage);
                    return null;
                });
                
        } catch (Exception e) {
            log.error("Error handling tool execution", e);
        }
    }
    
    /**
     * Process user request with LLM and function calling.
     */
    private CompletableFuture<String> processUserRequest(String userInput, String correlationId) {
        // Add user message to conversation history
        conversationHistory.add(LLMMessage.user(userInput));
        trimConversationHistory();
        
        // Create LLM request with available functions
        LLMRequest request = LLMRequest.builder()
            .messages(conversationHistory)
            .addFunction(createWeatherFunction())
            .addFunction(createCalculatorFunction())
            .addFunction(createTimeFunction())
            .addFunction(createDatabaseFunction())
            .functionCall("auto")
            .temperature(0.7)
            .maxTokens(1500)
            .build();
        
        return llmProvider.chat(request)
            .thenCompose(response -> handleLLMResponse(response, correlationId));
    }
    
    /**
     * Handle LLM response and execute any requested functions.
     */
    private CompletableFuture<String> handleLLMResponse(LLMResponse response, String correlationId) {
        if (!response.hasFunctionCalls()) {
            // No function calls, add response to history and return
            conversationHistory.add(LLMMessage.assistant(response.content()));
            return CompletableFuture.completedFuture(response.content());
        }
        
        log.debug("LLM requested {} function call(s)", response.functionCalls().size());
        
        // Execute all requested function calls
        List<CompletableFuture<FunctionCallResult>> functionFutures = response.functionCalls()
            .stream()
            .map(call -> executeFunctionCall(call))
            .toList();
        
        return CompletableFuture.allOf(functionFutures.toArray(new CompletableFuture[0]))
            .thenCompose(v -> {
                // Add assistant message with function calls to history
                conversationHistory.add(LLMMessage.assistant(response.content(), response.functionCalls()));
                
                // Add function results to history
                for (CompletableFuture<FunctionCallResult> future : functionFutures) {
                    FunctionCallResult result = future.join();
                    conversationHistory.add(LLMMessage.function(result.functionName(), result.result()));
                }
                
                // Make follow-up request with function results
                LLMRequest followUpRequest = LLMRequest.builder()
                    .messages(conversationHistory)
                    .temperature(0.7)
                    .maxTokens(1500)
                    .build();
                
                return llmProvider.chat(followUpRequest);
            })
            .thenApply(finalResponse -> {
                conversationHistory.add(LLMMessage.assistant(finalResponse.content()));
                return finalResponse.content();
            });
    }
    
    /**
     * Execute a single function call.
     */
    private CompletableFuture<FunctionCallResult> executeFunctionCall(FunctionCall call) {
        try {
            Map<String, Object> args = parseJsonArguments(call.arguments());
            
            return toolRegistry.executeTool(call.name(), args)
                .thenApply(result -> new FunctionCallResult(call.name(), result.toString()))
                .exceptionally(throwable -> {
                    log.warn("Function call failed: {}", call.name(), throwable);
                    return new FunctionCallResult(call.name(), "Error: " + throwable.getMessage());
                });
                
        } catch (Exception e) {
            log.warn("Error parsing function call arguments: {}", call.arguments(), e);
            return CompletableFuture.completedFuture(
                new FunctionCallResult(call.name(), "Error: Invalid arguments")
            );
        }
    }
    
    /**
     * Register all available tools.
     */
    private void registerTools() {
        registerWeatherTool();
        registerCalculatorTool(); 
        registerTimeTool();
        registerDatabaseTool();
        
        log.info("Registered {} tools for AI Assistant", toolRegistry.getToolCount());
    }
    
    /**
     * Register weather tool with mock implementation.
     */
    private void registerWeatherTool() {
        toolRegistry.registerTool("get_weather", args -> {
            String location = (String) args.get("location");
            String units = (String) args.getOrDefault("units", "celsius");
            
            // Generate realistic mock weather data
            String[] conditions = {"sunny", "cloudy", "rainy", "partly cloudy", "clear", "stormy"};
            String condition = conditions[ThreadLocalRandom.current().nextInt(conditions.length)];
            
            double tempC = ThreadLocalRandom.current().nextDouble(5, 35);
            double temperature = "fahrenheit".equals(units) ? (tempC * 9.0 / 5.0) + 32 : tempC;
            
            WeatherData weatherData = new WeatherData(
                location,
                Math.round(temperature * 10.0) / 10.0,
                units,
                condition,
                ThreadLocalRandom.current().nextInt(30, 95),
                Math.round(ThreadLocalRandom.current().nextDouble(2, 30) * 10.0) / 10.0,
                LocalDateTime.now()
            );
            
            return weatherData;
        });
    }
    
    /**
     * Register calculator tool with expression evaluation.
     */
    private void registerCalculatorTool() {
        toolRegistry.registerTool("calculate", args -> {
            String expression = (String) args.get("expression");
            
            try {
                double result = evaluateExpression(expression);
                return new CalculationResult(expression, result, true, null);
            } catch (Exception e) {
                return new CalculationResult(expression, 0.0, false, e.getMessage());
            }
        });
    }
    
    /**
     * Register time tool for date/time information.
     */
    private void registerTimeTool() {
        toolRegistry.registerTool("get_time", args -> {
            String timezone = (String) args.getOrDefault("timezone", "UTC");
            String format = (String) args.getOrDefault("format", "ISO");
            
            LocalDateTime now = LocalDateTime.now();
            String formattedTime = switch (format.toLowerCase()) {
                case "iso" -> now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                case "human" -> now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' HH:mm:ss"));
                case "short" -> now.format(DateTimeFormatter.ofPattern("HH:mm"));
                case "date" -> now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                default -> now.toString();
            };
            
            return new TimeInfo(formattedTime, timezone, format, now.getDayOfWeek().toString());
        });
    }
    
    /**
     * Register database tool for query simulation.
     */
    private void registerDatabaseTool() {
        toolRegistry.registerTool("query_database", args -> {
            String query = (String) args.get("query");
            String table = (String) args.getOrDefault("table", "users");
            
            if (query.toLowerCase().contains("select")) {
                return simulateSelectQuery(table, query);
            } else if (query.toLowerCase().contains("insert")) {
                return simulateInsertQuery(table, query);
            } else {
                return new DatabaseResult(query, table, 0, "Unsupported operation", false);
            }
        });
    }
    
    // ========== FUNCTION DEFINITIONS ==========
    
    private FunctionDefinition createWeatherFunction() {
        return FunctionDefinition.builder("get_weather")
            .description("Get current weather information for a specific location")
            .stringParameter("location", "City and country, e.g. 'London, UK'", true)
            .enumParameter("units", "Temperature units", false, "celsius", "fahrenheit")
            .build();
    }
    
    private FunctionDefinition createCalculatorFunction() {
        return FunctionDefinition.builder("calculate")
            .description("Perform mathematical calculations")
            .stringParameter("expression", "Mathematical expression to evaluate", true)
            .build();
    }
    
    private FunctionDefinition createTimeFunction() {
        return FunctionDefinition.builder("get_time")
            .description("Get current date and time information")
            .stringParameter("timezone", "Timezone (mock implementation)", false)
            .enumParameter("format", "Output format", false, "ISO", "human", "short", "date")
            .build();
    }
    
    private FunctionDefinition createDatabaseFunction() {
        return FunctionDefinition.builder("query_database")
            .description("Execute database queries with mock responses")
            .stringParameter("query", "SQL query to execute", true)
            .stringParameter("table", "Table name", false)
            .build();
    }
    
    // ========== UTILITY METHODS ==========
    
    private void trimConversationHistory() {
        if (conversationHistory.size() > maxHistorySize) {
            // Keep system message and recent messages
            int removeCount = conversationHistory.size() - maxHistorySize + 1;
            for (int i = 0; i < removeCount && conversationHistory.size() > 1; i++) {
                // Don't remove system message (index 0)
                if (conversationHistory.size() > 1) {
                    conversationHistory.remove(1);
                }
            }
        }
    }
    
    private Map<String, Object> parseJsonArguments(String jsonArgs) {
        // Simple JSON parsing - in production use proper JSON library
        if (jsonArgs == null || jsonArgs.trim().isEmpty()) {
            return Map.of();
        }
        
        try {
            jsonArgs = jsonArgs.trim();
            if (!jsonArgs.startsWith("{") || !jsonArgs.endsWith("}")) {
                throw new IllegalArgumentException("Invalid JSON format");
            }
            
            Map<String, Object> result = new HashMap<>();
            String content = jsonArgs.substring(1, jsonArgs.length() - 1).trim();
            
            if (content.isEmpty()) {
                return result;
            }
            
            String[] pairs = content.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            for (String pair : pairs) {
                String[] keyValue = pair.split(":", 2);
                if (keyValue.length == 2) {
                    String key = keyValue[0].trim().replaceAll("^\"|\"$", "");
                    String value = keyValue[1].trim();
                    
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        result.put(key, value.substring(1, value.length() - 1));
                    } else if ("true".equals(value) || "false".equals(value)) {
                        result.put(key, Boolean.parseBoolean(value));
                    } else if (value.contains(".")) {
                        result.put(key, Double.parseDouble(value));
                    } else {
                        try {
                            result.put(key, Long.parseLong(value));
                        } catch (NumberFormatException e) {
                            result.put(key, value);
                        }
                    }
                }
            }
            
            return result;
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON arguments: " + jsonArgs, e);
        }
    }
    
    private double evaluateExpression(String expression) {
        // Simple expression evaluator - supports basic math operations
        expression = expression.replaceAll("\\s+", "");
        
        try {
            // Handle parentheses
            while (expression.contains("(")) {
                int start = expression.lastIndexOf("(");
                int end = expression.indexOf(")", start);
                if (end == -1) throw new IllegalArgumentException("Mismatched parentheses");
                
                String sub = expression.substring(start + 1, end);
                double subResult = evaluateExpression(sub);
                expression = expression.substring(0, start) + subResult + expression.substring(end + 1);
            }
            
            // Handle multiplication and division
            expression = processOperations(expression, "*", "/");
            
            // Handle addition and subtraction
            expression = processOperations(expression, "+", "-");
            
            return Double.parseDouble(expression);
            
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid expression: " + expression);
        }
    }
    
    private String processOperations(String expr, String... operators) {
        for (String op : operators) {
            while (expr.contains(op)) {
                int index = findOperatorIndex(expr, op);
                if (index == -1) break;
                
                double left = extractLeftNumber(expr, index);
                double right = extractRightNumber(expr, index + 1);
                
                double result = switch (op) {
                    case "+" -> left + right;
                    case "-" -> left - right;
                    case "*" -> left * right;
                    case "/" -> {
                        if (right == 0) throw new ArithmeticException("Division by zero");
                        yield left / right;
                    }
                    default -> throw new IllegalArgumentException("Unknown operator: " + op);
                };
                
                int leftStart = findLeftNumberStart(expr, index);
                int rightEnd = findRightNumberEnd(expr, index + 1);
                
                expr = expr.substring(0, leftStart) + result + expr.substring(rightEnd);
            }
        }
        return expr;
    }
    
    private int findOperatorIndex(String expr, String op) {
        for (int i = 1; i < expr.length(); i++) {
            if (expr.startsWith(op, i)) {
                return i;
            }
        }
        return -1;
    }
    
    private double extractLeftNumber(String expr, int opIndex) {
        int start = findLeftNumberStart(expr, opIndex);
        return Double.parseDouble(expr.substring(start, opIndex));
    }
    
    private double extractRightNumber(String expr, int startIndex) {
        int end = findRightNumberEnd(expr, startIndex);
        return Double.parseDouble(expr.substring(startIndex, end));
    }
    
    private int findLeftNumberStart(String expr, int opIndex) {
        int start = opIndex - 1;
        while (start > 0 && (Character.isDigit(expr.charAt(start - 1)) || expr.charAt(start - 1) == '.')) {
            start--;
        }
        return start;
    }
    
    private int findRightNumberEnd(String expr, int startIndex) {
        int end = startIndex;
        while (end < expr.length() && (Character.isDigit(expr.charAt(end)) || expr.charAt(end) == '.')) {
            end++;
        }
        return end;
    }
    
    private DatabaseResult simulateSelectQuery(String table, String query) {
        int recordCount = ThreadLocalRandom.current().nextInt(0, 5);
        List<Map<String, Object>> records = new ArrayList<>();
        
        for (int i = 1; i <= recordCount; i++) {
            records.add(Map.of(
                "id", i,
                "name", "User" + i,
                "email", "user" + i + "@example.com",
                "active", ThreadLocalRandom.current().nextBoolean()
            ));
        }
        
        return new DatabaseResult(query, table, recordCount, "Success", true, records);
    }
    
    private DatabaseResult simulateInsertQuery(String table, String query) {
        int insertedId = ThreadLocalRandom.current().nextInt(1000, 9999);
        return new DatabaseResult(query, table, 1, "Inserted with ID: " + insertedId, true);
    }
    
    // ========== DATA CLASSES ==========
    
    /**
     * Tool registry for managing function implementations.
     */
    private static class ToolRegistry {
        private final Map<String, ToolFunction> tools = new HashMap<>();
        
        public void registerTool(String name, ToolFunction function) {
            tools.put(name, function);
        }
        
        public CompletableFuture<Object> executeTool(String name, Map<String, Object> arguments) {
            ToolFunction function = tools.get(name);
            if (function == null) {
                return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unknown tool: " + name)
                );
            }
            
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return function.execute(arguments);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        
        public int getToolCount() {
            return tools.size();
        }
    }
    
    @FunctionalInterface
    private interface ToolFunction {
        Object execute(Map<String, Object> arguments) throws Exception;
    }
    
    public record WeatherData(
        String location,
        double temperature,
        String units,
        String condition,
        int humidity,
        double windSpeed,
        LocalDateTime timestamp
    ) {}
    
    public record CalculationResult(
        String expression,
        double result,
        boolean success,
        String error
    ) {}
    
    public record TimeInfo(
        String formattedTime,
        String timezone,
        String format,
        String dayOfWeek
    ) {}
    
    public record DatabaseResult(
        String query,
        String table,
        int recordsAffected,
        String message,
        boolean success,
        List<Map<String, Object>> records
    ) {
        public DatabaseResult(String query, String table, int recordsAffected, String message, boolean success) {
            this(query, table, recordsAffected, message, success, List.of());
        }
    }
    
    public record ToolExecutionRequest(
        String toolName,
        Map<String, Object> arguments
    ) {}
    
    public record ToolExecutionResponse(
        String toolName,
        Object result,
        boolean success,
        String error
    ) {}
    
    public record FunctionCallResult(
        String functionName,
        String result
    ) {}
}