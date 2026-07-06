package com.example.orders.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class OrderItem {

    @Id
    private Long id;

    private String productSku;

    private int quantity;
}
