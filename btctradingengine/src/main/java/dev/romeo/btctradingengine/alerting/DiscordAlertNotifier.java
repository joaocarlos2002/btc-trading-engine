package dev.romeo.btctradingengine.alerting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Envia alertas para um webhook do Discord. Se {@code webhookUrl} estiver em branco,
 * o alerta e apenas logado - permite ligar o canal depois sem mudar codigo, so a config.
 * O envio e assincrono e best-effort: uma falha de entrega nunca propaga para o chamador.
 */
public class DiscordAlertNotifier implements AlertNotifier {
    private static final Logger logger = LoggerFactory.getLogger(DiscordAlertNotifier.class);
    private static final int DISCORD_CONTENT_LIMIT = 2000;

    private final String webhookUrl;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public DiscordAlertNotifier(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    @Override
    public void alert(String message) {
        logger.error("ALERT: {}", message);

        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        try {
            String payload = mapper.writeValueAsString(Map.of("content", truncate(message)));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(e -> {
                        logger.warn("Failed to deliver Discord alert: {}", e.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            logger.warn("Failed to build Discord alert payload: {}", e.getMessage());
        }
    }

    private String truncate(String message) {
        return message.length() > DISCORD_CONTENT_LIMIT - 20
                ? message.substring(0, DISCORD_CONTENT_LIMIT - 20) + "... (truncated)"
                : message;
    }
}
