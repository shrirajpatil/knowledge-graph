package com.example.orders.entity;

import jakarta.persistence.*;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private Long id;

    private String status;

    @OneToMany
    private List<OrderItem> items;
}
