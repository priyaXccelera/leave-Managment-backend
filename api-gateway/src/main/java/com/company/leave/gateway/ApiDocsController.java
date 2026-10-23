package com.company.leave.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import reactor.core.publisher.Mono;

/**
 * Serves the public OpenAPI contract (bundled from docs/openapi.yaml at build time)
 * and a Swagger UI page (assets from the swagger-ui webjar, no external network needed).
 * Public: no authentication.
 */
@RestController
public class ApiDocsController {

    private static final Logger log = LoggerFactory.getLogger(ApiDocsController.class);
    private static final String SPEC_PATH = "openapi/openapi.yaml";
    private static final String SWAGGER_VERSION = "5.17.14";

    private static final String UI_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <title>Employee Leave Management API - Swagger UI</title>
              <link rel="stylesheet" href="/webjars/swagger-ui/%1$s/swagger-ui.css">
            </head>
            <body>
              <div id="swagger-ui"></div>
              <script src="/webjars/swagger-ui/%1$s/swagger-ui-bundle.js"></script>
              <script>
                window.onload = function () {
                  window.ui = SwaggerUIBundle({
                    url: "/openapi.yaml",
                    dom_id: "#swagger-ui",
                    deepLinking: true
                  });
                };
              </script>
            </body>
            </html>
            """.formatted(SWAGGER_VERSION);

    private final ObjectMapper objectMapper;
    private final byte[] yaml;
    private final byte[] json;

    public ApiDocsController(ObjectMapper objectMapper) throws IOException {
        this.objectMapper = objectMapper;
        try (InputStream in = new ClassPathResource(SPEC_PATH).getInputStream()) {
            this.yaml = in.readAllBytes();
        }
        this.json = toJson(yaml);
    }

    private byte[] toJson(byte[] yamlBytes) throws IOException {
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(new String(yamlBytes, StandardCharsets.UTF_8));
        log.info("OpenAPI spec loaded from classpath:{}", SPEC_PATH);
        return objectMapper.writeValueAsBytes(parsed);
    }

    @GetMapping(value = {"/docs", "/docs/"}, produces = MediaType.TEXT_HTML_VALUE)
    public Mono<String> docs() {
        return Mono.just(UI_HTML);
    }

    @GetMapping(value = "/openapi.yaml", produces = "application/yaml")
    public Mono<byte[]> openApiYaml() {
        return Mono.just(yaml);
    }

    @GetMapping(value = {"/openapi.json", "/v3/api-docs"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<byte[]> openApiJson() {
        return Mono.just(json);
    }
}
