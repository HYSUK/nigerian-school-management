package com.nigeriaschools.dao;

import com.nigeriaschools.model.AcademicRecord;
import com.nigeriaschools.util.DatabaseConnection;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class AcademicRecordDAO {

	/**
     * Fetches existing academic records filtered by class session, term, and subject.
     * Maps s.ApplicationID to StudentRegNo via an explicit SQL alias assignment.
     */
    public List<AcademicRecord> getRecordsByFilter(String currentClass, String session, String term, String subject) throws SQLException {
        List<AcademicRecord> records = new ArrayList<>();
        
        // 🌟 FIXED: Replaced 's.StudentRegNo' with 's.ApplicationID AS StudentRegNo' to match your exact schema naming conventions
        String sql = "SELECT r.RecordID, r.StudentID, s.ApplicationID AS StudentRegNo, r.AcademicYear, r.Term, " +
                     "r.SubjectName, r.FirstCA, r.SecondCA, r.ExamScore, r.TotalScore, r.Grade, r.Remarks " +
                     "FROM AcademicRecords r " +
                     "INNER JOIN Students s ON r.StudentID = s.StudentID " +
                     "WHERE s.CurrentClass = ? AND r.AcademicYear = ? AND r.Term = ? AND r.SubjectName = ?";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, currentClass);
            stmt.setString(2, session);
            stmt.setString(3, term);
            stmt.setString(4, subject);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    AcademicRecord record = new AcademicRecord(
                        rs.getInt("RecordID"),
                        rs.getInt("StudentID"),
                        rs.getString("AcademicYear"),
                        rs.getString("Term"), 
                        rs.getString("SubjectName"),
                        rs.getBigDecimal("FirstCA"),
                        rs.getBigDecimal("SecondCA"),
                        rs.getBigDecimal("ExamScore"),
                        rs.getBigDecimal("TotalScore"),
                        rs.getString("Grade"),
                        rs.getString("Remarks")
                    );
                    record.setStudentRegNo(rs.getString("StudentRegNo"));
                    records.add(record);
                }
            }
        }
        return records;
    }

    /**
     * Pulls students registered in a class who don't have records yet for a specific subject/term.
     * Pulls ApplicationID from the Students table.
     */
    public List<AcademicRecord> generateBlankSheetForClass(String currentClass, String session, String term, String subject) throws SQLException {
        List<AcademicRecord> records = new ArrayList<>();
        
        // 🌟 FIXED: Replaced 'StudentRegNo' selector with 'ApplicationID' to mirror your student file repository columns
        String sql = "SELECT StudentID, ApplicationID FROM Students " +
                     "WHERE CurrentClass = ? AND IsActive = 1 " +
                     "AND StudentID NOT IN ( " +
                     "    SELECT StudentID FROM AcademicRecords " +
                     "    WHERE AcademicYear = ? AND Term = ? AND SubjectName = ?" +
                     ")";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, currentClass);
            stmt.setString(2, session);
            stmt.setString(3, term);
            stmt.setString(4, subject);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    AcademicRecord record = new AcademicRecord(
                        rs.getInt("StudentID"),
                        session,
                        term,
                        subject,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "F9", 
                        "No Assessment"
                    );
                    // 🌟 FIXED: Extracted using the active 'ApplicationID' column string matching key
                    record.setStudentRegNo(rs.getString("ApplicationID"));
                    records.add(record);
                }
            }
        }
        return records;
    }

    /**
     * Executes optimized SQL batch queries to process records simultaneously.
     * Automatically handles both INSERTs for new marks and UPDATEs for existing marks.
     */
    public void saveOrUpdateBatch(List<AcademicRecord> records) throws SQLException {
        // 🌟 FIXED: Added 'CurrentClass' column and its parameter placeholder (?) to the insert statement
        String insertSql = "INSERT INTO AcademicRecords (StudentID, StudentRegNo, CurrentClass, AcademicYear, Term, SubjectName, " +
                           "FirstCA, SecondCA, ExamScore, Grade, Remarks) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
        // 🌟 FIXED: Added 'CurrentClass = ?' to the update statement to ensure edits reflect class adjustments
        String updateSql = "UPDATE AcademicRecords SET CurrentClass = ?, FirstCA = ?, SecondCA = ?, ExamScore = ?, Grade = ?, " +
                           "Remarks = ? WHERE RecordID = ?";

        Connection conn = null;
        PreparedStatement insertStmt = null;
        PreparedStatement updateStmt = null;

        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false); 

            insertStmt = conn.prepareStatement(insertSql);
            updateStmt = conn.prepareStatement(updateSql);

            boolean hasInserts = false;
            boolean hasUpdates = false;

            for (AcademicRecord record : records) {
                if (record.getRecordId() == 0) {
                    // New record needing database insertion
                    insertStmt.setInt(1, record.getStudentId());
                    insertStmt.setString(2, record.getStudentRegNo()); 
                    insertStmt.setString(3, record.getCurrentClass()); // 🌟 FIXED: Injected CurrentClass value
                    insertStmt.setString(4, record.getAcademicYear());
                    insertStmt.setString(5, record.getAcademicTerm()); 
                    insertStmt.setString(6, record.getSubjectName());
                    insertStmt.setBigDecimal(7, record.getFirstCA());
                    insertStmt.setBigDecimal(8, record.getSecondCA());
                    insertStmt.setBigDecimal(9, record.getExamScore());
                    insertStmt.setString(10, record.getGrade());
                    insertStmt.setString(11, record.getRemarks());
                    insertStmt.addBatch();
                    hasInserts = true;
                } else {
                    // Existing record needing modification updates
                    updateStmt.setString(1, record.getCurrentClass()); // 🌟 FIXED: Injected CurrentClass value
                    updateStmt.setBigDecimal(2, record.getFirstCA());
                    updateStmt.setBigDecimal(3, record.getSecondCA());
                    updateStmt.setBigDecimal(4, record.getExamScore());
                    updateStmt.setString(5, record.getGrade());
                    updateStmt.setString(6, record.getRemarks());
                    updateStmt.setInt(7, record.getRecordId());
                    updateStmt.addBatch();
                    hasUpdates = true;
                }
            }

            if (hasInserts) insertStmt.executeBatch();
            if (hasUpdates) updateStmt.executeBatch();

            conn.commit(); 
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback(); 
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
            throw e;
        } finally {
            if (insertStmt != null) insertStmt.close();
            if (updateStmt != null) updateStmt.close();
            if (conn != null) conn.close();
        }
    }
}