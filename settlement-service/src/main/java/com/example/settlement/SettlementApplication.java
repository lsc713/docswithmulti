package com.example.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Phase 1: consume-only. No @EnableScheduling (reconciler is Phase 3).
@SpringBootApplication
public class SettlementApplication {
    public static void main(String[] args) {
        SpringApplication.run(SettlementApplication.class, args);
    }
}
