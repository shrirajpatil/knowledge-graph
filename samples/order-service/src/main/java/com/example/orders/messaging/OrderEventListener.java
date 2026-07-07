package com.example.orders.messaging;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventListener {

    @KafkaListener(topics = "order-events-topic")
    public void onOrderEvent(String message) {
        System.out.println("Received: " + message);
    }
}
