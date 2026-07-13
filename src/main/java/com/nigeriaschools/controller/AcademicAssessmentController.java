package com.nigeriaschools.controller;

import com.nigeriaschools.dao.AcademicRecordDAO;
import com.nigeriaschools.dao.TeacherDAO;
import com.nigeriaschools.model.AcademicRecord;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.converter.BigDecimalStringConverter;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

public class AcademicAssessmentController {

    // FXML Gate and Sheet Container Controls
    @FXML private Label userBrandingLabel;
    @FXML private HBox loginGatePane;
    @FXML private BorderPane gradingViewPane;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label gateErrorLabel;
    
    // FXML Top Filter Controls
    @FXML private ComboBox<String> classComboBox;
    @FXML private ComboBox<String> sessionComboBox;
    @FXML private ComboBox<String> termComboBox;
    @FXML private ComboBox<String> subjectComboBox;
    @FXML private Button loadSheetButton;
    @FXML private Button saveChangesButton;
    @FXML private ProgressIndicator progressIndicator;

    // FXML Interactive Table Grid
    @FXML private TableView<AcademicRecord> gradingTableView;
    @FXML private TableColumn<AcademicRecord, String> regNoColumn;
    @FXML private TableColumn<AcademicRecord, BigDecimal> firstCAColumn;
    @FXML private TableColumn<AcademicRecord, BigDecimal> secondCAColumn;
    @FXML private TableColumn<AcademicRecord, BigDecimal> examScoreColumn;
    @FXML private TableColumn<AcademicRecord, BigDecimal> totalScoreColumn;
    @FXML private TableColumn<AcademicRecord, String> gradeColumn;
    @FXML private TableColumn<AcademicRecord, String> remarksColumn;

    private final AcademicRecordDAO recordDAO = new AcademicRecordDAO();
    private final TeacherDAO teacherDAO = new TeacherDAO();
    private final ObservableList<AcademicRecord> masterRecordsList = FXCollections.observableArrayList();
    
    @FXML
    public void initialize() {
        setupDropdownFilters();
        configureTableStructure();
    }

    /**
     * Initializes localized operational values for school filters.
     */
    private void setupDropdownFilters() {
        classComboBox.setItems(FXCollections.observableArrayList("JS1", "JS2", "JS3", "SS1", "SS2", "SS3"));
        sessionComboBox.setItems(FXCollections.observableArrayList("2025/2026", "2026/2027"));
        termComboBox.setItems(FXCollections.observableArrayList("First", "Second", "Third"));
        subjectComboBox.setItems(FXCollections.observableArrayList("Mathematics", "English Language", "Civic Education", "Data Processing", "Basic Science"));
    }

    /**
     * Prepares table columns for spreadsheet-like inline editing and data binding.
     */
    private void configureTableStructure() {
        gradingTableView.setEditable(true);
        gradingTableView.setItems(masterRecordsList);

        // Core Identity Properties
        regNoColumn.setCellValueFactory(cellData -> cellData.getValue().studentRegNoProperty());

        // Continuous Assessment 1 Column Setup
        firstCAColumn.setCellValueFactory(cellData -> cellData.getValue().firstCAProperty());
        firstCAColumn.setCellFactory(TextFieldTableCell.forTableColumn(new BigDecimalStringConverter()));
        firstCAColumn.setOnEditCommit(event -> {
            BigDecimal val = validateScore(event.getNewValue(), 20);
            AcademicRecord record = event.getRowValue();
            record.setFirstCA(val);
            recalculateRowMetrics(record);
            gradingTableView.refresh();
        });

        // Continuous Assessment 2 Column Setup
        secondCAColumn.setCellValueFactory(cellData -> cellData.getValue().secondCAProperty());
        secondCAColumn.setCellFactory(TextFieldTableCell.forTableColumn(new BigDecimalStringConverter()));
        secondCAColumn.setOnEditCommit(event -> {
            BigDecimal val = validateScore(event.getNewValue(), 20);
            AcademicRecord record = event.getRowValue();
            record.setSecondCA(val);
            recalculateRowMetrics(record);
            gradingTableView.refresh();
        });

        // Exam Column Setup
        examScoreColumn.setCellValueFactory(cellData -> cellData.getValue().examScoreProperty());
        examScoreColumn.setCellFactory(TextFieldTableCell.forTableColumn(new BigDecimalStringConverter()));
        examScoreColumn.setOnEditCommit(event -> {
            BigDecimal val = validateScore(event.getNewValue(), 60);
            AcademicRecord record = event.getRowValue();
            record.setExamScore(val);
            recalculateRowMetrics(record);
            gradingTableView.refresh();
        });

        // Automatic Computed Columns
        totalScoreColumn.setCellValueFactory(cellData -> cellData.getValue().totalScoreProperty());
        gradeColumn.setCellValueFactory(cellData -> cellData.getValue().gradeProperty());
        remarksColumn.setCellValueFactory(cellData -> cellData.getValue().remarksProperty());
    }

