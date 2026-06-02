package dev.agenor.tools.console;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;

/**
 * Serves static resources (HTML, CSS, JS) from classpath.
 *
 * Resources are loaded from src/main/resources/webapp/
 */
public class StaticResourceHandler extends HttpServlet {

	@Serial
    private static final long serialVersionUID = -268207312584070204L;

	private static final Logger logger = LoggerFactory.getLogger(StaticResourceHandler.class);

    private static final String RESOURCE_BASE = "/webapp";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();

        // Default to index.html
        if (path == null || path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }

        logger.debug("Serving static resource: {}", path);

        String resourcePath = RESOURCE_BASE + path;

        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                logger.warn("Resource not found: {}", resourcePath);
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Resource not found: " + path);
                return;
            }

            // Set content type
            String contentType = getContentType(path);
            resp.setContentType(contentType);

            // Set caching headers
            resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            resp.setHeader("Pragma", "no-cache");
            resp.setHeader("Expires", "0");

            // Copy stream
            try (OutputStream os = resp.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }

            logger.debug("Served resource: {}", path);

        } catch (Exception e) {
            logger.error("Error serving resource: " + path, e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Error serving resource: " + e.getMessage());
        }
    }

    private String getContentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=UTF-8";
        } else if (path.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        } else if (path.endsWith(".js")) {
            return "application/javascript; charset=UTF-8";
        } else if (path.endsWith(".json")) {
            return "application/json; charset=UTF-8";
        } else if (path.endsWith(".png")) {
            return "image/png";
        } else if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (path.endsWith(".svg")) {
            return "image/svg+xml";
        } else if (path.endsWith(".ico")) {
            return "image/x-icon";
        } else {
            return "application/octet-stream";
        }
    }
}
