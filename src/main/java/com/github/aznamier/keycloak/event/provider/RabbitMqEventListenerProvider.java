package com.github.aznamier.keycloak.event.provider;

import com.github.aznamier.analytics.SegmentConsumer;
import com.rabbitmq.client.*;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.AMQP.BasicProperties.Builder;
import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerTransaction;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class RabbitMqEventListenerProvider extends SegmentConsumer implements EventListenerProvider {

    private static final Logger log = Logger.getLogger(RabbitMqEventListenerProvider.class);

    private final RabbitMqConfig cfg;
    private final ConnectionFactory factory;

    private final EventListenerTransaction tx = new EventListenerTransaction(this::publishAdminEvent, this::publishEvent);

    public RabbitMqEventListenerProvider(RabbitMqConfig cfg, KeycloakSession session) {
        this.cfg = cfg;

        this.factory = new ConnectionFactory();

        this.factory.setUsername(cfg.getUsername());
        this.factory.setPassword(cfg.getPassword());
        this.factory.setVirtualHost(cfg.getVhost());
        this.factory.setHost(cfg.getHostUrl());
        this.factory.setPort(cfg.getPort());

        if (cfg.getUseTls()) {
            try {
                this.factory.useSslProtocol();
            } catch (Exception e) {
                log.error("Could not use SSL protocol", e);
            }
        }

        session.getTransactionManager().enlistAfterCompletion(tx);

    }

    @Override
    public void close() {

    }

    @Override
    public void onEvent(Event event) {
        try {
            this.onEvent(event.getType().toString(), event);
        } catch (Throwable t) {
            log.errorf(t, "failed to send event %s to Segment!", event.getType());
        }

        if (filterClientEvent(event)) {
            log.info("Event filtered: " + event.getType());
            return;
        }
        tx.addEvent(event);
    }

    @Override
    public void onEvent(AdminEvent adminEvent, boolean includeRepresentation) {
        try {
            this.onEvent(adminEvent.getOperationType().toString(), adminEvent);
        } catch (Throwable t) {
            log.errorf(t, "failed to send admin event %s to Segment!", adminEvent.getOperationType());
        }

        if (filterAdminEvent(adminEvent)) {
            log.info("Admin event filtered: " + adminEvent.getOperationType());
            return;
        }
        tx.addAdminEvent(adminEvent, includeRepresentation);
    }

    private boolean filterClientEvent(Event event) {
        return filterEvent(event.getType().toString().toUpperCase(Locale.ENGLISH),
                cfg.getAllowedClientEvents(),
                cfg.getIgnoredClientEvents());
    }

    private boolean filterAdminEvent(AdminEvent adminEvent) {
        return filterEvent(adminEvent.getOperationType().toString().toUpperCase(Locale.ENGLISH),
                cfg.getAllowedAdminEvents(),
                cfg.getIgnoredAdminEvents());
    }

    private boolean filterEvent(String eventTypeUpperCase, final String allowFilter, String ignoreFilter) {
        Set<String> allowedEvents = parseFilter(allowFilter);
        Set<String> ignoredEvents = parseFilter(ignoreFilter);

        if (allowedEvents.isEmpty()) {
            return ignoredEvents.contains(eventTypeUpperCase);
        } else {
            return !allowedEvents.contains(eventTypeUpperCase);
        }
    }

    private Set<String> parseFilter(String eventFilter) {
        eventFilter = eventFilter.replace(" ", "");
        if (eventFilter.isEmpty()) {
            return new HashSet<>();
        }
        return Arrays.stream(eventFilter.toUpperCase(Locale.ENGLISH).split(",")).collect(Collectors.toSet());
    }

    private void publishEvent(Event event) {
        EventClientNotificationMqMsg msg = EventClientNotificationMqMsg.create(event);
        String routingKey = RabbitMqConfig.calculateRoutingKey(event);
        String messageString = RabbitMqConfig.writeAsJson(msg, true);

        BasicProperties msgProps = RabbitMqEventListenerProvider.getMessageProps(EventClientNotificationMqMsg.class.getName());
        this.publishNotification(messageString, msgProps, routingKey);
    }

    private void publishAdminEvent(AdminEvent adminEvent, boolean includeRepresentation) {
        EventAdminNotificationMqMsg msg = EventAdminNotificationMqMsg.create(adminEvent);
        String routingKey = RabbitMqConfig.calculateRoutingKey(adminEvent);
        String messageString = RabbitMqConfig.writeAsJson(msg, true);
        BasicProperties msgProps = RabbitMqEventListenerProvider.getMessageProps(EventAdminNotificationMqMsg.class.getName());
        this.publishNotification(messageString, msgProps, routingKey);
    }

    private static BasicProperties getMessageProps(String className) {

        Map<String, Object> headers = new HashMap<>();
        headers.put("__TypeId__", className);

        int DELIVERY_MODE_PERSISTENT = 2;
        Builder propsBuilder = new AMQP.BasicProperties.Builder()
                .appId("Keycloak")
                .headers(headers)
                .deliveryMode(DELIVERY_MODE_PERSISTENT)
                .contentType("application/json")
                .contentEncoding("UTF-8");
        return propsBuilder.build();
    }


    private void publishNotification(String messageString, BasicProperties props, String routingKey) {
        try {
            Connection conn = factory.newConnection();
            Channel channel = conn.createChannel();

            // declaration and binding are not required programmatically
            try {
                channel.exchangeDeclarePassive(cfg.getExchange());
            } catch (IOException e) {
                log.infof("exchange (%s) not found!", cfg.getExchange());
                boolean durable = true; // the exchange will survive a broker restart
                if (!channel.isOpen()) channel = conn.createChannel();
                channel.exchangeDeclare(cfg.getExchange(), BuiltinExchangeType.TOPIC, durable);
                log.infof("exchange (%s) declared successfully.", cfg.getExchange());
            }
            try {
                channel.queueDeclarePassive(cfg.getQueueName());
            } catch (IOException e) {
                log.infof("queue (%s) not found!", cfg.getQueueName());
                boolean durable = true; // the queue will survive a broker restart
                if (!channel.isOpen()) channel = conn.createChannel();
                channel.queueDeclare(cfg.getQueueName(), durable, false, false, null);
                log.infof("queue (%s) declared successfully.", cfg.getQueueName());
            }

            channel.queueBind(cfg.getQueueName(), cfg.getExchange(), routingKey);
            channel.basicPublish(cfg.getExchange(), routingKey, props, messageString.getBytes(StandardCharsets.UTF_8));

            log.infof("keycloak-to-rabbitmq SUCCESS sending message: %s%n%s%n", routingKey, messageString);

            channel.close();
            conn.close();
        } catch (Exception ex) {
            log.errorf(ex, "keycloak-to-rabbitmq ERROR sending message: %s%n%s%n", routingKey, messageString);
        }
    }
}
