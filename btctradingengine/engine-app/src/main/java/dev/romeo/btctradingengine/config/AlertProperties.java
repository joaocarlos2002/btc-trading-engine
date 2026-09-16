package dev.romeo.btctradingengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

/**
 * {@code alert.*}: the Discord webhook for critical alerts (BTC_ENGINE_DISCORD_WEBHOOK_URL); blank
 * disables them. The URL embeds a token, so {@link #toString()} masks it.
 */
@ConfigurationProperties("alert")
public record AlertProperties(
        @Name("discord.webhook.url") String discordWebhookUrl
) {
    public AlertProperties {
        discordWebhookUrl = discordWebhookUrl == null ? "" : discordWebhookUrl;
    }

    @Override
    public String toString() {
        return "AlertProperties[discordWebhookUrl=" + BinanceProperties.mask(discordWebhookUrl) + "]";
    }
}
