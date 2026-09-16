package dev.romeo.btctradingengine.dashboard;

import dev.romeo.btctradingengine.config.Config;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Configuration
@EnableWebSocket
public class DashboardWebSocketConfig implements WebSocketConfigurer {
    private final DashboardState state;

    public DashboardWebSocketConfig(DashboardState state) {
        this.state = state;
    }

    /**
     * Only the origins in {@code dashboard.allowed.origins} may open the live feed: it streams the
     * open position, the signals and the P&L, which a third-party site used to be able to read with
     * the previous wildcard (issue #66).
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(org.springframework.web.socket.WebSocketSession session) {
                state.addSession(session);
            }

            @Override
            public void afterConnectionClosed(org.springframework.web.socket.WebSocketSession session,
                                              org.springframework.web.socket.CloseStatus status) {
                state.removeSession(session);
            }
        }, "/ws/live").setAllowedOrigins(Config.getDashboardAllowedOrigins().toArray(new String[0]));
    }
}
