import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootApplication
public class BajajFinservApp implements CommandLineRunner {

    private static final String WEBHOOK_URL = "https://bfhldevapigw.healthrx.co.in/hiring/generateWebhook";
    private static final String TEST_WEBHOOK_URL = "https://bfhldevapigw.healthrx.co.in/hiring/testWebhook";
    private static final String REG_NO = "REG12347";
    private static final String NAME = "John Doe";
    private static final String EMAIL = "john@example.com";
    private static final int MAX_RETRIES = 4; // Added retry logic

    public static void main(String[] args) {
        SpringApplication.run(BajajFinservApp.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        // 1. Make a POST request to /generateWebhook
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("name", NAME);
        requestBody.put("regNo", REG_NO);
        requestBody.put("email", EMAIL);

        HttpEntity<Map<String, String>> httpEntity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<JsonNode> response = null;
        int retryCount = 0;
        while (retryCount < MAX_RETRIES) {
            try {
                response = restTemplate.exchange(WEBHOOK_URL, HttpMethod.POST, httpEntity, JsonNode.class);
                break; // If successful, break the retry loop
            } catch (HttpClientErrorException | ResourceAccessException e) {
                System.err.println("Error during /generateWebhook call (attempt " + (retryCount + 1) + "/" + MAX_RETRIES + "): " + e.getMessage());
                retryCount++;
                if (retryCount >= MAX_RETRIES) {
                    System.err.println("Failed to get webhook after " + MAX_RETRIES + " attempts. Exiting.");
                    throw e; //Re-throw the exception to stop the program.
                }
                // Add a delay before retrying (exponential backoff would be better in a real-world scenario)
                Thread.sleep(1000 * retryCount); // Simple retry delay
            }
        }

        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            System.err.println("Failed to get webhook after all retries.  Exiting.");
            return;
        }

        JsonNode responseBody = response.getBody();
        String webhook = responseBody.get("webhook").asText();
        String accessToken = responseBody.get("accessToken").asText();
        JsonNode data = responseBody.get("data");


        // 2. Solve the problem based on the registration number.
        Map<String, Object> output = solveProblem(data);

        // 3. Send the result to the provided webhook with JWT authentication.
        sendResultToWebhook(webhook, accessToken, output);
    }



    private Map<String, Object> solveProblem(JsonNode data) {
        // Determine which question to solve based on the last digit of the registration number
        int lastDigit = Integer.parseInt(REG_NO.substring(REG_NO.length() - 1));

        if (lastDigit % 2 == 0) {
            // Solve Question 2: Nth-Level Followers
            return solveQuestion2(data);
        } else {
            // Solve Question 1: Mutual Followers -  Added implementation for Question 1
            return solveQuestion1(data);
        }
    }

    private Map<String, Object> solveQuestion1(JsonNode data) {
        List<Map<String, Object>> users = new ArrayList<>();
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            users = objectMapper.readValue(data.get("users").toString(), new TypeReference<List<Map<String, Object>>>(){});
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

        List<List<Integer>> mutualFollowers = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            Map<String, Object> user1 = users.get(i);
            int id1 = (int) user1.get("id");
            List<Integer> follows1 = (List<Integer>) user1.get("follows");

            for (int j = i + 1; j < users.size(); j++) {
                Map<String, Object> user2 = users.get(j);
                int id2 = (int) user2.get("id");
                List<Integer> follows2 = (List<Integer>) user2.get("follows");

                if (follows1.contains(id2) && follows2.contains(id1)) {
                    List<Integer> pair = new ArrayList<>();
                    pair.add(Math.min(id1, id2));
                    pair.add(Math.max(id1, id2));
                    mutualFollowers.add(pair);
                }
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("regNo", REG_NO);
        result.put("outcome", mutualFollowers);
        return result;
    }


    private Map<String, Object> solveQuestion2(JsonNode data) {
        JsonNode usersNode = data.get("users");
        int n = usersNode.get("n").asInt();
        int findId = usersNode.get("findId").asInt();
        JsonNode usersArray = usersNode.get("users");

        // Create a map for easy user lookup
        Map<Integer, JsonNode> userMap = new HashMap<>();
        for (JsonNode user : usersArray) {
            userMap.put(user.get("id").asInt(), user);
        }

        List<Integer> queue = new ArrayList<>();
        queue.add(findId);
        List<Integer> visited = new ArrayList<>();
        visited.add(findId);
        int level = 0;

        while (level < n && !queue.isEmpty()) {
            int nodesAtLevel = queue.size();
            for (int i = 0; i < nodesAtLevel; i++) {
                int currentId = queue.remove(0);
                JsonNode currentUser = userMap.get(currentId);
                for (JsonNode followIdNode : currentUser.get("follows")) {
                    int followId = followIdNode.asInt();
                    if (!visited.contains(followId)) {
                        visited.add(followId);
                        queue.add(followId);
                    }
                }
            }
            level++;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("regNo", REG_NO);
        result.put("outcome", queue);
        return result;
    }

    private void sendResultToWebhook(String webhookUrl, String accessToken, Map<String, Object> output) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", accessToken);

        HttpEntity<Map<String, Object>> httpEntity = new HttpEntity<>(output, headers);

        int retryCount = 0;
        while (retryCount < MAX_RETRIES) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(webhookUrl, HttpMethod.POST, httpEntity, String.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    System.out.println("Successfully sent result to webhook.");
                    return; // Exit the method on success
                } else {
                    System.err.println("Failed to send result to webhook. Status code: " + response.getStatusCode());
                }

            } catch (HttpClientErrorException | ResourceAccessException e) {
                System.err.println("Error sending result to webhook (attempt " + (retryCount + 1) + "/" + MAX_RETRIES + "): " + e.getMessage());

            }
            retryCount++;
            if (retryCount >= MAX_RETRIES) {
                System.err.println("Failed to send result to webhook after " + MAX_RETRIES + " attempts.");
                return; // Exit after max retries
            }
            try {
                Thread.sleep(1000 * retryCount);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }
        System.err.println("Failed to send the result after multiple retries. Please check the application logs.");

    }
}

