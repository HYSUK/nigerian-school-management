package com.nigeriaschools.dao;

import com.nigeriaschools.util.DatabaseConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class TeacherDAO {

    public boolean validateLogin(String username, String password) throws SQLException {
        String sql = "SELECT * FROM Teachers WHERE Username = ? AND PasswordHash = ? AND IsActive = 1";
        
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, username);
            stmt.setString(2, password); // Note: For staging production, we will replace this with SHA-256 hashing

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next(); // Returns true if a matching row is found
            }
        }
    }
}
