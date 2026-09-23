package com.sysj.collector.config;


import com.sysj.collector.metrics.TaskMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class MetricsConfig {

    private final MeterRegistry meterRegistry;

    @Bean
    public TaskMetrics taskMetrics() {
        return new TaskMetrics(meterRegistry);
    }
}
