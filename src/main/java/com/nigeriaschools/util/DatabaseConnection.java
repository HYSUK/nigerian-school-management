package com.nigeriaschools.util;

import java.security.Security;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConnection {

    // Force Java 21 to remove TLS 1.0 and 1.1 from its disabled security list
    static {
        String disabledAlgorithms = Security.getProperty("jdk.tls.disabledAlgorithms");
        if (disabledAlgorithms != null) {
            // Remove TLSv1 and TLSv1.1 restrictions if present
            disabledAlgorithms = disabledAlgorithms.replace("TLSv1, ", "").replace("TLSv1.1, ", "");
            Security.setProperty("jdk.tls.disabledAlgorithms", disabledAlgorithms);
        }
    }

    private static final String URL = "jdbc:sqlserver://localhost:1433;"
            + "databaseName=SchoolManagementDB;"
            + "user=sa;"
            + "password=hysuk;"
            + "encrypt=false;"; // Plain text session communication after login

    public static void testConnection() {
        System.out.println("Attempting to connect via SQL Server Authentication with TLS override...");
        try (Connection conn = getConnection()) {
            if (conn != null && !conn.isClosed()) {
                System.out.println("✅ DATABASE CONNECTION SUCCESSFUL! Your development pipeline is now fully open.");
            }
        } catch (SQLException e) {
            System.err.println("❌ Connection Failed:");
            System.err.println("Message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL);
    }
}
