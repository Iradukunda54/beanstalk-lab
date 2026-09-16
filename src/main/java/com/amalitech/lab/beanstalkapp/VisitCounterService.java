package com.amalitech.lab.beanstalkapp;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * Increments a single counter item in DynamoDB on every request.
 * Table name and region come from Elastic Beanstalk environment variables,
 * credentials come from the EC2 instance profile (no keys in config).
 */
@Service
public class VisitCounterService {

    private static final String PARTITION_KEY = "counterId";
    private static final String COUNTER_ID = "site-visits";

    @Value("${app.dynamodb.table:}")
    private String tableName;

    @Value("${app.dynamodb.region:eu-west-1}")
    private String region;

    private DynamoDbClient client;

    @PostConstruct
    void init() {
        if (tableName != null && !tableName.isBlank()) {
            this.client = DynamoDbClient.builder()
                    .region(Region.of(region))
                    .build();
        }
    }

    public boolean isConfigured() {
        return client != null;
    }

    public long incrementAndGet() {
        if (!isConfigured()) {
            throw new IllegalStateException("DynamoDB is not configured (DDB_TABLE_NAME not set)");
        }

        Map<String, AttributeValue> key = new HashMap<>();
        key.put(PARTITION_KEY, AttributeValue.builder().s(COUNTER_ID).build());

        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":inc", AttributeValue.builder().n("1").build());
        values.put(":start", AttributeValue.builder().n("0").build());

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression("SET visitCount = if_not_exists(visitCount, :start) + :inc")
                .expressionAttributeValues(values)
                .returnValues(ReturnValue.UPDATED_NEW)
                .build();

        try {
            UpdateItemResponse response = client.updateItem(request);
            return Long.parseLong(response.attributes().get("visitCount").n());
        } catch (DynamoDbException e) {
            throw new IllegalStateException("Failed to update visit counter in DynamoDB: " + e.getMessage(), e);
        }
    }
}
