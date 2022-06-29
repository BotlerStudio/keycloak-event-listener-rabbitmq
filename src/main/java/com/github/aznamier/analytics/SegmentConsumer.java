package com.github.aznamier.analytics;

import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.admin.AdminEvent;

public abstract class SegmentConsumer {

    private static final Logger LOGGER = Logger.getLogger(SegmentConsumer.class);

    protected void onEvent(String type, Event event) {
        LOGGER.infof("received event: %s", type);
        // TODO: filter by type and put event tracking logic here using Segment.getInstance().event(...);
    }

    protected void onEvent(String type, AdminEvent adminEvent) {
        LOGGER.infof("received admin event: %s", type);
        // TODO: filter by type and put event tracking logic here using Segment.getInstance().event(...);
    }
}
