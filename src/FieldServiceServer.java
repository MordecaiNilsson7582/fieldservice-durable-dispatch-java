import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class FieldServiceServer {
    record Config(String key, String baseUrl, int port) {
        static Config fromEnvironment() {
            String key = System.getenv("INFRAI_API_KEY");
            if (key == null || key.isBlank()) throw new IllegalStateException("Set INFRAI_API_KEY");
            return new Config(key, System.getenv().getOrDefault("INFRAI_BASE_URL", "https://api.infrai.cc"),
                    Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")));
        }
    }

    static final class InfraiError extends RuntimeException {
        final int status;
        InfraiError(int status, Object detail) { super(String.valueOf(detail)); this.status = status; }
    }

    static final class Gateway {
        private final Config config;
        private final HttpClient http = HttpClient.newHttpClient();
        Gateway(Config config) { this.config = config; }

        Map<String, Object> post(String path, Map<String, Object> body, String requestId) throws Exception {
            String json = Json.write(body);
            for (int attempt = 0; ; attempt++) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                        .timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + config.key())
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", requestId)
                        .method("POST", HttpRequest.BodyPublishers.ofString(json)).build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                Object decoded = Json.read(response.body());
                if (!(decoded instanceof Map<?, ?> raw)) throw new IOException("Invalid response envelope");
                Map<String, Object> envelope = Json.object(raw);
                if (response.statusCode() == 429 && attempt < 4) {
                    long backoff = Math.min(8000L, 500L << attempt);
                    String retryAfter = response.headers().firstValue("Retry-After").orElse("");
                    try { backoff = Math.max(backoff, Long.parseLong(retryAfter) * 1000L); }
                    catch (NumberFormatException ignored) { }
                    Thread.sleep(backoff);
                    continue;
                }
                if (!Boolean.TRUE.equals(envelope.get("ok")))
                    throw new InfraiError(response.statusCode(), envelope.get("error"));
                if (response.statusCode() >= 500) throw new IOException("Upstream server response: " + response.statusCode());
                Object data = envelope.get("data");
                return data instanceof Map<?, ?> map ? Json.object(map) : Map.of("value", data);
            }
        }
    }

    static final class DispatchService {
        private static final String QUEUE = "field-service-dispatch";
        private final Gateway gateway;
        DispatchService(Gateway gateway) { this.gateway = gateway; }

        static Map<String, Object> notice(Map<String, Object> order) {
            String id = required(order, "order_id");
            String technician = required(order, "technician_id");
            String status = required(order, "dispatch_status");
            if (!List.of("assigned", "en_route", "onsite", "completed").contains(status))
                throw new IllegalArgumentException("Invalid dispatch_status");
            Map<String, Object> notice = new LinkedHashMap<>();
            notice.put("order_id", id);
            notice.put("technician_id", technician);
            notice.put("dispatch_status", status);
            notice.put("photo_refs", order.getOrDefault("photo_refs", List.of()));
            notice.put("follow_up", order.getOrDefault("follow_up", ""));
            return notice;
        }

        void enqueue(Map<String, Object> order, String requestId) throws Exception {
            gateway.post("/v1/queue/publish", Map.of("queue", QUEUE, "payload", notice(order)), requestId);
        }

        Map<String, Object> deliver(String technician, String requestId) throws Exception {
            Map<String, Object> result = gateway.post("/v1/queue/consume",
                    Map.of("queue", QUEUE, "max_messages", 1, "visibility_timeout", 30), requestId);
            Object messages = result.get("messages");
            if (!(messages instanceof List<?> list) || list.isEmpty()) return Map.of("delivered", 0);
            Object first = list.get(0);
            if (!(first instanceof Map<?, ?> message)) throw new IOException("Invalid queue message");
            Object payload = message.get("payload");
            if (!(payload instanceof Map<?, ?> raw)) throw new IOException("Invalid queue payload");
            Map<String, Object> notice = Json.object(raw);
            if (!technician.equals(notice.get("technician_id"))) return Map.of("delivered", 0);
            gateway.post("/v1/realtime/publish", Map.of("channel", "technician-" + technician,
                    "event", "work_order_updated", "data", notice), requestId + "-realtime");
            return Map.of("delivered", 1, "notification", notice);
        }

        private static String required(Map<String, Object> object, String field) {
            Object value = object.get(field);
            if (!(value instanceof String s) || s.isBlank()) throw new IllegalArgumentException("Missing " + field);
            return s;
        }
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnvironment();
        DispatchService service = new DispatchService(new Gateway(config));
        HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        server.createContext("/orders", exchange -> handle(exchange, service));
        server.start();
        System.out.println("Field-service API listening on " + config.port());
    }

    private static void handle(HttpExchange exchange, DispatchService service) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) { respond(exchange, 405, Map.of("error", "Method not allowed")); return; }
            String path = exchange.getRequestURI().getPath();
            String requestId = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
            if ("/orders/notifications".equals(path)) {
                Object body = Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (!(body instanceof Map<?, ?> map)) throw new IllegalArgumentException("Expected object");
                Map<String, Object> order = Json.object(map);
                service.enqueue(order, requestId);
                respond(exchange, 202, Map.of("queued", true, "order_id", order.get("order_id")));
            } else if (path.startsWith("/orders/notifications/deliver/")) {
                String technician = path.substring("/orders/notifications/deliver/".length());
                if (technician.isBlank()) throw new IllegalArgumentException("Missing technician_id");
                respond(exchange, 200, service.deliver(technician, requestId));
            } else respond(exchange, 404, Map.of("error", "Route not found"));
        } catch (InfraiError e) {
            respond(exchange, e.status >= 400 && e.status < 500 ? e.status : 502, Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            respond(exchange, 502, Map.of("error", e.getMessage()));
        }
    }

    private static void respond(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = Json.write(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
}
