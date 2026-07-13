package com.nigeriaschools.dao;

import com.nigeriaschools.model.Student;
import com.nigeriaschools.util.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class StudentDAO {

    // 1. READ ALL STUDENTS
    public List<Student> getAllStudents() {
        List<Student> students = new ArrayList<>();
        String query = "SELECT * FROM dbo.Students ORDER BY StudentID DESC";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                students.add(mapRowToStudent(rs));
            }
        } catch (SQLException e) {
            System.err.println("❌ Error reading students table: " + e.getMessage());
            e.printStackTrace();
        }
        return students;
    }

    // 2. INSERT NEW STUDENT
    public boolean insertStudent(Student student) {
        String query = "INSERT INTO dbo.Students (ApplicationID, FirstName, LastName, Gender, DateOfBirth, "
                + "StateOfOrigin, LGA, HomeAddress, CurrentClass, GuardianName, GuardianRelationship, "
                + "GuardianPhone, LocalPassportPath, EnrollmentDate, IsActive, StudentPassword, WebPassportBase64, "
                + "PrevPrimarySchool, PrimaryFromYear, PrimaryToYear, PrevJuniorSecSchool, JuniorSecFromYear, JuniorSecToYear) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, student.getApplicationId() != null ? student.getApplicationId().trim() : null);
            stmt.setString(2, student.getFirstName());
            stmt.setString(3, student.getLastName());
            stmt.setString(4, student.getGender());
            stmt.setDate(5, student.getDateOfBirth() != null ? Date.valueOf(student.getDateOfBirth()) : null);
            stmt.setString(6, student.getStateOfOrigin());
            stmt.setString(7, student.getLga());
            stmt.setString(8, student.getHomeAddress());
            stmt.setString(9, student.getCurrentClass());
            stmt.setString(10, student.getGuardianName());
            stmt.setString(11, student.getGuardianRelationship());
            stmt.setString(12, student.getGuardianPhone());
            stmt.setString(13, student.getLocalPassportPath());
            stmt.setTimestamp(14, student.getEnrollmentDate() != null ? Timestamp.valueOf(student.getEnrollmentDate()) : null);
            stmt.setBoolean(15, student.isActive());
            stmt.setString(16, student.getStudentPassword());
            stmt.setString(17, student.getWebPassportBase64());
            stmt.setString(18, student.getPrevPrimarySchool());
            stmt.setString(19, student.getPrimaryFromYear());
            stmt.setString(20, student.getPrimaryToYear());
            stmt.setString(21, student.getPrevJuniorSecSchool());
            stmt.setString(22, student.getJuniorSecFromYear());
            stmt.setString(23, student.getJuniorSecToYear());

            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("❌ Error saving student profile: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // 3. AUTHENTICATE STUDENT
    public boolean authenticate(String usernameOrPin, String password) {
        if (usernameOrPin == null || password == null) return false;
        String cleanInput = usernameOrPin.trim();

        String sql = "SELECT s.StudentPassword " +
                     "FROM Students s " +
                     "WHERE s.IsActive = 1 " +
                     "AND (UPPER(LTRIM(RTRIM(s.ApplicationID))) = UPPER(?) " +
                     "     OR EXISTS (SELECT 1 FROM ScratchCards sc " +
                     "                 WHERE LTRIM(RTRIM(sc.PinNumber)) = ? " +
                     "                 AND UPPER(LTRIM(RTRIM(sc.UsedByApplicationID))) = UPPER(s.ApplicationID)))";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, cleanInput);
            ps.setString(2, cleanInput);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String storedPassword = rs.getString("StudentPassword");
                    return storedPassword != null && storedPassword.equals(password.trim());
                }
            }
        } catch (SQLException e) {
            System.err.println("❌ Database error during authentication: " + e.getMessage());
            e.printStackTrace();
        }
        return false;
    }

    // 4. DUAL-LOOKUP FILTER
    public Student getStudentByDetails(String searchToken) {
        if (searchToken == null) return null;
        String cleanToken = searchToken.trim();

        String sql = "SELECT * FROM dbo.Students WHERE UPPER(LTRIM(RTRIM(ApplicationID))) = UPPER(?) OR StudentPassword = ?";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, cleanToken);
            stmt.setString(2, cleanToken);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapRowToStudent(rs);
                }
            }
        } catch (SQLException e) {
            System.err.println("❌ Database exception while running dual-lookup filter: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    // 🌟 5. UPDATE SCREENING RESULT LOCALLY (Fixes Eclipse red error)
    public boolean updateScreeningResultLocally(String applicationId, int score, int cutoff) {
        if (applicationId == null || applicationId.trim().isEmpty()) return false;

        String status = (score >= cutoff) ? "Passed" : "Failed";
        String query = "UPDATE [SchoolManagementDB].[dbo].[Students] "
                     + "SET ScreeningScore = ?, ExamScore = ?, ScreeningStatus = ? "
                     + "WHERE UPPER(LTRIM(RTRIM(ApplicationID))) = UPPER(?)";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setInt(1, score);
            stmt.setInt(2, score);
            stmt.setString(3, status);
            stmt.setString(4, applicationId.trim());

            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("❌ Error updating local screening score: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // 6. HELPER MAPPER
    private Student mapRowToStudent(ResultSet rs) throws SQLException {
        return new Student(
            rs.getInt("StudentID"),
            rs.getString("ApplicationID"),
            rs.getString("FirstName"),
            rs.getString("LastName"),
            rs.getString("Gender"),
            rs.getDate("DateOfBirth") != null ? rs.getDate("DateOfBirth").toLocalDate() : null,
            rs.getString("StateOfOrigin"),
            rs.getString("LGA"),
            rs.getString("HomeAddress"),
            rs.getString("CurrentClass"),
            rs.getString("GuardianName"),
            rs.getString("GuardianRelationship"),
            rs.getString("GuardianPhone"),
            rs.getString("LocalPassportPath"),
            rs.getTimestamp("EnrollmentDate") != null ? rs.getTimestamp("EnrollmentDate").toLocalDateTime() : null,
            rs.getBoolean("IsActive"),
            rs.getString("StudentPassword"),
            rs.getString("WebPassportBase64"),
            rs.getString("PrevPrimarySchool"),
            rs.getString("PrimaryFromYear"),
            rs.getString("PrimaryToYear"),
            rs.getString("PrevJuniorSecSchool"),
            rs.getString("JuniorSecFromYear"),
            rs.getString("JuniorSecToYear")
        );
    }
}