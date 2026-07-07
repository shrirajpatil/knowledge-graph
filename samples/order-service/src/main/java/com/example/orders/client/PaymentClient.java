package com.example.orders.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "${payment.service.name}", url = "${payment.service.url}")
public interface PaymentClient {

    @PostMapping("/charges")
    void charge(@RequestBody Object chargeRequest);
}
