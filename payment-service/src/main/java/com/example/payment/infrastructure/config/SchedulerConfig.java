package com.example.payment.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄러 설정
 */
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulerConfig {
}