    /**
     * Enforces continuous assessment and examination caps locally before updating memory objects.
     */
    private BigDecimal validateScore(BigDecimal input, double maxLimit) {
        if (input == null || input.doubleValue() < 0) return BigDecimal.ZERO;
        if (input.doubleValue() > maxLimit) {
            showAlert("Input Limit Warning", "Score truncated to max allowable limit: " + maxLimit, Alert.AlertType.WARNING);
            return BigDecimal.valueOf(maxLimit);
        }
        return input;
    }

    /**
     * Launches advanced modal form popup wizard for entry manipulation.
     */
    @FXML
    private void openAdvancedGradingForm(ActionEvent event) {
        AcademicRecord selectedItem = gradingTableView.getSelectionModel().getSelectedItem();
        if (selectedItem == null) {
            showAlert("Selection Required", "Please click on a student row inside the table grid first before opening the form wizard.", Alert.AlertType.WARNING);
            return;
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/GradeStudentDialog.fxml"));
            Parent root = loader.load();
            
            Stage stage = new Stage();
            stage.setTitle("Form Assessment Input Wizard");
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(gradingTableView.getScene().getWindow());
            stage.setScene(new Scene(root));
            
            GradeStudentDialogController controller = loader.getController();
            controller.setAcademicRecord(selectedItem);
            
            stage.showAndWait();
            
            if (controller.isApplyClicked()) {
                recalculateRowMetrics(selectedItem);
                gradingTableView.refresh();
            }
        } catch (IOException e) {
            showAlert("Loader Error", "Could not locate GradeStudentDialog.fxml structural asset template.", Alert.AlertType.ERROR);
            e.printStackTrace();
        }
    }

    /**
     * Authenticates staff credentials directly within the assessment module interface.
     */
    @FXML
    private void handleTeacherLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();

        if (username.isEmpty() || password.isEmpty()) {
            gateErrorLabel.setText("⚠️ Input complete staff tokens!");
            return;
        }

        gateErrorLabel.setText("");
        progressIndicator.setVisible(true);

