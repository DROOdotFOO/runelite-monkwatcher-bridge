package io.axol.monkwatcher;

public class EventPayload {
    public final int v = 1;
    public final String event;
    public final Object data;

    public EventPayload(String event, Object data) {
        this.event = event;
        this.data = data;
    }
}
