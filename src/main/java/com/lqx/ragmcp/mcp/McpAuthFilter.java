package com.lqx.ragmcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lqx.ragmcp.api.error.ApiError;
import com.lqx.ragmcp.api.error.ErrorCode;
import com.lqx.ragmcp.api.error.TraceIdFilter;
import com.lqx.ragmcp.config.McpProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP gate for {@code /mcp}.
 *
 * <ul>
 *   <li>Requires {@code Authorization: Bearer <MCP_AUTH_TOKEN>} on POST/GET/DELETE. Missing,
 *       malformed or wrong tokens get HTTP 401 with the unified {@code ERR-MCP_UNAUTHORIZED}
 *       body. An unconfigured token fails closed (401) without blocking startup.</li>
 *   <li>Rejects {@code tools/call} for names outside {@code search_knowledge}/{@code get_document}
 *       with JSON-RPC {@code -32601}. SDK 0.18.3 maps an unknown tool to {@code -32602} internally;
 *       the contract for this service requires {@code -32601}, so the gate answers before the
 *       transport sees the call. Known tools still go through the SDK schema/session pipeline.</li>
 * </ul>
 *
 * <p>The bearer token is never logged. Unknown-tool calls are audited with only the mandate id
 * and a SHA-256 of the arguments.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class McpAuthFilter extends OncePerRequestFilter {

    private static final int MAX_GUARD_BODY_BYTES = 1_048_576;
    private static final int JSONRPC_METHOD_NOT_FOUND = -32601;
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    private final McpProperties properties;
    private final ObjectMapper objectMapper;
    private final McpAuditService auditService;
    private final String endpoint;

    public McpAuthFilter(McpProperties properties,
                         ObjectMapper objectMapper,
                         McpAuditService auditService,
                         @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}")
                         String endpoint) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.endpoint = normalizeEndpoint(endpoint);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !endpoint.equals(requestPath(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String suppliedToken = McpAuth.tokenFromHeader(request.getHeader(McpAuth.HEADER));
        if (!McpAuth.matches(properties.getAuthToken(), suppliedToken)) {
            writeUnauthorized(response);
            return;
        }

        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        byte[] body = request.getInputStream().readAllBytes();
        HttpServletRequest wrapped = new CachedBodyRequest(request, body);
        if (body.length > 0 && body.length <= MAX_GUARD_BODY_BYTES
                && rejectUnknownTool(body, response)) {
            return;
        }
        filterChain.doFilter(wrapped, response);
    }

    /** @return true when the request was answered with a JSON-RPC error. */
    private boolean rejectUnknownTool(byte[] body, HttpServletResponse response) throws IOException {
        final JsonNode message;
        try {
            message = objectMapper.readTree(body);
        } catch (IOException ex) {
            // Not valid JSON: let the MCP transport produce the protocol error.
            return false;
        }
        if (message == null || !message.isObject()
                || !"tools/call".equals(message.path("method").asText(null))) {
            return false;
        }
        JsonNode nameNode = message.path("params").path("name");
        if (!nameNode.isTextual()) {
            // Missing/ill-typed name is a malformed parameter and stays -32602 downstream.
            return false;
        }
        String toolName = nameNode.asText();
        if (McpToolService.TOOL_SEARCH.equals(toolName)
                || McpToolService.TOOL_DOCUMENT.equals(toolName)) {
            return false;
        }

        long startedAt = System.nanoTime();
        auditUnknownTool(message, toolName, startedAt);

        ObjectNode responseBody = objectMapper.createObjectNode();
        responseBody.put("jsonrpc", "2.0");
        JsonNode id = message.get("id");
        if (id == null) {
            responseBody.putNull("id");
        } else {
            responseBody.set("id", id);
        }
        ObjectNode error = responseBody.putObject("error");
        error.put("code", JSONRPC_METHOD_NOT_FOUND);
        error.put("message", "Method not found: " + toolName);
        writeJson(response, HttpServletResponse.SC_OK, responseBody);
        return true;
    }

    private void auditUnknownTool(JsonNode message, String toolName, long startedAt) {
        try {
            JsonNode argumentsNode = message.path("params").path("arguments");
            Map<String, Object> arguments = new LinkedHashMap<>();
            if (argumentsNode.isObject()) {
                arguments = objectMapper.convertValue(argumentsNode,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
            }
            UUID mandateId = parseMandateId(arguments.get("mandateId"));
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
            auditService.record(toolName, null, mandateId, auditService.argsHash(arguments),
                    McpAuditEntry.STATUS_ERROR, String.valueOf(JSONRPC_METHOD_NOT_FOUND), latencyMs);
        } catch (RuntimeException ex) {
            // Never let audit bookkeeping turn a protocol rejection into a 500.
            auditService.record(toolName, null, null, auditService.argsHash(Map.of()),
                    McpAuditEntry.STATUS_ERROR, String.valueOf(JSONRPC_METHOD_NOT_FOUND), 0L);
        }
    }

    private static UUID parseMandateId(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer realm=\"mcp\"");
        ApiError error = ApiError.of(ErrorCode.MCP_UNAUTHORIZED,
                "MCP token 缺失或错误", currentTraceId(), null);
        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, error);
    }

    private void writeJson(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), body);
    }

    private static String currentTraceId() {
        String current = MDC.get(TraceIdFilter.MDC_KEY);
        return current != null ? current
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String normalizeEndpoint(String raw) {
        String value = raw == null || raw.isBlank() ? "/mcp" : raw.trim();
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String requestPath(HttpServletRequest request) {
        // Decoded path (context path removed, semicolon content removed) so an encoded spelling
        // such as /m%63p cannot bypass the Bearer gate.
        String path = PATH_HELPER.getPathWithinApplication(request);
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /** Buffers the request body so the MCP transport can still read it after the guard. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buffer = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return buffer.read();
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    return buffer.read(target, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return buffer.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // Synchronous MVC request body; no async listener needed.
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
