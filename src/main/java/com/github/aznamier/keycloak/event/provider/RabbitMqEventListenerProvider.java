package com.github.aznamier.keycloak.event.provider;

import com.rabbitmq.client.*;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.AMQP.BasicProperties.Builder;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerTransaction;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@JBossLog
public class RabbitMqEventListenerProvider implements EventListenerProvider {
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
        tx.addEvent(event);
    }

    @Override
    public void onEvent(AdminEvent adminEvent, boolean includeRepresentation) {
        tx.addAdminEvent(adminEvent, includeRepresentation);
    }

    private void publishEvent(Event event) {
        EventClientNotificationMqMsg msg = EventClientNotificationMqMsg.create(event);
        String routingKey = RabbitMqConfig.calculateRoutingKey(event);
        String messageString = RabbitMqConfig.writeAsJson(msg, true);

        BasicProperties msgProps = RabbitMqEventListenerProvider.getMessageProps(
                EventClientNotificationMqMsg.class.getName(), event.getId());
        this.publishNotification(messageString, msgProps, routingKey);
    }

    private void publishAdminEvent(AdminEvent adminEvent, boolean includeRepresentation) {
        EventAdminNotificationMqMsg msg = EventAdminNotificationMqMsg.create(adminEvent);
        String routingKey = RabbitMqConfig.calculateRoutingKey(adminEvent);
        String messageString = RabbitMqConfig.writeAsJson(msg, true);
        BasicProperties msgProps = RabbitMqEventListenerProvider.getMessageProps(
                EventAdminNotificationMqMsg.class.getName(), adminEvent.getId());
        this.publishNotification(messageString, msgProps, routingKey);
    }

    private static BasicProperties getMessageProps(String className, String deduplicationHeader) {

        Map<String, Object> headers = new HashMap<>();
        headers.put("__TypeId__", className);
        headers.put("x-deduplication-header", deduplicationHeader);

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

            // declaration/binding is not required programmatically
            try {
                channel.exchangeDeclarePassive(cfg.getExchange());
            } catch (IOException e) {
                log.infof("exchange (%s) not found!", cfg.getExchange());
                boolean durable = true; // the exchange will survive a broker restart
                if (!channel.isOpen()) channel = conn.createChannel();
                channel.exchangeDeclare(cfg.getExchange(), BuiltinExchangeType.TOPIC, durable);
                log.infof("exchange (%s) declared successfully.", cfg.getExchange());
            }

            channel.basicPublish(cfg.getExchange(), routingKey, props, messageString.getBytes(StandardCharsets.UTF_8));

            log.infof("keycloak-to-rabbitmq SUCCESS sending message: %s%n%s%n", routingKey, messageString);

            channel.close();
            conn.close();
        } catch (Exception ex) {
            log.errorf(ex, "keycloak-to-rabbitmq ERROR sending message: %s%n%s%n", routingKey, messageString);
        }
    }
}
