package dev.romeo.btctradingengine.dashboard;

import dev.romeo.btctradingengine.model.NormalizedPriceEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardStateSerializationTest {

    private static final Instant EVENT = Instant.parse("2025-09-14T12:00:00.123Z");
    private static final Instant RECEIPT = Instant.parse("2025-09-14T12:00:00.456Z");

    private static String broadcastPrice(DashboardState state) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        state.addSession(session);
        state.onEvent(new NormalizedPriceEvent("BTCUSDT", new BigDecimal("65000"), EVENT, RECEIPT));

        ArgumentCaptor<TextMessage> message = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, timeout(2000)).sendMessage(message.capture());
        return message.getValue().getPayload();
    }

    private static void assertIsoTimestamps(String json) {
        assertTrue(json.contains("\"eventTimestamp\":\"2025-09-14T12:00:00.123Z\""), json);
        assertTrue(json.contains("\"receiptTimestamp\":\"2025-09-14T12:00:00.456Z\""), json);
    }

    /** A mapper that writes dates as timestamps (a plain builder, or spring.jackson overrides) is overridden. */
    @Test
    void timestampMapperStillWritesInstantsAsIso() throws Exception {
        try (DashboardState state = new DashboardState(new BigDecimal("100"),
                Jackson2ObjectMapperBuilder.json().build())) {
            assertIsoTimestamps(broadcastPrice(state));
        }
    }

    @Test
    void defaultObjectMapperWritesInstantsAsIso() throws Exception {
        try (DashboardState state = new DashboardState(new BigDecimal("100"))) {
            assertIsoTimestamps(broadcastPrice(state));
        }
    }
}
