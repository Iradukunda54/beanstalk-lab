package com.amalitech.lab.beanstalkapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class AppController {

    private final VisitCounterService visitCounterService;

    @Value("${app.version}")
    private String appVersion;

    public AppController(VisitCounterService visitCounterService) {
        this.visitCounterService = visitCounterService;
    }

    @GetMapping("/")
    public Map<String, Object> home() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "deployed successfully via Elastic Beanstalk");
        body.put("version", appVersion);
        body.put("timestamp", Instant.now().toString());

        if (visitCounterService.isConfigured()) {
            body.put("visitCount", visitCounterService.incrementAndGet());
            body.put("dynamoDbConnected", true);
        } else {
            body.put("dynamoDbConnected", false);
        }

        return body;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "version", appVersion);
    }
}
