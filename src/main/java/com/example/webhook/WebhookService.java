package com.example.webhook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import org.springframework.boot.web.client.RestTemplateBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.util.*;

@SpringBootApplication
public class WebhookService {
    public static void main(String[] args) {
        SpringApplication.run(WebhookService.class, args);
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
            .setConnectTimeout(Duration.ofSeconds(30))
            .setReadTimeout(Duration.ofSeconds(30))
            .build();
    }

    @Bean
    public CommandLineRunner run(RestTemplate restTemplate, ObjectMapper objectMapper) {
        return args -> {
            try {
                // 1. Call generateWebhook endpoint
                String generateUrl = "https://bfhldevapigw.healthrx.co.in/hiring/generateWebhook";
                
                Map<String, String> requestBody = new HashMap<>();
                requestBody.put("name", "John Doe");
                requestBody.put("regNo", "REG12347");
                requestBody.put("email", "john@example.com");
                
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                
                HttpEntity<Map<String, String>> entity = new HttpEntity<>(requestBody, headers);
                
                // 2. Get webhook configuration
                ResponseEntity<WebhookResponse> response = restTemplate.exchange(
                    generateUrl,
                    HttpMethod.POST,
                    entity,
                    WebhookResponse.class
                );
                
                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    WebhookResponse webhookResponse = response.getBody();
                    System.out.println("Received response: " + objectMapper.writeValueAsString(webhookResponse));
                    
                    // 3. Find mutual followers
                    List<int[]> mutualPairs = findMutualFollowers(webhookResponse.getUsers());
                    
                    // 4. Prepare outcome
                    Map<String, Object> outcome = new HashMap<>();
                    outcome.put("regNo", "REG12347");
                    outcome.put("outcome", mutualPairs);
                    
                    // 5. Send to webhook with retry
                    sendToWebhookWithRetry(
                        restTemplate, 
                        webhookResponse.getWebhook(), 
                        webhookResponse.getAccessToken(), 
                        outcome,
                        4
                    );
                }
                
                // Keep application running
                Thread.sleep(300000);
            } catch (Exception e) {
                System.err.println("Application failed: " + e.getMessage());
                e.printStackTrace();
                Thread.sleep(60000);
                System.exit(1);
            }
        };
    }
    
    private List<int[]> findMutualFollowers(List<User> users) {
        List<int[]> result = new ArrayList<>();
        Set<String> addedPairs = new HashSet<>();
        
        Map<Integer, Set<Integer>> userFollows = new HashMap<>();
        for (User user : users) {
            userFollows.put(user.getId(), new HashSet<>(user.getFollows()));
        }
        
        for (User user : users) {
            int userId = user.getId();
            for (int followedId : user.getFollows()) {
                if (userFollows.containsKey(followedId) && 
                    userFollows.get(followedId).contains(userId)) {
                    
                    int min = Math.min(userId, followedId);
                    int max = Math.max(userId, followedId);
                    String pairKey = min + "," + max;
                    
                    if (!addedPairs.contains(pairKey)) {
                        result.add(new int[]{min, max});
                        addedPairs.add(pairKey);
                    }
                }
            }
        }
        
        result.sort((a, b) -> Integer.compare(a[0], b[0]));
        return result;
    }
    
    private void sendToWebhookWithRetry(
        RestTemplate restTemplate, 
        String webhookUrl, 
        String accessToken, 
        Map<String, Object> outcome,
        int maxRetries
    ) {
        int attempt = 0;
        boolean success = false;
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", accessToken);
        
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(outcome, headers);
        
        while (attempt < maxRetries && !success) {
            attempt++;
            try {
                ResponseEntity<String> response = restTemplate.exchange(
                    webhookUrl,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
                );
                
                if (response.getStatusCode().is2xxSuccessful()) {
                    success = true;
                    System.out.println("Webhook call succeeded on attempt " + attempt);
                } else {
                    System.out.println("Webhook call failed on attempt " + attempt);
                }
            } catch (Exception e) {
                System.out.println("Webhook call failed on attempt " + attempt + ": " + e.getMessage());
            }
            
            if (!success && attempt < maxRetries) {
                try {
                    Thread.sleep(1000 * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // Inner classes with proper JSON mapping
    public static class WebhookResponse {
        private String webhook;
        private String accessToken;
        private Map<String, Object> data;
        
        public String getWebhook() { return webhook; }
        public void setWebhook(String webhook) { this.webhook = webhook; }
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public Map<String, Object> getData() { return data; }
        public void setData(Map<String, Object> data) { this.data = data; }
        
        @SuppressWarnings("unchecked")
        public List<User> getUsers() {
            try {
                if (data != null && data.containsKey("users")) {
                    Object users = data.get("users");
                    if (users instanceof List) {
                        ObjectMapper mapper = new ObjectMapper();
                        return mapper.convertValue(users, new TypeReference<List<User>>() {});
                    }
                }
            } catch (Exception e) {
                System.err.println("Error parsing users: " + e.getMessage());
            }
            return Collections.emptyList();
        }
    }

    public static class User {
        private int id;
        private String name;
        private List<Integer> follows;
        
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<Integer> getFollows() { return follows; }
        public void setFollows(List<Integer> follows) { this.follows = follows; }
    }
}