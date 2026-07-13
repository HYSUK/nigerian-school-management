package com.nigeriaschools.sync;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HttpSyncClient {

    // 🌟 Using explicit IPv4 127.0.0.1 prevents Windows Java IPv6 resolution issues
    private static final String API_URL = "http://127.0.0.1:8000/api/v1/sync/pending-registrations";
    private static final String ACK_URL = "http://127.0.0.1:8000/api/v1/sync/acknowledge-import";
    private static final String SCREENING_SYNC_URL = "http://127.0.0.1:8000/api/v1/sync/screening-result";
    private static final String CREDENTIALS_SYNC_URL = "http://127.0.0.1:8000/api/v1/sync/assign-credentials";
    
    // API key matching main.py
    private static final String AUTH_TOKEN = "hysuk_sync_token_2026";

    private final HttpClient httpClient;

    public HttpSyncClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Downloads pending online registrations from Python Cloud Gateway
     */
    public String fetchPendingRegistrations() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(10))
                .header("X-API-KEY", AUTH_TOKEN)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return response.body();
        } else {
            throw new RuntimeException("HTTP Sync Error Pipeline Code: " + response.statusCode());
        }
    }

    /**
     * Confirms imported student registration IDs back to the cloud gateway
     */
    public void acknowledgeImport(String jsonIdsPayload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ACK_URL))
                .timeout(Duration.ofSeconds(10))
                .header("X-API-KEY", AUTH_TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonIdsPayload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Server failed to acknowledge import data stream.");
        }
    }

    /**
     * Syncs student screening exercise result to the FastAPI Cloud Gateway
     */
    public boolean pushScreeningResult(String applicationId, int screeningScore, int cutoffMark) {
        try {
            if (applicationId == null || applicationId.trim().isEmpty()) {
                System.err.println("❌ Sync Error: Application ID is null or empty");
                return false;
            }

            String cleanAppId = applicationId.trim().replace("\\", "/");
            
            // Includes both field variations to guarantee compatibility with your FastAPI Pydantic model
            String jsonPayload = String.format(
                "{\"application_id\":\"%s\",\"screening_score\":%d,\"score\":%d,\"cutoff_mark\":%d,\"cutoff\":%d}",
                cleanAppId, screeningScore, screeningScore, cutoffMark, cutoffMark
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SCREENING_SYNC_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-API-KEY", AUTH_TOKEN)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            System.out.println("--> [SCREENING SYNC] Response Code: " + response.statusCode());
            System.out.println("--> [SCREENING SYNC] Response Body: " + response.body());

            return response.statusCode() == 200 || response.statusCode() == 201;
        } catch (Exception e) {
            System.err.println("⚠️ Screening Result Sync Exception: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Syncs generated Application ID and Security PIN back to the Python Cloud Server
     */
    public boolean pushAssignedCredentials(int pendingId, String applicationId, String studentPassword) {
        try {
            String cleanAppId = applicationId.trim().replace("\\", "/");
            
            String jsonPayload = String.format(
                "{\"pending_id\":%d,\"application_id\":\"%s\",\"student_password\":\"%s\"}",
                pendingId, cleanAppId, studentPassword.trim()
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CREDENTIALS_SYNC_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-API-KEY", AUTH_TOKEN)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 || response.statusCode() == 201;
        } catch (Exception e) {
            System.err.println("⚠️ Credentials Sync Failed for Pending ID " + pendingId + ": " + e.getMessage());
            return false;
        }
    }
}