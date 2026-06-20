package com.mcadmin.plugin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class McAdminPlugin extends JavaPlugin {

    private HttpServer server;
    private String apiKey;
    private int port;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.apiKey = getConfig().getString("api-key", "changeme");
        this.port = getConfig().getInt("port", 8080);

        if (this.apiKey.equals("changeme")) {
            getLogger().warning("============================================");
            getLogger().warning("You are using the DEFAULT API key!");
            getLogger().warning("Edit plugins/McAdminPlugin/config.yml and set");
            getLogger().warning("a real api-key, then restart the server.");
            getLogger().warning("============================================");
        }

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/players", new PlayersHandler());
            server.createContext("/kick", new KickHandler());
            server.createContext("/ban", new BanHandler());
            server.createContext("/warn", new WarnHandler());
            server.createContext("/ping", new PingHandler());
            server.setExecutor(null);
            server.start();
            getLogger().info("McAdminPlugin HTTP API listening on port " + port);
        } catch (IOException e) {
            getLogger().severe("Failed to start HTTP API server: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (server != null) {
            server.stop(0);
            getLogger().info("McAdminPlugin HTTP API stopped.");
        }
    }

    // ---------- Helpers ----------

    private boolean isAuthorized(HttpExchange exchange) {
        List<String> headers = exchange.getRequestHeaders().get("X-Api-Key");
        if (headers == null || headers.isEmpty()) return false;
        return headers.get(0).equals(apiKey);
    }

    private void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendUnauthorized(HttpExchange exchange) throws IOException {
        sendJson(exchange, 401, "{\"error\":\"unauthorized\"}");
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> result = new java.util.HashMap<>();
        if (query == null) return result;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                result.put(
                    java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8)
                );
            }
        }
        return result;
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Runs a command on the main server thread (required for Bukkit API calls
     * and for dispatching commands like LiteBan's /ban, /warn etc.)
     * and waits for it to complete before returning.
     */
    private void runSyncAndWait(Runnable task) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                task.run();
            } finally {
                future.complete(null);
            }
        });
        try {
            future.get();
        } catch (Exception e) {
            getLogger().warning("Error running sync task: " + e.getMessage());
        }
    }

    // ---------- Handlers ----------

    private class PingHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isAuthorized(exchange)) { sendUnauthorized(exchange); return; }
            sendJson(exchange, 200, "{\"status\":\"ok\"}");
        }
    }

    private class PlayersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isAuthorized(exchange)) { sendUnauthorized(exchange); return; }

            final List<String> names = new ArrayList<>();
            final int[] maxPlayers = new int[1];

            runSyncAndWait(() -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                maxPlayers[0] = Bukkit.getMaxPlayers();
            });

            StringBuilder json = new StringBuilder();
            json.append("{\"online\":").append(names.size())
                .append(",\"max\":").append(maxPlayers[0])
                .append(",\"players\":[");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) json.append(",");
                json.append("\"").append(escapeJson(names.get(i))).append("\"");
            }
            json.append("]}");

            sendJson(exchange, 200, json.toString());
        }
    }

    private class KickHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isAuthorized(exchange)) { sendUnauthorized(exchange); return; }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"use POST\"}");
                return;
            }

            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String player = params.get("player");
            String reason = params.getOrDefault("reason", "Kicked by an admin");

            if (player == null || player.isEmpty()) {
                sendJson(exchange, 400, "{\"error\":\"missing player parameter\"}");
                return;
            }

            final boolean[] found = {false};
            runSyncAndWait(() -> {
                Player target = Bukkit.getPlayerExact(player);
                if (target != null) {
                    target.kick(net.kyori.adventure.text.Component.text(reason));
                    found[0] = true;
                }
            });

            if (found[0]) {
                sendJson(exchange, 200, "{\"success\":true,\"player\":\"" + escapeJson(player) + "\"}");
            } else {
                sendJson(exchange, 404, "{\"error\":\"player not online\"}");
            }
        }
    }

    private class BanHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isAuthorized(exchange)) { sendUnauthorized(exchange); return; }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"use POST\"}");
                return;
            }

            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String player = params.get("player");
            String reason = params.getOrDefault("reason", "Banned by an admin");
            String duration = params.get("duration"); // e.g. "7d", "permanent" - optional, LiteBan syntax

            if (player == null || player.isEmpty()) {
                sendJson(exchange, 400, "{\"error\":\"missing player parameter\"}");
                return;
            }

            // Build a LiteBan-style command: /ban <player> [duration] <reason>
            String command;
            if (duration != null && !duration.isEmpty()) {
                command = "ban " + player + " " + duration + " " + reason;
            } else {
                command = "ban " + player + " " + reason;
            }

            runSyncAndWait(() ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
            );

            sendJson(exchange, 200, "{\"success\":true,\"player\":\"" + escapeJson(player) + "\"}");
        }
    }

    private class WarnHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isAuthorized(exchange)) { sendUnauthorized(exchange); return; }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"use POST\"}");
                return;
            }

            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String player = params.get("player");
            String reason = params.getOrDefault("reason", "Warned by an admin");

            if (player == null || player.isEmpty()) {
                sendJson(exchange, 400, "{\"error\":\"missing player parameter\"}");
                return;
            }

            // LiteBan-style command: /warn <player> <reason>
            String command = "warn " + player + " " + reason;

            runSyncAndWait(() ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
            );

            sendJson(exchange, 200, "{\"success\":true,\"player\":\"" + escapeJson(player) + "\"}");
        }
    }
}
