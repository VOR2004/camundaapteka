package ru.itis;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Локальный мок API для Camunda. Запуск и остановка кнопками Run / Stop в IDE. */
public class Main {
    private static final Map<String, Order> orders = new ConcurrentHashMap<>();
    enum Status { PAID, ASSEMBLING, READY, DELIVERED, CANCELLED }

    record Order(String orderId, Status status, Instant updatedAt) {
        String json() {
            // orderId допускает только безопасные символы; status ограничен enum.
            return "{\"orderId\":\"" + orderId + "\",\"status\":\"" + status
                    + "\",\"updatedAt\":\"" + updatedAt + "\"}";
        }
    }

    public static void main(String[] args) throws IOException {
        int port = args.length == 0 ? 3001 : Integer.parseInt(args[0]);
        orders.put("1001", new Order("1001", Status.PAID, Instant.now()));
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (BindException e) {
            System.err.println("Port" + port + " already in use.");
            System.exit(1);
            return;
        }
        server.createContext("/", Main::handle);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        server.start();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (method.equals("GET") && path.equals("/")) {
                staticFile(exchange, "index.html", "text/html");
            } else if (method.equals("GET") && path.equals("/app.js")) {
                staticFile(exchange, "app.js", "text/javascript");
            } else if (method.equals("GET") && path.equals("/styles.css")) {
                staticFile(exchange, "styles.css", "text/css");
            } else if (method.equals("GET") && path.equals("/orders")) {
                String body = orders.values().stream().sorted(Comparator.comparing(a -> a.orderId))
                        .map(Order::json).collect(Collectors.joining(",", "[", "]"));
                json(exchange, 200, body);
            } else if (method.equals("GET") && path.equals("/health")) {
                json(exchange, 200, "{\"status\":\"UP\",\"runtime\":\"java\"}");
            } else if (method.equals("GET") && path.matches("/orders/[A-Za-z0-9_-]{1,64}")) {
                Order order = orders.get(path.substring(8));
                json(exchange, order == null ? 404 : 200,
                        order == null ? "{\"error\":\"ORDER_NOT_FOUND\"}" : order.json());
            } else if (method.equals("POST") && path.equals("/set")) {
                update(exchange);
            } else {
                json(exchange, 404, "{\"error\":\"NOT_FOUND\"}");
            }
        }
    }

    private static void update(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(4097);
        if (bytes.length > 4096) {
            json(exchange, 413, "{\"error\":\"TOO_LARGE\"}");
            return;
        }
        try {
            Map<String, String> fields = Arrays.stream(new String(bytes, StandardCharsets.UTF_8).split("&"))
                    .map(s -> s.split("=", 2))
                    .collect(Collectors.toMap(p -> decode(p[0]),
                            p -> p.length == 2 ? decode(p[1]) : "", (first, last) -> last));
            String id = fields.getOrDefault("orderId", "");
            if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException();
            Status status = Status.valueOf(fields.getOrDefault("status", ""));
            orders.put(id, new Order(id, status, Instant.now()));
        } catch (IllegalArgumentException e) {
            json(exchange, 400, "{\"error\":\"INVALID_INPUT\"}");
            return;
        }
        exchange.getResponseHeaders().set("Location", "/");
        exchange.sendResponseHeaders(303, -1);
    }

    private static String decode(String text) {
        return URLDecoder.decode(text, StandardCharsets.UTF_8);
    }

    private static void json(HttpExchange exchange, int code, String body) throws IOException {
        send(exchange, code, "application/json", body);
    }

    private static void send(HttpExchange exchange, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void staticFile(HttpExchange exchange, String name, String contentType) throws IOException {
        try (var stream = Main.class.getResourceAsStream("/static/" + name)) {
            if (stream == null) {
                json(exchange, 500, "{\"error\":\"STATIC_RESOURCE_MISSING\"}");
                return;
            }
            send(exchange, 200, contentType, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}