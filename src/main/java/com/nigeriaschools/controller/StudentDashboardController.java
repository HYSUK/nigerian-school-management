package com.nigeriaschools.controller;

import com.nigeriaschools.dao.StudentDAO;
import com.nigeriaschools.model.Student;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import com.nigeriaschools.dao.FeeDAO;
import com.nigeriaschools.dao.ResultDAO;
import com.nigeriaschools.dao.StudentDAO;
import com.nigeriaschools.model.Student;
import com.nigeriaschools.model.AcademicRecord;


public class StudentDashboardController {

    @FXML private TabPane mainTabPane;

    // Profile tab fields
    @FXML private ImageView studentImageView;
    @FXML private Label studentNameLabel;
    @FXML private Label studentClassLabel;
    @FXML private Label studentStateLabel;

    // Results tab fields
    @FXML private TableView<ResultRow> resultsTable;
    @FXML private TableColumn<ResultRow, String> subjectCol;
    @FXML private TableColumn<ResultRow, String> scoreCol;
    @FXML private TableColumn<ResultRow, String> gradeCol;

    // Fees tab fields
    @FXML private Label feesStatusLabel;

    // Messages tab fields
    @FXML private ListView<String> messagesList;

    // Sidebar buttons
    @FXML private Button profileBtn;
    @FXML private Button resultsBtn;
    @FXML private Button feesBtn;
    @FXML private Button messagesBtn;
    @FXML private Button printAdmissionBtn;
    
    private final ResultDAO resultDAO = new ResultDAO();
    private final FeeDAO feeDAO = new FeeDAO();
    private final StudentDAO studentDAO = new StudentDAO();
    private Student loggedInStudent;

    // Called after login to set student data
    public void setLoggedInStudent(Student student) {
        this.loggedInStudent = student;
        populateProfile();
        populateResults();
        populateFees();
        populateMessages();
    }

    @FXML
    public void initialize() {
        // Setup table columns
        subjectCol.setCellValueFactory(cellData -> cellData.getValue().subjectProperty());
        scoreCol.setCellValueFactory(cellData -> cellData.getValue().scoreProperty());
        gradeCol.setCellValueFactory(cellData -> cellData.getValue().gradeProperty());
    }

    // Sidebar navigation
    @FXML
    void switchToProfile() {
        mainTabPane.getSelectionModel().select(0);
    }

    @FXML
    void switchToResults() {
        mainTabPane.getSelectionModel().select(1);
    }

    @FXML
    void switchToFees() {
        mainTabPane.getSelectionModel().select(2);
    }

    @FXML
    void switchToMessages() {
        mainTabPane.getSelectionModel().select(3);
    }
    
    
    @FXML
    private void handlePrintAdmissionLetter() {
        if (loggedInStudent == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "No student logged in!");
            alert.showAndWait();
            return;
        }

        try {
            // Call your AdmissionLetterEngine utility
            String path = com.nigeriaschools.util.AdmissionLetterEngine.generateAdmissionLetter(loggedInStudent);

            // Auto-open the generated PDF
            try {
                java.awt.Desktop.getDesktop().open(new java.io.File(path));
            } catch (Exception e) {
                System.err.println("⚠ PDF saved but could not open viewer: " + e.getMessage());
            }

            Alert success = new Alert(Alert.AlertType.INFORMATION);
            success.setTitle("Admission Letter Ready");
            success.setHeaderText("Admission Letter Generated Successfully!");
            success.setContentText("The admission letter has been saved to:\n" + path);
            success.showAndWait();

        } catch (Exception e) {
            Alert error = new Alert(Alert.AlertType.ERROR, "Failed to generate admission letter: " + e.getMessage());
            error.showAndWait();
            e.printStackTrace();
        }
    }


    // Populate tabs
    private void populateProfile() {
        if (loggedInStudent != null) {
            studentNameLabel.setText("Name: " + loggedInStudent.getFirstName() + " " + loggedInStudent.getLastName());
            studentClassLabel.setText("Class: " + loggedInStudent.getCurrentClass());
            studentStateLabel.setText("State of Origin: " + loggedInStudent.getStateOfOrigin());

            Image img = null;

            // Try local path first
            if (loggedInStudent.getLocalPassportPath() != null && !loggedInStudent.getLocalPassportPath().isEmpty()) {
                try {
                    img = new Image("file:" + loggedInStudent.getLocalPassportPath());
                } catch (Exception e) {
                    System.err.println("⚠ Could not load student image from path: " + e.getMessage());
                }
            }
            // Try Base64 if available
            else if (loggedInStudent.getWebPassportBase64() != null && !loggedInStudent.getWebPassportBase64().isEmpty()) {
                try {
                    byte[] decodedBytes = java.util.Base64.getDecoder().decode(loggedInStudent.getWebPassportBase64());
                    img = new Image(new java.io.ByteArrayInputStream(decodedBytes));
                } catch (Exception e) {
                    System.err.println("⚠ Could not decode student image: " + e.getMessage());
                }
            }

            // Fallback to default placeholder
            if (img == null) {
                try {
                    img = new Image(getClass().getResource("/assets/default.png").toExternalForm());
                } catch (Exception e) {
                    System.err.println("⚠ Could not load default placeholder image: " + e.getMessage());
                }
            }
            studentImageView.setImage(img);

        }
    }


    private void populateResults() {
        if (loggedInStudent != null) {
            var results = resultDAO.getResultsByStudentId(loggedInStudent.getStudentId());

            var rows = new java.util.ArrayList<ResultRow>();
            for (AcademicRecord r : results) {
                rows.add(new ResultRow(
                        r.getSubjectName(),
                        String.valueOf(r.getTotalScore()),
                        r.getGrade()
                ));
            }

            resultsTable.setItems(FXCollections.observableArrayList(rows));
        }
    }


    private void populateFees() {
        if (loggedInStudent != null) {
            double outstanding = feeDAO.getOutstandingFees(loggedInStudent.getStudentId());
            feesStatusLabel.setText("Outstanding Fees: ₦" + outstanding);
        }
    }

    private void populateMessages() {
        // Example static data — replace with DAO query later
        messagesList.setItems(FXCollections.observableArrayList(
                "Welcome to the new session!",
                "Exam timetable will be released next week.",
                "School fees deadline: July 15th."
        ));
    }

    // Inner class for results table rows
    public static class ResultRow {
        private final javafx.beans.property.SimpleStringProperty subject;
        private final javafx.beans.property.SimpleStringProperty score;
        private final javafx.beans.property.SimpleStringProperty grade;

        public ResultRow(String subject, String score, String grade) {
            this.subject = new javafx.beans.property.SimpleStringProperty(subject);
            this.score = new javafx.beans.property.SimpleStringProperty(score);
            this.grade = new javafx.beans.property.SimpleStringProperty(grade);
        }

        public javafx.beans.property.StringProperty subjectProperty() { return subject; }
        public javafx.beans.property.StringProperty scoreProperty() { return score; }
        public javafx.beans.property.StringProperty gradeProperty() { return grade; }
    }
}
