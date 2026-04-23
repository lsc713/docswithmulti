package com.example.merchantlimit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MerchantLimitApplication {
    public static void main(String[] args) {
        SpringApplication.run(MerchantLimitApplication.class, args);
    }
}
