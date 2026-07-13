package com.nigeriaschools.dao;

import com.nigeriaschools.util.DatabaseConnection;
import java.sql.*;

public class FeeDAO {

    public double getOutstandingFees(int studentId) {
        String sql = "SELECT OutstandingBalance FROM StudentFees WHERE StudentID = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, studentId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getDouble("OutstandingBalance");
            }
        } catch (SQLException e) {
            System.err.println("❌ Error fetching fees: " + e.getMessage());
            e.printStackTrace();
        }
        return 0.0;
    }
}
