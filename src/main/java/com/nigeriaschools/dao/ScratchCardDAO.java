package com.nigeriaschools.dao;

import com.nigeriaschools.util.DatabaseConnection;
import java.security.SecureRandom;
import java.sql.*;
import java.time.LocalDateTime;

public class ScratchCardDAO {

    private final SecureRandom random = new SecureRandom();

    /**
     * Generates a batch of distinct scratch cards for a specific section (JS or SS)
     * @param count How many cards to print (e.g., 100, 500)
     * @param section The category ("JS" or "SS")
     * @param batchNumber The tracking batch identity (e.g., "2026-JS1")
     * @return true if the batch was completely generated and saved successfully
     */
    public boolean generateBulkCards(int count, String section, String batchNumber) {
        String query = "INSERT INTO dbo.ScratchCards (SerialNumber, PinNumber, SchoolSection, CardStatus, BatchNumber) VALUES (?, ?, ?, 'Unused', ?)";
        
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            
            // Turn off auto-commit for fast transaction processing
            conn.setAutoCommit(false);

            for (int i = 0; i < count; i++) {
                // 1. Generate unique 12-digit Serial Number
                String serialNumber = generateRandomDigits(12);
                
                // 2. Generate secure 10-digit PIN Number
                String pinNumber = generateRandomDigits(10);
                
                stmt.setString(1, serialNumber);
                stmt.setString(2, pinNumber);
                stmt.setString(3, section.toUpperCase());
                stmt.setString(4, batchNumber);
                
                stmt.addBatch();
            }

            // Execute all inserts together as a single package
            int[] results = stmt.executeBatch();
            conn.commit(); // Save changes permanently to SQL Server
            
            return results.length == count;

        } catch (SQLException e) {
            System.err.println("❌ Bulk Card Generation Failed:");
            e.printStackTrace();
            return false;
        }
    }

    // Helper method to create secure, completely random numeric strings
    private String generateRandomDigits(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }
    
    // ... keep your previous validateAndUseCard method below this ...


    /**
     * Validates and processes a registration scratch card based on section (JS or SS)
     * @return 1 = Success, -1 = Invalid Serial/PIN, -2 = Already Used, -3 = Wrong Section (JS card used for SS or vice versa)
     */
    public int validateAndUseCard(String serial, String pin, String requiredSection, String applicationId) {
        String checkQuery = "SELECT SchoolSection, CardStatus FROM dbo.ScratchCards WHERE SerialNumber = ? AND PinNumber = ?";
        String updateQuery = "UPDATE dbo.ScratchCards SET CardStatus = 'Used', UsedByApplicationID = ?, DateUsed = ? WHERE SerialNumber = ?";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement checkStmt = conn.prepareStatement(checkQuery)) {
            
            checkStmt.setString(1, serial);
            checkStmt.setString(2, pin);
            
            try (ResultSet rs = checkStmt.executeQuery()) {
                if (!rs.next()) {
                    return -1; // Card does not exist / incorrect details
                }
                
                String cardSection = rs.getString("SchoolSection");
                String status = rs.getString("CardStatus");
                
                if ("Used".equalsIgnoreCase(status)) {
                    return -2; // Card already swiped/used
                }
                
                if (!cardSection.equalsIgnoreCase(requiredSection)) {
                    return -3; // Wrong section (e.g. trying to use JS card for SS1 admission)
                }
            }

            // If it passes all rules, mark it as used
            try (PreparedStatement updateStmt = conn.prepareStatement(updateQuery)) {
                updateStmt.setString(1, applicationId);
                updateStmt.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
                updateStmt.setString(3, serial);
                updateStmt.executeUpdate();
                return 1; // Card successfully activated
            }

        } catch (SQLException e) {
            e.printStackTrace();
            return -4; // Database error
        }
    }
}
