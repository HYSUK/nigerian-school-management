package com.nigeriaschools.dao;

import com.nigeriaschools.model.AcademicRecord;
import com.nigeriaschools.util.DatabaseConnection;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.math.BigDecimal;

public class ResultDAO {

    public List<AcademicRecord> getResultsByStudentId(int studentId) {
        List<AcademicRecord> results = new ArrayList<>();
        String sql = "SELECT RecordId, StudentId, AcademicYear, AcademicTerm, Subject, FirstCA, SecondCA, ExamScore, TotalScore, Grade, Remarks FROM StudentResults WHERE StudentId = ?";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, studentId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                AcademicRecord record = new AcademicRecord(
                        rs.getInt("RecordId"),
                        rs.getInt("StudentId"),
                        rs.getString("AcademicYear"),
                        rs.getString("AcademicTerm"),
                        rs.getString("Subject"),
                        rs.getBigDecimal("FirstCA"),
                        rs.getBigDecimal("SecondCA"),
                        rs.getBigDecimal("ExamScore"),
                        rs.getBigDecimal("TotalScore"),
                        rs.getString("Grade"),
                        rs.getString("Remarks")
                );
                results.add(record);
            }
        } catch (SQLException e) {
            System.err.println("❌ Error fetching results: " + e.getMessage());
            e.printStackTrace();
        }
        return results;
    }
}
