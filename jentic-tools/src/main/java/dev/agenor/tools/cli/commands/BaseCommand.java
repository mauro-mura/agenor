package dev.agenor.tools.cli.commands;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.agenor.tools.cli.output.TableFormatter;
import picocli.CommandLine.Option;

/**
 * Base command providing common API client functionality.
 */
public abstract class BaseCommand implements Runnable {

    @Option(names = {"-u", "--url"},
            description = "API base URL",
            defaultValue = "http://localhost:8080",
            scope = picocli.CommandLine.ScopeType.INHERIT)
    protected String apiUrl;

    @Option(names = {"-v", "--verbose"},
            description = "Enable verbose output",
            scope = picocli.CommandLine.ScopeType.INHERIT)
    protected boolean verbose;

    protected static final ObjectMapper mapper = new ObjectMapper();
    protected static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    protected TableFormatter table = new TableFormatter();

    protected String getApiUrl() {
        return apiUrl;
    }

    protected boolean isVerbose() {
        return verbose;
    }

    protected JsonNode apiGet(String endpoint) throws Exception {
        String url = getApiUrl() + endpoint;
        if (isVerbose()) {
            System.out.println("GET " + url);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("API error: " + response.statusCode() +
                    " - " + response.body());
        }

        return mapper.readTree(response.body());
    }

    /**
     * Extract data field from API response wrapper.
     */
    protected JsonNode extractData(JsonNode response) {
        if (response.has("data")) {
            return response.get("data");
        }
        return response;
    }

    protected JsonNode apiPost(String endpoint) throws Exception {
        String url = getApiUrl() + endpoint;
        if (isVerbose()) {
            System.out.println("POST " + url);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("API error: " + response.statusCode() +
                    " - " + response.body());
        }

        return mapper.readTree(response.body());
    }

    protected void printError(String message) {
        System.err.println("Error: " + message);
    }

    protected void printSuccess(String message) {
        System.out.println("✓ " + message);
    }
}
