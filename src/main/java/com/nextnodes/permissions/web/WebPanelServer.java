package com.nextnodes.permissions.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.nextnodes.permissions.CommandCatalog.CommandInfo;
import com.nextnodes.permissions.PermissionModels.Rank;
import com.nextnodes.permissions.PermissionModels.UserEntry;
import com.nextnodes.permissions.PermissionStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class WebPanelServer implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final long SESSION_DURATION_MS = 15L * 60L * 1000L;
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOGIN_COOLDOWN_MS = 60_000L;

    private final PermissionStore store;
    private final int port;
    private final Supplier<List<CommandInfo>> commandSupplier;
    private final Set<OutputStream> eventStreams = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "nextnodes-session-timer");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger loginAttempts = new AtomicInteger(0);
    private final AtomicLong loginCooldownUntil = new AtomicLong(0);

    private HttpServer server;
    private String token;
    private String password;
    private volatile long sessionExpiresAt;
    private ScheduledFuture<?> shutdownTask;
    private Runnable onSessionExpired;

    public WebPanelServer(PermissionStore store, int port, Supplier<List<CommandInfo>> commandSupplier) {
        this.store = store;
        this.port = port;
        this.commandSupplier = commandSupplier;
        this.store.addChangeListener(this::broadcastChanged);
        this.store.addOnlineChangeListener(this::broadcastChanged);
    }

    public synchronized String start() throws IOException {
        if (this.server != null) {
            stop();
        }
        this.password = generatePassword();
        this.token = newToken();
        this.loginAttempts.set(0);
        this.loginCooldownUntil.set(0);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", this.port), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "nextnodes-web-" + r.hashCode());
            t.setDaemon(true);
            return t;
        }));
        this.server.start();
        scheduleShutdown();
        return this.password;
    }

    public synchronized void stop() {
        cancelShutdownTask();
        closeAllEventStreams();
        if (this.server != null) {
            this.server.stop(1);
            this.server = null;
        }
        this.token = null;
        this.password = null;
        this.sessionExpiresAt = 0;
    }

    public synchronized boolean isRunning() {
        return this.server != null;
    }

    public String url() {
        return "http://localhost:" + this.port + "/";
    }

    public long remainingMs() {
        long remaining = this.sessionExpiresAt - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    public synchronized void extendSession() {
        if (this.server == null) return;
        scheduleShutdown();
    }

    public void setOnSessionExpired(Runnable callback) {
        this.onSessionExpired = callback;
    }

    @Override
    public void close() {
        stop();
        this.scheduler.shutdownNow();
    }

    private void scheduleShutdown() {
        cancelShutdownTask();
        this.sessionExpiresAt = System.currentTimeMillis() + SESSION_DURATION_MS;
        this.shutdownTask = this.scheduler.schedule(() -> {
            broadcastSessionExpired();
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { return; }
            stop();
            Runnable callback = this.onSessionExpired;
            if (callback != null) callback.run();
        }, SESSION_DURATION_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelShutdownTask() {
        if (this.shutdownTask != null) {
            this.shutdownTask.cancel(false);
            this.shutdownTask = null;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        boolean closeExchange = true;
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/") || path.equals("/index.html")) {
                send(exchange, 200, "text/html; charset=utf-8", WebAssets.indexHtml());
                return;
            }
            if (path.equals("/web/styles.css")) {
                send(exchange, 200, "text/css; charset=utf-8", WebAssets.text("styles.css"));
                return;
            }
            if (path.equals("/web/app.js")) {
                send(exchange, 200, "application/javascript; charset=utf-8", WebAssets.text("app.js"));
                return;
            }
            if (path.equals("/api/login") && method.equals("POST")) {
                handleLogin(exchange);
                return;
            }
            if (path.equals("/api/events") && method.equals("GET")) {
                if (!isAuthorized(exchange)) {
                    sendJson(exchange, 401, error("unauthorized"));
                    return;
                }
                closeExchange = false;
                handleEvents(exchange);
                return;
            }
            if (!path.startsWith("/api/")) {
                sendJson(exchange, 404, error("not found"));
                return;
            }
            if (!isAuthorized(exchange)) {
                sendJson(exchange, 401, error("unauthorized"));
                return;
            }
            if (path.equals("/api/session") && method.equals("GET")) {
                JsonObject response = new JsonObject();
                response.addProperty("remainingMs", remainingMs());
                response.addProperty("totalMs", SESSION_DURATION_MS);
                sendJson(exchange, 200, response);
                return;
            }
            if (path.equals("/api/extend") && method.equals("POST")) {
                extendSession();
                JsonObject response = new JsonObject();
                response.addProperty("ok", true);
                response.addProperty("remainingMs", remainingMs());
                sendJson(exchange, 200, response);
                return;
            }
            if (path.equals("/api/state") && method.equals("GET")) {
                JsonObject state = GSON.fromJson(this.store.snapshotJson(), JsonObject.class);
                state.add("commands", GSON.toJsonTree(this.commandSupplier.get()));
                send(exchange, 200, "application/json; charset=utf-8", GSON.toJson(state));
                return;
            }
            if (path.startsWith("/api/ranks/")) {
                String name = decodeSegment(path.substring("/api/ranks/".length()));
                if (method.equals("PUT")) {
                    Rank rank = GSON.fromJson(readBody(exchange), Rank.class);
                    if (rank.name == null || rank.name.isBlank()) {
                        rank.name = name;
                    }
                    this.store.saveRank(rank);
                    sendJson(exchange, 200, ok());
                    return;
                }
                if (method.equals("DELETE")) {
                    this.store.deleteRank(name);
                    sendJson(exchange, 200, ok());
                    return;
                }
            }
            if (path.startsWith("/api/users/")) {
                String uuid = decodeSegment(path.substring("/api/users/".length()));
                if (method.equals("PUT")) {
                    UserEntry user = GSON.fromJson(readBody(exchange), UserEntry.class);
                    if (user.uuid == null || user.uuid.isBlank()) {
                        user.uuid = uuid;
                    }
                    this.store.saveUser(user);
                    sendJson(exchange, 200, ok());
                    return;
                }
                if (method.equals("DELETE")) {
                    this.store.deleteUser(uuid);
                    sendJson(exchange, 200, ok());
                    return;
                }
            }
            sendJson(exchange, 404, error("not found"));
        } catch (Exception ex) {
            sendJson(exchange, 500, error(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        } finally {
            if (closeExchange) {
                exchange.close();
            }
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        long now = System.currentTimeMillis();
        if (now < this.loginCooldownUntil.get()) {
            long waitSec = (this.loginCooldownUntil.get() - now) / 1000 + 1;
            sendJson(exchange, 429, error("Demasiados intentos. Espera " + waitSec + " segundos."));
            return;
        }
        JsonObject body = readJson(exchange);
        String attempt = body.has("password") ? body.get("password").getAsString() : "";
        if (this.password != null && this.password.equals(attempt)) {
            this.loginAttempts.set(0);
            JsonObject response = new JsonObject();
            response.addProperty("token", this.token);
            response.addProperty("remainingMs", remainingMs());
            sendJson(exchange, 200, response);
        } else {
            int attempts = this.loginAttempts.incrementAndGet();
            if (attempts >= MAX_LOGIN_ATTEMPTS) {
                this.loginCooldownUntil.set(now + LOGIN_COOLDOWN_MS);
                this.loginAttempts.set(0);
                sendJson(exchange, 429, error("Demasiados intentos. Bloqueado por 60 segundos."));
            } else {
                sendJson(exchange, 401, error("Contrase\u00f1a incorrecta (" + (MAX_LOGIN_ATTEMPTS - attempts) + " intentos restantes)"));
            }
        }
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        OutputStream body = exchange.getResponseBody();
        this.eventStreams.add(body);
        body.write("event: ready\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
        body.flush();
    }

    private void broadcastChanged() {
        broadcastEvent("changed", "{}");
    }

    private void broadcastSessionExpired() {
        broadcastEvent("session_expired", "{}");
    }

    private void broadcastEvent(String eventName, String data) {
        byte[] event = ("event: " + eventName + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
        for (OutputStream stream : this.eventStreams) {
            try {
                stream.write(event);
                stream.flush();
            } catch (IOException ex) {
                this.eventStreams.remove(stream);
                try { stream.close(); } catch (IOException ignored) { }
            }
        }
    }

    private void closeAllEventStreams() {
        for (OutputStream stream : this.eventStreams) {
            try { stream.close(); } catch (IOException ignored) { }
        }
        this.eventStreams.clear();
    }

    private boolean isAuthorized(HttpExchange exchange) {
        if (this.token == null) return false;
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.equals("Bearer " + this.token)) {
            return true;
        }
        String query = exchange.getRequestURI().getQuery();
        return query != null && query.contains("token=" + this.token);
    }

    private static JsonObject readJson(HttpExchange exchange) throws IOException {
        return GSON.fromJson(readBody(exchange), JsonObject.class);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange exchange, int status, JsonObject body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", GSON.toJson(body));
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static JsonObject ok() {
        JsonObject object = new JsonObject();
        object.addProperty("ok", true);
        return object;
    }

    private static JsonObject error(String message) {
        JsonObject object = new JsonObject();
        object.addProperty("ok", false);
        object.addProperty("error", message);
        return object;
    }

    private static String decodeSegment(String segment) {
        return java.net.URLDecoder.decode(segment, StandardCharsets.UTF_8);
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String generatePassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