        new Thread(() -> {
            try {
                boolean isValid = teacherDAO.validateLogin(username, password);
                
                Platform.runLater(() -> {
                    progressIndicator.setVisible(false);
                    if (isValid) {
                        String currentStaff = username.toUpperCase();
                        setBrandingUsername(currentStaff);
                        usernameField.clear();
                        passwordField.clear();
                        loginGatePane.setVisible(false);
                        gradingViewPane.setVisible(true);
                    } else {
                        gateErrorLabel.setText("❌ Authorization Denied. Check credentials.");
                    }
                });
            } catch (SQLException e) {
                Platform.runLater(() -> {
                    progressIndicator.setVisible(false);
                    gateErrorLabel.setText("❌ Pipeline Error: Unable to access database connection.");
                });
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * Closes the active grading session and locks the interface.
     */
    @FXML
    private void handleTeacherLogout() {
        masterRecordsList.clear();
        gradingViewPane.setVisible(false);
        loginGatePane.setVisible(true);
    }
    
    /**
     * Updates the user banner text dynamically with authenticated staff metadata
     */
    public void setBrandingUsername(String username) {
        Platform.runLater(() -> {
            if (userBrandingLabel != null) {
                userBrandingLabel.setText("👤 ACTIVE PORTAL TERMINAL ACCOUNT: " + username + " (Staff Authenticated Verified)");
            }
        });
    }
    
    /**
     * Dynamically evaluates totals, grades, and remarks locally as fields update.
     */
    private void recalculateRowMetrics(AcademicRecord record) {
        // 1. Safe context injection from current top-level UI filters
        String currentClass = classComboBox.getValue();
        String currentSession = sessionComboBox.getValue();
        String currentTerm = termComboBox.getValue();
        String currentSubject = subjectComboBox.getValue();

        record.setCurrentClass(currentClass != null ? currentClass : "");
        record.setAcademicYear(currentSession != null ? currentSession : "");
        record.setAcademicTerm(currentTerm != null ? currentTerm : "");
        record.setSubjectName(currentSubject != null ? currentSubject : "");

        // 2. Performance Metric Calculations
        BigDecimal total = record.getFirstCA().add(record.getSecondCA()).add(record.getExamScore());
        record.setTotalScore(total);

        boolean isSenior = currentClass != null && currentClass.startsWith("SS");
        double score = total.doubleValue();

        String calculatedGrade;
        String localizedRemark;

        if (isSenior) {
            if (score >= 75) { calculatedGrade = "A1"; localizedRemark = "Excellent"; }
            else if (score >= 70) { calculatedGrade = "B2"; localizedRemark = "Very Good"; }
            else if (score >= 65) { calculatedGrade = "B3"; localizedRemark = "Good"; }
            else if (score >= 60) { calculatedGrade = "C4"; localizedRemark = "Credit"; }
            else if (score >= 55) { calculatedGrade = "C5"; localizedRemark = "Credit"; }
            else if (score >= 50) { calculatedGrade = "C6"; localizedRemark = "Credit"; }
            else if (score >= 45) { calculatedGrade = "D7"; localizedRemark = "Pass"; }
            else if (score >= 40) { calculatedGrade = "E8"; localizedRemark = "Pass"; }
            else { calculatedGrade = "F9"; localizedRemark = "Fail"; }
        } else {
            if (score >= 70) { calculatedGrade = "A"; localizedRemark = "Distinction"; }
            else if (score >= 60) { calculatedGrade = "B"; localizedRemark = "Upper Credit"; }
            else if (score >= 50) { calculatedGrade = "C"; localizedRemark = "Lower Credit"; }
            else if (score >= 40) { calculatedGrade = "P"; localizedRemark = "Pass"; }
            else { calculatedGrade = "F"; localizedRemark = "Fail"; }
        }

        record.setGrade(calculatedGrade);
        record.setRemarks(localizedRemark);
    }

    /**
     * Loads existing rows or auto-generates a missing roster blueprint on click action.
     */
    @FXML
    private void handleLoadSheet() {
        String targetClass = classComboBox.getValue();
        String session = sessionComboBox.getValue();
        String term = termComboBox.getValue();
        String subject = subjectComboBox.getValue();

        if (targetClass == null || session == null || term == null || subject == null) {
            showAlert("Filter Error", "Please select all dropdown parameters before loading.", Alert.AlertType.ERROR);
            return;
        }

        progressIndicator.setVisible(true);
        masterRecordsList.clear();

        Task<List<AcademicRecord>> loadTask = new Task<>() {
            @Override
            protected List<AcademicRecord> call() throws Exception {
                List<AcademicRecord> existing = recordDAO.getRecordsByFilter(targetClass, session, term, subject);
                if (existing.isEmpty()) {
                    return recordDAO.generateBlankSheetForClass(targetClass, session, term, subject);
                }
                return existing;
            }
        };

        loadTask.setOnSucceeded(e -> {
            progressIndicator.setVisible(false);
            masterRecordsList.addAll(loadTask.getValue());
            if (masterRecordsList.isEmpty()) {
                showAlert("Roster Info", "No active students found matching the selected class.", Alert.AlertType.INFORMATION);
            }
        });

        loadTask.setOnFailed(e -> {
            progressIndicator.setVisible(false);
            showAlert("Database Error", "Failed to retrieve records: " + loadTask.getException().getMessage(), Alert.AlertType.ERROR);
        });

        new Thread(loadTask).start();
    }

    /**
     * Aggregates the UI dataset rows and pushes them securely into the backend batch compiler pipeline.
     */
    @FXML
    private void handleSaveChanges() {
        if (masterRecordsList.isEmpty()) {
            showAlert("Action Denied", "No data rows available to save.", Alert.AlertType.WARNING);
            return;
        }

        progressIndicator.setVisible(true);

        // Fetch active workspace parameters before thread isolation
        String currentClass = classComboBox.getValue();
        String currentSession = sessionComboBox.getValue();
        String currentTerm = termComboBox.getValue();
        String currentSubject = subjectComboBox.getValue();

        Task<Void> saveTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                // Safeguard: Ensure every list item contains the selected filter tags
                for (AcademicRecord record : masterRecordsList) {
                    if (record.getCurrentClass() == null || record.getCurrentClass().isEmpty()) {
                        record.setCurrentClass(currentClass);
                    }
                    if (record.getAcademicYear() == null || record.getAcademicYear().isEmpty()) {
                        record.setAcademicYear(currentSession);
                    }
                    if (record.getAcademicTerm() == null || record.getAcademicTerm().isEmpty()) {
                        record.setAcademicTerm(currentTerm);
                    }
                    if (record.getSubjectName() == null || record.getSubjectName().isEmpty()) {
                        record.setSubjectName(currentSubject);
                    }
                }
                
                recordDAO.saveOrUpdateBatch(masterRecordsList);
                return null;
            }
        };

        saveTask.setOnSucceeded(e -> {
            progressIndicator.setVisible(false);
            showAlert("Success", "Academic processing complete! All records synchronized safely.", Alert.AlertType.INFORMATION);
            handleLoadSheet();
        });

        saveTask.setOnFailed(e -> {
            progressIndicator.setVisible(false);
            showAlert("Pipeline Failure", "Batch sync failed: " + saveTask.getException().getMessage(), Alert.AlertType.ERROR);
        });

        new Thread(saveTask).start();
    }

    private void showAlert(String title, String message, Alert.AlertType type) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
}
