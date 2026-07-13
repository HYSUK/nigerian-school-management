package com.nigeriaschools.dao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class StudentLocalDAO {

    // Connection params for local SQL Server
    private static final String DB_URL = "jdbc:sqlserver://localhost:1433;databaseName=SchoolManagementDB;encrypt=false;";
    private static final String DB_USER = "sa";
    private static final String DB_PASS = "hysuk";

    private Connection getConnection() throws Exception {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    /**
     * 1. Fetches student info locally by Application ID
     */
    public StudentRecord getStudentByAppId(String applicationId) {
        String query = "SELECT ApplicationID, FirstName, LastName, CurrentClass, ScreeningStatus, ScreeningScore "
                     + "FROM [SchoolManagementDB].[dbo].[Students] "
                     + "WHERE UPPER(LTRIM(RTRIM(ApplicationID))) = UPPER(?)";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, applicationId.trim());
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return new StudentRecord(
                    rs.getString("ApplicationID").trim(),
                    rs.getString("FirstName").trim(),
                    rs.getString("LastName").trim(),
                    rs.getString("CurrentClass").trim(),
                    rs.getString("ScreeningStatus") != null ? rs.getString("ScreeningStatus").trim() : "Pending",
                    rs.getInt("ScreeningScore")
                );
            }
        } catch (Exception e) {
            System.err.println("❌ Local DAO Read Error: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 2. Updates screening score locally in SQL Server
     */
    public boolean updateScreeningResultLocally(String applicationId, int score, int cutoff) {
        String status = (score >= cutoff) ? "Passed" : "Failed";

        String query = "UPDATE [SchoolManagementDB].[dbo].[Students] "
                     + "SET ScreeningScore = ?, ExamScore = ?, ScreeningStatus = ? "
                     + "WHERE UPPER(LTRIM(RTRIM(ApplicationID))) = UPPER(?)";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setInt(1, score);
            stmt.setInt(2, score);
            stmt.setString(3, status);
            stmt.setString(4, applicationId.trim());

            int affectedRows = stmt.executeUpdate();
            return affectedRows > 0;

        } catch (Exception e) {
            System.err.println("❌ Local DAO Write Error: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Inner Data Model Helper Class
    public static class StudentRecord {
        public String applicationId;
        public String firstName;
        public String lastName;
        public String currentClass;
        public String screeningStatus;
        public int screeningScore;

        public StudentRecord(String applicationId, String firstName, String lastName, 
                             String currentClass, String screeningStatus, int screeningScore) {
            this.applicationId = applicationId;
            this.firstName = firstName;
            this.lastName = lastName;
            this.currentClass = currentClass;
            this.screeningStatus = screeningStatus;
            this.screeningScore = screeningScore;
        }
    }
}