package dev.romeo.btctradingengine.dashboard;

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
        }, "/ws/live").setAllowedOriginPatterns("*");
    }
}
