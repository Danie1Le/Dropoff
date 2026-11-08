package io.github.danie1le.dropoff.simulator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One STOMP 1.2 frame (M4-3, #42), written by hand: a command line, {@code name:value} header lines, a blank line,
 * the body and a NUL byte. {@code watch} only sends {@code CONNECT} and {@code SUBSCRIBE} with plain ids and paths, so
 * {@link #encode()} does not escape header values; {@link #decode(String)} does unescape the frames the server sends.
 */
record StompFrame(String command, Map<String, String> headers, String body) {

    private static final char NUL = '\0';

    StompFrame {
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    /** A frame with no body; {@code nameValues} alternates header names and values, in order. */
    static StompFrame of(String command, String... nameValues) {
        if (nameValues.length % 2 != 0) {
            throw new IllegalArgumentException("headers come in name/value pairs");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            headers.put(nameValues[i], nameValues[i + 1]);
        }
        return new StompFrame(command, headers, "");
    }

    String encode() {
        StringBuilder text = new StringBuilder(command).append('\n');
        headers.forEach(
                (name, value) -> text.append(name).append(':').append(value).append('\n'));
        return text.append('\n').append(body).append(NUL).toString();
    }

    /** Parses one frame. Lines may end in {@code \n} or {@code \r\n}; a repeated header keeps its first value. */
    static StompFrame decode(String text) {
        String frame = text.indexOf(NUL) >= 0 ? text.substring(0, text.indexOf(NUL)) : text;
        int blank = frame.indexOf("\n\n");
        int blankCrLf = frame.indexOf("\r\n\r\n");
        int headEnd;
        int bodyStart;
        if (blankCrLf >= 0 && (blank < 0 || blankCrLf < blank)) {
            headEnd = blankCrLf;
            bodyStart = blankCrLf + 4;
        } else if (blank >= 0) {
            headEnd = blank;
            bodyStart = blank + 2;
        } else {
            throw new IllegalArgumentException("STOMP frame has no blank line after its headers");
        }
        List<String> lines = frame.substring(0, headEnd).lines().toList();
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.putIfAbsent(line.substring(0, colon), unescape(line.substring(colon + 1)));
            }
        }
        return new StompFrame(lines.get(0), headers, frame.substring(bodyStart));
    }

    /** STOMP 1.2 header escapes: {@code \\c} is a colon, {@code \\n} a newline, {@code \\r} a CR, {@code \\\\} a backslash. */
    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                out.append(
                        switch (next) {
                            case 'c' -> ':';
                            case 'n' -> '\n';
                            case 'r' -> '\r';
                            default -> next;
                        });
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
