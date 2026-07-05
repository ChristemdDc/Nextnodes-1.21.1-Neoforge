package com.nextnodes.permissions.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nextnodes.permissions.AuditLog;
import com.nextnodes.permissions.CommandCatalog.CommandInfo;
import com.nextnodes.permissions.PermissionModels;
import com.nextnodes.permissions.PermissionModels.PermissionData;
import com.nextnodes.permissions.PermissionModels.Rank;
import com.nextnodes.permissions.PermissionModels.UserEntry;
import com.nextnodes.permissions.PermissionStore;
import com.nextnodes.permissions.RankHistoryLog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bson.Document;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
    private volatile long sessionDurationMs = 15L * 60L * 1000L;
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

    private volatile String ip = "localhost";
    private HttpServer server;
    private volatile String token;
    private volatile String password;
    private volatile long sessionExpiresAt;
    private ScheduledFuture<?> shutdownTask;
    private Runnable onSessionExpired;
    private AuditLog auditLog;
    private RankHistoryLog historyLog;
    private com.nextnodes.permissions.ban.BanStore banStore;

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
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
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
        return "http://" + this.ip + ":" + this.port + "/";
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

    public void setSessionDurationMinutes(int minutes) {
        if (minutes > 0) {
            this.sessionDurationMs = minutes * 60_000L;
        }
    }

    public void setAuditLog(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    public void setRankHistoryLog(RankHistoryLog historyLog) {
        this.historyLog = historyLog;
    }

    public void setBanStore(com.nextnodes.permissions.ban.BanStore banStore) {
        this.banStore = banStore;
    }

    public void setServerIp(String serverIp) {
        if (serverIp == null || serverIp.isBlank() || serverIp.equals("0.0.0.0")
                || serverIp.equals("127.0.0.1") || serverIp.equalsIgnoreCase("localhost")) {
            // No usable host was given (e.g. server-ip is empty so getLocalIp() returns loopback).
            // Guess from the local interfaces immediately, then refine with the true public IP off-thread.
            this.ip = detectNetworkIp();
            refinePublicIpAsync();
        } else {
            this.ip = serverIp; // explicit host (config webHost / -Dnextnodes.web.host): trust it as-is
        }
    }

    private static String detectNetworkIp() {
        String privateFallback = null;
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            if (ifaces != null) {
                while (ifaces.hasMoreElements()) {
                    java.net.NetworkInterface iface = ifaces.nextElement();
                    if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) continue;
                    java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        java.net.InetAddress addr = addrs.nextElement();
                        if (addr.isLoopbackAddress() || !(addr instanceof java.net.Inet4Address)) continue;
                        if (addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
                            if (privateFallback == null) privateFallback = addr.getHostAddress();
                            continue; // prefer a public address if the host has one bound directly
                        }
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return privateFallback != null ? privateFallback : "localhost";
    }

    /** Looks up the true public IP from an external echo service off-thread, then updates {@link #ip}. */
    private void refinePublicIpAsync() {
        Thread t = new Thread(() -> {
            String pub = queryPublicIp();
            if (pub != null) {
                this.ip = pub;
            }
        }, "nextnodes-ip-detect");
        t.setDaemon(true);
        t.start();
    }

    private static String queryPublicIp() {
        for (String service : new String[]{"https://checkip.amazonaws.com", "https://api.ipify.org"}) {
            try {
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) java.net.URI.create(service).toURL().openConnection();
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
                conn.setRequestProperty("User-Agent", "NextNodes");
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    if (line != null && isIpv4(line.trim())) {
                        return line.trim();
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static boolean isIpv4(String value) {
        String[] parts = value.split("\\.");
        if (parts.length != 4) return false;
        for (String part : parts) {
            try {
                int n = Integer.parseInt(part);
                if (n < 0 || n > 255) return false;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        stop();
        this.scheduler.shutdownNow();
    }

    private void scheduleShutdown() {
        cancelShutdownTask();
        this.sessionExpiresAt = System.currentTimeMillis() + this.sessionDurationMs;
        this.shutdownTask = this.scheduler.schedule(() -> {
            broadcastSessionExpired();
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { return; }
            stop();
            Runnable callback = this.onSessionExpired;
            if (callback != null) callback.run();
        }, this.sessionDurationMs, TimeUnit.MILLISECONDS);
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
                String q = exchange.getRequestURI().getQuery();
                String keyParam = extractParam(q, "key");
                if (keyParam != null && keyParam.equals(this.password) && this.token != null) {
                    String html = WebAssets.indexHtml().replace("</head>",
                            "<script>localStorage.setItem('nn_token','" + this.token + "');</script></head>");
                    send(exchange, 200, "text/html; charset=utf-8", html);
                } else {
                    send(exchange, 200, "text/html; charset=utf-8", WebAssets.indexHtml());
                }
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
                response.addProperty("totalMs", this.sessionDurationMs);
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
                    if (this.auditLog != null) this.auditLog.log("web-panel", "web-panel", "rank.save", "rank", rank.name, "");
                    sendJson(exchange, 200, ok());
                    return;
                }
                if (method.equals("DELETE")) {
                    this.store.deleteRank(name);
                    if (this.auditLog != null) this.auditLog.log("web-panel", "web-panel", "rank.delete", "rank", name, "");
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
                    if (this.auditLog != null) this.auditLog.log("web-panel", "web-panel", "user.save", "user", user.uuid, "");
                    sendJson(exchange, 200, ok());
                    return;
                }
                if (method.equals("DELETE")) {
                    this.store.deleteUser(uuid);
                    if (this.auditLog != null) this.auditLog.log("web-panel", "web-panel", "user.delete", "user", uuid, "");
                    sendJson(exchange, 200, ok());
                    return;
                }
            }
            // --- export / import ---
            if (path.equals("/api/export") && method.equals("GET")) {
                String json = this.store.snapshotJson();
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=nextnodes-export.json");
                send(exchange, 200, "application/json; charset=utf-8", json);
                if (this.auditLog != null) this.auditLog.log("web-panel", "web-panel", "export", "", "", "");
                return;
            }
            if (path.equals("/api/import") && method.equals("POST")) {
                String body = readBody(exchange);
                PermissionData pd = GSON.fromJson(body, PermissionData.class);
                if (pd == null) { sendJson(exchange, 400, error("JSON inv\u00e1lido")); return; }
                this.store.importAll(pd);
                if (this.auditLog != null) this.auditLog.log("web-panel", "web-panel", "import", "", "", "ranks=" + pd.ranks.size() + " users=" + pd.users.size());
                sendJson(exchange, 200, ok());
                return;
            }
            // --- audit log ---
            if (path.equals("/api/audit") && method.equals("GET")) {
                int limit = parseQueryInt(exchange, "limit", 100);
                JsonArray arr = new JsonArray();
                if (this.auditLog != null) {
                    for (Document doc : this.auditLog.recent(limit)) arr.add(docToJson(doc));
                }
                send(exchange, 200, "application/json; charset=utf-8", GSON.toJson(arr));
                return;
            }
            // --- rank history ---
            if (path.startsWith("/api/history/") && method.equals("GET")) {
                String uuid = decodeSegment(path.substring("/api/history/".length()));
                int limit = parseQueryInt(exchange, "limit", 50);
                JsonArray arr = new JsonArray();
                if (this.historyLog != null) {
                    for (Document doc : this.historyLog.forPlayer(uuid, limit)) arr.add(docToJson(doc));
                }
                send(exchange, 200, "application/json; charset=utf-8", GSON.toJson(arr));
                return;
            }
            // --- tab list settings ---
            if (path.equals("/api/settings/tab") && method.equals("GET")) {
                sendJson(exchange, 200, GSON.toJsonTree(this.store.getTabSettings()).getAsJsonObject());
                return;
            }
            if (path.equals("/api/settings/tab") && method.equals("PUT")) {
                PermissionModels.TabSettings ts = GSON.fromJson(readBody(exchange), PermissionModels.TabSettings.class);
                if (ts == null) { sendJson(exchange, 400, error("JSON inválido")); return; }
                this.store.saveTabSettings(ts);
                if (this.auditLog != null) this.auditLog.log("web-panel", "web-panel", "tabsettings.save", "", "", "showPing=" + ts.showPing);
                sendJson(exchange, 200, ok());
                return;
            }
            // --- API key management ---
            if (path.equals("/api/apikey") && method.equals("GET")) {
                String key = this.store.getApiKey();
                JsonObject resp = new JsonObject();
                if (key == null || key.isBlank()) {
                    resp.addProperty("apiKey", (String) null);
                } else {
                    String masked = key.length() > 8 ? key.substring(0, 8) + "***" : "***";
                    resp.addProperty("apiKey", masked);
                }
                sendJson(exchange, 200, resp);
                return;
            }
            if (path.equals("/api/apikey/regenerate") && method.equals("POST")) {
                String newKey = newToken();
                this.store.setApiKey(newKey);
                if (this.auditLog != null) this.auditLog.log("web-panel", "web-panel", "apikey.regenerate", "", "", "");
                JsonObject resp = new JsonObject();
                resp.addProperty("apiKey", newKey);
                sendJson(exchange, 200, resp);
                return;
            }
            // --- bans ---
            if (path.equals("/api/bans") && method.equals("GET")) {
                handleBansList(exchange);
                return;
            }
            if (path.equals("/api/bans") && method.equals("POST")) {
                handleBanCreate(exchange);
                return;
            }
            if (path.startsWith("/api/bans/") && method.equals("DELETE")) {
                handleBanDelete(exchange, path.substring("/api/bans/".length()));
                return;
            }
            if (path.equals("/api/alts") && method.equals("GET")) {
                String ip = extractParam(exchange.getRequestURI().getQuery(), "ip");
                handleAlts(exchange, ip);
                return;
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

    // --- bans ---

    private void handleBansList(HttpExchange exchange) throws IOException {
        if (this.banStore == null) { sendJson(exchange, 200, emptyBansPayload()); return; }
        long now = System.currentTimeMillis();
        JsonArray active = new JsonArray();
        for (var b : this.banStore.listActive(now)) active.add(banJson(b));
        JsonArray log = new JsonArray();
        for (var e : this.banStore.listLog(200)) log.add(logJson(e));
        JsonObject out = new JsonObject();
        out.add("active", active);
        out.add("log", log);
        sendJson(exchange, 200, out);
    }

    private void handleBanCreate(HttpExchange exchange) throws IOException {
        if (this.banStore == null) { sendJson(exchange, 503, error("Baneos no disponibles")); return; }
        JsonObject body = readJson(exchange);
        String type = optString(body, "type", "account");
        String reason = optString(body, "reason", "Sin razón");
        String duration = optString(body, "duration", "perm");
        long now = System.currentTimeMillis();
        Long expiresAt;
        try {
            expiresAt = com.nextnodes.permissions.ban.BanDuration.expiresAt(duration, now);
        } catch (IllegalArgumentException ex) {
            sendJson(exchange, 400, error(ex.getMessage()));
            return;
        }
        if ("ip".equals(type)) {
            String ip = optString(body, "ip", "");
            if (ip.isBlank()) { sendJson(exchange, 400, error("ip requerida")); return; }
            if (!com.nextnodes.permissions.ban.IpFormat.looksLikeIp(ip)) { sendJson(exchange, 400, error("IP inválida: " + ip)); return; }
            this.banStore.banIp(ip, reason, "panel web", expiresAt, now);
        } else {
            String name = optString(body, "name", "");
            String uuid = optString(body, "uuid", null);
            if (name.isBlank() && uuid == null) { sendJson(exchange, 400, error("jugador requerido")); return; }
            this.banStore.banAccount(uuid, name, reason, "panel web", expiresAt, now);
        }
        broadcastChanged();
        if (this.auditLog != null) this.auditLog.log("web-panel", "web-panel", "ban.create", type, optString(body, "ip", optString(body, "name", "")), reason);
        sendJson(exchange, 200, ok());
    }

    private void handleBanDelete(HttpExchange exchange, String id) throws IOException {
        if (this.banStore == null) { sendJson(exchange, 503, error("Baneos no disponibles")); return; }
        boolean lifted = this.banStore.unbanById(decodeSegment(id), "panel web", System.currentTimeMillis());
        if (!lifted) { sendJson(exchange, 404, error("no encontrado")); return; }
        broadcastChanged();
        if (this.auditLog != null) this.auditLog.log("web-panel", "web-panel", "ban.delete", "", id, "");
        sendJson(exchange, 200, ok());
    }

    private void handleAlts(HttpExchange exchange, String ip) throws IOException {
        JsonArray arr = new JsonArray();
        if (ip != null && this.banStore != null) {
            for (var a : this.banStore.accountsForIp(ip)) {
                JsonObject o = new JsonObject();
                o.addProperty("uuid", a.uuid);
                o.addProperty("name", a.name);
                o.addProperty("lastIp", a.lastIp);
                arr.add(o);
            }
        }
        JsonObject out = new JsonObject();
        out.add("accounts", arr);
        sendJson(exchange, 200, out);
    }

    private static JsonObject emptyBansPayload() {
        JsonObject out = new JsonObject();
        out.add("active", new JsonArray());
        out.add("log", new JsonArray());
        return out;
    }

    private static JsonObject banJson(com.nextnodes.permissions.ban.BanEntry b) {
        JsonObject o = new JsonObject();
        o.addProperty("id", b.id);
        o.addProperty("type", b.type);
        o.addProperty("targetName", b.targetName);
        o.addProperty("ip", b.ip);
        o.addProperty("reason", b.reason);
        o.addProperty("issuer", b.issuer);
        o.addProperty("createdAt", b.createdAt);
        if (b.expiresAt != null) o.addProperty("expiresAt", b.expiresAt);
        return o;
    }

    private static JsonObject logJson(com.nextnodes.permissions.ban.BanLogEntry e) {
        JsonObject o = new JsonObject();
        o.addProperty("ts", e.ts);
        o.addProperty("action", e.action);
        o.addProperty("type", e.type);
        o.addProperty("target", e.target);
        o.addProperty("targetName", e.targetName);
        o.addProperty("ip", e.ip);
        o.addProperty("reason", e.reason);
        o.addProperty("issuer", e.issuer);
        return o;
    }

    private static String optString(JsonObject o, String key, String def) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
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
        if (auth != null && auth.startsWith("ApiKey ")) {
            String key = auth.substring(7).trim();
            String storedKey = this.store.getApiKey();
            if (storedKey != null && !storedKey.isBlank() && storedKey.equals(key)) return true;
        }
        String query = exchange.getRequestURI().getQuery();
        return query != null && query.contains("token=" + this.token);
    }

    private static JsonObject docToJson(Document doc) {
        JsonObject obj = new JsonObject();
        for (Map.Entry<String, Object> entry : doc.entrySet()) {
            String k = entry.getKey();
            Object v = entry.getValue();
            if (v instanceof String s) obj.addProperty(k, s);
            else if (v instanceof Number n) obj.addProperty(k, n.longValue());
            else if (v instanceof Boolean b) obj.addProperty(k, b);
            else if (v != null) obj.addProperty(k, v.toString());
        }
        return obj;
    }

    private static int parseQueryInt(HttpExchange exchange, String key, int defaultVal) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return defaultVal;
        for (String part : query.split("&")) {
            if (part.startsWith(key + "=")) {
                try { return Integer.parseInt(part.substring(key.length() + 1)); }
                catch (NumberFormatException ignored) {}
            }
        }
        return defaultVal;
    }

    private static String extractParam(String query, String key) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            if (part.startsWith(key + "=")) {
                return part.substring(key.length() + 1);
            }
        }
        return null;
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
