package com.github.aznamier.analytics;

import com.google.common.base.Strings;
import com.segment.analytics.Analytics;
import com.segment.analytics.messages.Message;
import com.segment.analytics.messages.MessageBuilder;
import com.segment.analytics.messages.TrackMessage;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class Segment implements Tracker {
    private static Segment INSTANCE;
    private static final Logger LOGGER = Logger.getLogger(Segment.class);
    private final AtomicReference<Analytics> analyticsRef;

    private Segment() {
        String writeKey = System.getenv("SEGMENT_WRITE_KEY");
        LOGGER.infof("initializing segment analytics with key: %s", writeKey);
        analyticsRef = new AtomicReference<>(null);
        try {
            analyticsRef.set(Analytics.builder(writeKey).build());
        } catch (Throwable e) {
            LOGGER.errorf(e, "failed to initialize segment analytics!");
        }
    }

    synchronized public static Segment getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new Segment();
        }
        return INSTANCE;
    }

    private <T extends Message> void tryEnqueue(MessageBuilder<T, ?> builder) {
        if (analyticsRef.get() == null) return;
        analyticsRef.get().enqueue(builder);
    }

    @Override
    public void event(String userId, String eventName) {
        event(userId, eventName, new HashMap<>());
    }

    @Override
    public void event(String userId, String eventName, Map<String, ?> eventProps) {
        if (Strings.isNullOrEmpty(userId) || Strings.isNullOrEmpty(eventName)) {
            LOGGER.errorf("ignoring event [%s] with userId [%s]...", eventName, userId);
            return;
        }
        LOGGER.infof("received event [%s] with userId [%s]", eventName, userId);
        try {
            tryEnqueue(TrackMessage.builder(eventName).properties(eventProps).userId(userId));
        } catch (Throwable e) {
            LOGGER.errorf(e, "failed to send event [%s] with userId [%s]", eventName, userId);
        }
    }
}
