package com.example.orders.service;

import com.example.orders.client.PaymentClient;
import com.example.orders.entity.Order;
import com.example.orders.repository.OrderRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final PaymentClient paymentClient;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OrderService(OrderRepository orderRepository, PaymentClient paymentClient, KafkaTemplate<String, String> kafkaTemplate) {
        this.orderRepository = orderRepository;
        this.paymentClient = paymentClient;
        this.kafkaTemplate = kafkaTemplate;
    }

    public Order placeOrder(Order order) {
        Order saved = orderRepository.save(order);
        paymentClient.charge(order);
        kafkaTemplate.send("order-events-topic", "order placed: " + saved.getId());
        return saved;
    }

    public Order getOrder(Long id) {
        return orderRepository.findById(id).orElseThrow();
    }
}
