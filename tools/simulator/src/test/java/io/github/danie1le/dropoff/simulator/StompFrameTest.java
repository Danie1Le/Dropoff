package io.github.danie1le.dropoff.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StompFrameTest {

    @Test
    void encodesTheCommandHeadersBlankLineBodyAndNul() {
        StompFrame frame = StompFrame.of("SUBSCRIBE", "id", "sub-0", "destination", "/topic/orders/7");

        assertThat(frame.encode()).isEqualTo("SUBSCRIBE\nid:sub-0\ndestination:/topic/orders/7\n\n\0");
    }

    @Test
    void decodesAMessageFrame() {
        String text = "MESSAGE\ndestination:/topic/orders/7\ncontent-type:application/json\n"
                + "subscription:sub-0\nmessage-id:abc-1\ncontent-length:17\n\n{\"type\":\"STATUS\"}\0";

        StompFrame frame = StompFrame.decode(text);

        assertThat(frame.command()).isEqualTo("MESSAGE");
        assertThat(frame.headers())
                .containsEntry("destination", "/topic/orders/7")
                .containsEntry("subscription", "sub-0");
        assertThat(frame.body()).isEqualTo("{\"type\":\"STATUS\"}");
    }

    @Test
    void decodesCarriageReturnLineEndingsAndKeepsTheFirstRepeatedHeader() {
        StompFrame frame = StompFrame.decode("ERROR\r\nmessage:first\r\nmessage:second\r\n\r\nbad frame\0");

        assertThat(frame.command()).isEqualTo("ERROR");
        assertThat(frame.headers()).isEqualTo(Map.of("message", "first"));
        assertThat(frame.body()).isEqualTo("bad frame");
    }

    @Test
    void unescapesHeaderValues() {
        StompFrame frame = StompFrame.decode("ERROR\nmessage:a\\cb\\nc\\\\d\n\n\0");

        assertThat(frame.headers()).containsEntry("message", "a:b\nc\\d");
    }

    @Test
    void anUndefinedEscapeIsAFatalError() {
        assertThatThrownBy(() -> StompFrame.decode("ERROR\nmessage:a\\tb\n\n\0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Undefined STOMP header escape \\t");
    }

    @Test
    void aTrailingBackslashIsAFatalError() {
        assertThatThrownBy(() -> StompFrame.decode("ERROR\nmessage:ab\\\n\n\0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leavesConnectedHeadersUnescaped() {
        StompFrame frame = StompFrame.decode("CONNECTED\nversion:1.2\nserver:a\\cb\\t\n\n\0");

        assertThat(frame.headers()).containsEntry("server", "a\\cb\\t");
    }

    @Test
    void rejectsAFrameWithoutAHeaderBlock() {
        assertThatThrownBy(() -> StompFrame.decode("CONNECTED\0")).isInstanceOf(IllegalArgumentException.class);
    }
}
