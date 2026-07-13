package com.nigeriaschools.dao;

import com.nigeriaschools.model.SchoolFeesPayment;
import com.nigeriaschools.util.DatabaseConnection;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class SchoolFeesPaymentDAO {

    /**
     * 🔥 NEW VALIDATION METHOD: Checks if a student is fully cleared financially for report card printing.
     * Intercepts compilation downstream if the balance deficit is greater than zero.
     */
    public boolean isStudentFinanciallyCleared(String studentRegNo, String academicYear, String term) throws SQLException {
        // Matches your exact schema configuration column keys ('Balance', 'PaymentStatus')
        String sql = "SELECT Balance, PaymentStatus FROM SchoolFeesPayments " +
                     "WHERE StudentRegNo = ? AND AcademicYear = ? AND Term = ?";
        
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, studentRegNo);
            stmt.setString(2, academicYear);
            stmt.setString(3, term);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    BigDecimal balanceDeficit = rs.getBigDecimal("Balance");
                    String paymentStatus = rs.getString("PaymentStatus");

                    // Clearance condition rule: marked 'Fully Paid' or holds zero/negative balance deficit
                    if ("Fully Paid".equalsIgnoreCase(paymentStatus) || 
                        (balanceDeficit != null && balanceDeficit.compareTo(BigDecimal.ZERO) <= 0)) {
                        return true;
                    }
                }
            }
        }
        return false; // Blocks report generation for any profile missing ledger entries entirely
    }

    /**
     * Fetches the standard fee configuration for a specific section, year, and term.
     */
    public BigDecimal getStandardFee(String schoolSection, String academicYear, String term) throws SQLException {
        String sql = "SELECT StandardTermFee FROM FeeConfigurations WHERE SchoolSection = ? AND AcademicYear = ? AND Term = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, schoolSection);
            stmt.setString(2, academicYear);
            stmt.setString(3, term);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getBigDecimal("StandardTermFee");
                }
            }
        }
        return BigDecimal.ZERO; 
    }

    /**
     * Pulls the existing financial records for a given class filter.
     */
    public List<SchoolFeesPayment> getPaymentsByFilter(String currentClass, String academicYear, String term) throws SQLException {
        List<SchoolFeesPayment> payments = new ArrayList<>();
        String sql = "SELECT PaymentID, StudentID, StudentRegNo, CurrentClass, SchoolSection, AcademicYear, Term, " +
                     "AmountDue, AmountPaid, Balance, PaymentStatus " +
                     "FROM SchoolFeesPayments " +
                     "WHERE CurrentClass = ? AND AcademicYear = ? AND Term = ?";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, currentClass);
            stmt.setString(2, academicYear);
            stmt.setString(3, term);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    payments.add(new SchoolFeesPayment(
                        rs.getInt("PaymentID"),
                        rs.getInt("StudentID"),
                        rs.getString("StudentRegNo"),
                        rs.getString("CurrentClass"),
                        rs.getString("SchoolSection"),
                        rs.getString("AcademicYear"),
                        rs.getString("Term"),
                        rs.getBigDecimal("AmountDue"),
                        rs.getBigDecimal("AmountPaid"),
                        rs.getBigDecimal("Balance"),
                        rs.getString("PaymentStatus")
                    ));
                }
            }
        }
        return payments;
    }

    /**
     * Scans the student registry roster, automatically identifying which pupils lack payment entries
     * for the selected term, and auto-generates their blank-slate ledger invoices instantly.
     */
    public List<SchoolFeesPayment> generateBlankLedgerForClass(String currentClass, String academicYear, String term) throws SQLException {
        List<SchoolFeesPayment> freshLedger = new ArrayList<>();
        
        String section = currentClass.startsWith("SS") ? "SS" : "JS";
        BigDecimal amountDue = getStandardFee(section, academicYear, term);

        String sql = "SELECT StudentID, ApplicationID FROM Students " +
                     "WHERE CurrentClass = ? AND IsActive = 1 " +
                     "AND StudentID NOT IN ( " +
                     "    SELECT StudentID FROM SchoolFeesPayments " +
                     "    WHERE AcademicYear = ? AND Term = ?" +
                     ")";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, currentClass);
            stmt.setString(2, academicYear);
            stmt.setString(3, term);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    freshLedger.add(new SchoolFeesPayment(
                        0, 
                        rs.getInt("StudentID"),
                        rs.getString("ApplicationID"),
                        currentClass,
                        section,
                        academicYear,
                        term,
                        amountDue,
                        BigDecimal.ZERO,
                        amountDue, 
                        "Unpaid"
                    ));
                }
            }
        }
        return freshLedger;
    }

    /**
     * Executes transaction blocks via SQL batch processing to save or update entries simultaneously.
     */
    public void saveOrUpdatePaymentsBatch(List<SchoolFeesPayment> payments) throws SQLException {
        String insertSql = "INSERT INTO SchoolFeesPayments (StudentID, StudentRegNo, CurrentClass, SchoolSection, " +
                           "AcademicYear, Term, AmountDue, AmountPaid) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        
        String updateSql = "UPDATE SchoolFeesPayments SET AmountPaid = ? WHERE PaymentID = ?";

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

            for (SchoolFeesPayment payment : payments) {
                if (payment.getPaymentId() == 0) {
                    insertStmt.setInt(1, payment.getStudentId());
                    insertStmt.setString(2, payment.getStudentRegNo());
                    insertStmt.setString(3, payment.getCurrentClass());
                    insertStmt.setString(4, payment.getSchoolSection());
                    insertStmt.setString(5, payment.getAcademicYear());
                    insertStmt.setString(6, payment.getTerm());
                    insertStmt.setBigDecimal(7, payment.getAmountDue());
                    insertStmt.setBigDecimal(8, payment.getAmountPaid());
                    insertStmt.addBatch();
                    hasInserts = true;
                } else {
                    updateStmt.setBigDecimal(1, payment.getAmountPaid());
                    updateStmt.setInt(2, payment.getPaymentId());
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
