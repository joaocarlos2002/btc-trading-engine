package dev.romeo.btctradingengine.alerting;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class DiscordAlertNotifierTest {

    private HttpServer server;

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void postsMessageAsDiscordContentPayload() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        CompletableFuture<Void> received = new CompletableFuture<>();

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/webhook", exchange -> {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            receivedBody.set(new String(bytes, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            received.complete(null);
        });
        server.start();

        String webhookUrl = "http://localhost:" + server.getAddress().getPort() + "/webhook";
        DiscordAlertNotifier notifier = new DiscordAlertNotifier(webhookUrl);

        notifier.alert("something broke");
        received.get(5, TimeUnit.SECONDS);

        assertTrue(receivedBody.get().contains("\"content\""));
        assertTrue(receivedBody.get().contains("something broke"));
    }

    @Test
    public void doesNotThrowWhenWebhookUrlIsBlank() {
        DiscordAlertNotifier notifier = new DiscordAlertNotifier("");
        notifier.alert("no webhook configured, should just log");
    }

    @Test
    public void doesNotThrowWhenWebhookIsUnreachable() {
        DiscordAlertNotifier notifier = new DiscordAlertNotifier("http://localhost:1/unreachable");
        notifier.alert("delivery should fail silently");
    }
}
