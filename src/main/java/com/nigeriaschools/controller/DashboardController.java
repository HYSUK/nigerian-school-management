package com.nigeriaschools.controller;

import com.nigeriaschools.dao.ScratchCardDAO;
import com.nigeriaschools.dao.StudentDAO;
import com.nigeriaschools.model.Student;
import com.nigeriaschools.util.AdmissionPdfEngine;
import com.nigeriaschools.util.TerminalReportCardEngine; 
import com.nigeriaschools.sync.HttpSyncClient; 

import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList; 
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

public class DashboardController {

    @FXML private TableView<Student> studentTable;
    @FXML private TableColumn<Student, Integer> idCol;
    @FXML private TableColumn<Student, String> appIdCol;
    @FXML private TableColumn<Student, String> nameCol;
    @FXML private TableColumn<Student, String> classCol;
    @FXML private TableColumn<Student, String> stateCol;

    @FXML private AcademicAssessmentController assessmentTabPaneController;
    @FXML private TabPane mainTabPane;
    @FXML private Tab admissionsTab, scratchCardsTab, assessmentTab, feesTab; 
    @FXML private Button admissionMenuBtn, cardMenuBtn, assessmentMenuBtn, feesMenuBtn, loginMenuBtn; 
    
    // 🏠 HOME PAGE INJECTIONS
    @FXML private Tab homeTab;
    @FXML private Label homeStudentCountLabel;
    @FXML private Button homeMenuBtn; 

    @FXML private TextField printSearchField;

    @FXML private ComboBox<String> sectionBox;
    @FXML private TextField batchField;
    @FXML private TextField countField;
    @FXML private Label statusLabel;

    private final StudentDAO studentDAO = new StudentDAO();
    private final ScratchCardDAO scratchCardDAO = new ScratchCardDAO();
    private String authenticatedUser = "ADMIN";
    
    // 🔒 Security state tracking handle: defaults to false (locked status)
    private final javafx.beans.property.BooleanProperty isAdminPortalUnlocked = new javafx.beans.property.SimpleBooleanProperty(false);
    
    public void setAuthenticatedUser(String username) {
        this.authenticatedUser = username;
        if (assessmentTabPaneController != null) {
            assessmentTabPaneController.setBrandingUsername(username);
        }
    }

    @FXML
    public void initialize() {
        mainTabPane.getStyleClass().add("floating");
        mainTabPane.setStyle("-fx-tab-max-height: 0; -fx-tab-min-height: 0;");
        
        // Boot application directly into Tab 0 (Home Page workspace)
        mainTabPane.getSelectionModel().select(homeTab);
        
        idCol.setCellValueFactory(cellData -> new SimpleIntegerProperty(cellData.getValue().getStudentId()).asObject());
        appIdCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getApplicationId()));
        nameCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getFirstName() + " " + cellData.getValue().getLastName()));
        classCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getCurrentClass()));
        stateCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getStateOfOrigin()));
        
        // Bind disable states to security flag
        admissionMenuBtn.disableProperty().bind(isAdminPortalUnlocked.not());
        cardMenuBtn.disableProperty().bind(isAdminPortalUnlocked.not());
        assessmentMenuBtn.disableProperty().bind(isAdminPortalUnlocked.not());
        feesMenuBtn.disableProperty().bind(isAdminPortalUnlocked.not());
        
        loadStudentData();
    }
    
    @FXML
    void switchToHome(ActionEvent event) {
        mainTabPane.getSelectionModel().select(homeTab);
        resetSidebarStyles();
        if (homeMenuBtn != null) {
            homeMenuBtn.setStyle("-fx-background-color: #17b978; -fx-text-fill: white; -fx-font-weight: bold; -fx-alignment: LEFT; -fx-cursor: hand;");
        }
    }

    @FXML
    void openStudentLoginDialog(ActionEvent event) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/StudentLogin.fxml"));
            Parent root = loader.load();

            Stage loginStage = new Stage();
            loginStage.setTitle("Student Portal Login");
            loginStage.initModality(Modality.WINDOW_MODAL);
            loginStage.initOwner(mainTabPane.getScene().getWindow());
            loginStage.setScene(new Scene(root));

            StudentLoginController loginController = loader.getController();
            loginStage.showAndWait();

            if (loginController.isAuthenticated()) {
                Student loggedInStudent = loginController.getLoggedInStudent();

                FXMLLoader dashLoader = new FXMLLoader(getClass().getResource("/StudentDashboard.fxml"));
                Parent dashRoot = dashLoader.load();

                StudentDashboardController dashController = dashLoader.getController();
                dashController.setLoggedInStudent(loggedInStudent);

                Stage dashStage = new Stage();
                dashStage.setTitle("Student Dashboard");
                dashStage.setScene(new Scene(dashRoot));
                dashStage.show();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    @FXML
    private void handleLinkToAdminPortal(ActionEvent event) {
        isAdminPortalUnlocked.set(true);
        mainTabPane.getSelectionModel().select(admissionsTab);
        resetSidebarStyles();
        admissionMenuBtn.setStyle("-fx-background-color: #17b978; -fx-text-fill: white; -fx-font-weight: bold; -fx-alignment: LEFT; -fx-cursor: hand;");
    }

    @FXML
    private void handleInstantPdfPrint(ActionEvent event) {
        String searchToken = printSearchField.getText().trim();

        if (searchToken.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "Please input a valid student Application ID or Portal Security PIN token first!");
            alert.showAndWait();
            return;
        }

        Student matchingStudent = studentDAO.getStudentByDetails(searchToken);

        if (matchingStudent != null) {
            try {
                String cleanAppId = matchingStudent.getApplicationId().trim().replace("/", "_").replace("\\", "_");
                
                File outputFolder = new File(System.getProperty("user.home") + File.separator + "SchoolSystem" + File.separator + "clearance_slips");
                if (!outputFolder.exists()) {
                    outputFolder.mkdirs();
                }
                
                File targetPdfFile = new File(outputFolder, cleanAppId + "-Clearance.pdf");
                
                com.nigeriaschools.util.AdmissionPdfEngine.generateAdmissionSlipPdf(matchingStudent, matchingStudent.getStudentPassword(), targetPdfFile);
                
                printSearchField.clear(); 
                
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(targetPdfFile);
                } else {
                    System.err.println("❌ System AWT Desktop is unsupported on this platform instance.");
                }
                
                Alert successAlert = new Alert(Alert.AlertType.INFORMATION);
                successAlert.setTitle("Clearance Printed");
                successAlert.setHeaderText("Admission Dossier PDF Re-Generated Successfully!");
                successAlert.setContentText("The document has been saved to:\n" + targetPdfFile.getAbsolutePath());
                successAlert.showAndWait();
                
            } catch (IOException e) {
                Alert errAlert = new Alert(Alert.AlertType.ERROR, "Failed to compile background PDF content stream: " + e.getMessage());
                errAlert.showAndWait();
                e.printStackTrace();
            }
        } else {
            Alert failAlert = new Alert(Alert.AlertType.ERROR, "No student profile registry match found within database rows!");
            failAlert.showAndWait();
        }
    }
   
    private void updateHomeDashboardMetrics(int totalStudentsCount) {
        javafx.application.Platform.runLater(() -> {
            if (homeStudentCountLabel != null) {
                homeStudentCountLabel.setText(totalStudentsCount + " Enrolled Students");
            }
        });
    }

    @FXML 
    void switchToAdmissions(ActionEvent event) {
        mainTabPane.getSelectionModel().select(admissionsTab);
        resetSidebarStyles();
        admissionMenuBtn.setStyle("-fx-background-color: #17b978; -fx-text-fill: white; -fx-font-weight: bold; -fx-alignment: LEFT; -fx-cursor: hand;");
    }

    @FXML 
    void switchToScratchCards(ActionEvent event) {
        mainTabPane.getSelectionModel().select(scratchCardsTab);
        resetSidebarStyles();
        cardMenuBtn.setStyle("-fx-background-color: #17b978; -fx-text-fill: white; -fx-font-weight: bold; -fx-alignment: LEFT; -fx-cursor: hand;");
    }

    @FXML
    void switchToAssessment(ActionEvent event) {
        mainTabPane.getSelectionModel().select(assessmentTab);
        resetSidebarStyles();
        assessmentMenuBtn.setStyle("-fx-background-color: #17b978; -fx-text-fill: white; -fx-font-weight: bold; -fx-alignment: LEFT; -fx-cursor: hand;");
    }

    @FXML
    void switchToFees(ActionEvent event) {
        mainTabPane.getSelectionModel().select(feesTab);
        resetSidebarStyles();
        feesMenuBtn.setStyle("-fx-background-color: #17b978; -fx-text-fill: white; -fx-font-weight: bold; -fx-alignment: LEFT; -fx-cursor: hand;");
    }

    private void resetSidebarStyles() {
        if(homeMenuBtn != null) homeMenuBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #e8e8e8; -fx-alignment: LEFT; -fx-cursor: hand;");
        admissionMenuBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #e8e8e8; -fx-alignment: LEFT; -fx-cursor: hand;");
        cardMenuBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #e8e8e8; -fx-alignment: LEFT; -fx-cursor: hand;");
        assessmentMenuBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #e8e8e8; -fx-alignment: LEFT; -fx-cursor: hand;");
        feesMenuBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #e8e8e8; -fx-alignment: LEFT; -fx-cursor: hand;");
    }

    @FXML
    void openRegistrationDialog(ActionEvent event) {
        try {
            FXMLLoader preLoader = new FXMLLoader(getClass().getResource("/PreValidateDialog.fxml"));
            Parent preRoot = preLoader.load();
            
            Stage preStage = new Stage();
            preStage.setTitle("Portal Access Pre-Validation");
            preStage.initModality(Modality.WINDOW_MODAL);
            preStage.initOwner(studentTable.getScene().getWindow());
            preStage.setScene(new Scene(preRoot));
            
            PreValidateController preController = preLoader.getController();
            preStage.showAndWait();
            
            if (preController.isValidated()) {
                FXMLLoader regLoader = new FXMLLoader(getClass().getResource("/RegisterDialog.fxml"));
                Parent regRoot = regLoader.load();
                
                Stage regStage = new Stage();
                regStage.setTitle("Student Admission Profile Intake Form");
                regStage.initModality(Modality.WINDOW_MODAL);
                regStage.initOwner(studentTable.getScene().getWindow());
                regStage.setScene(new Scene(regRoot));
                
                RegisterDialogController regController = regLoader.getController();
                regController.setPreValidatedData(
                    preController.getGeneratedAppId(),
                    preController.getGeneratedPassword(),
                    preController.getValidatedClass()
                );
                
                regStage.showAndWait();
                
                if (regController.isSaveClicked()) {
                    loadStudentData();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void handleAssignScreeningScore(ActionEvent event) {
        Student selectedStudent = studentTable.getSelectionModel().getSelectedItem();
        if (selectedStudent == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "Please select a student from the table first!");
            alert.showAndWait();
            return;
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/ScreeningDialog.fxml"));
            Parent root = loader.load();

            ScreeningDialogController controller = loader.getController();
            controller.setStudent(selectedStudent);

            Stage dialogStage = new Stage();
            dialogStage.setTitle("Screening Result Entry");
            dialogStage.initModality(Modality.WINDOW_MODAL);
            dialogStage.initOwner(studentTable.getScene().getWindow());
            dialogStage.setScene(new Scene(root));
            dialogStage.showAndWait();

            // Refresh main view if needed
            loadStudentData();

        } catch (IOException e) {
            e.printStackTrace();
            Alert alert = new Alert(Alert.AlertType.ERROR, "Error opening Screening Dialog: " + e.getMessage());
            alert.showAndWait();
        }
    }
    
    @FXML
    void handleGenerateCards(ActionEvent event) {
        String selectedSection = sectionBox.getValue();
        String batchNum = batchField.getText();
        String countText = countField.getText();

        if (selectedSection == null || batchNum.trim().isEmpty() || countText.trim().isEmpty()) {
            statusLabel.setStyle("-fx-text-fill: #c62828;");
            statusLabel.setText("⚠️ Complete all card properties!");
            return;
        }
        try {
            int cardCount = Integer.parseInt(countText);
            if (scratchCardDAO.generateBulkCards(cardCount, selectedSection, batchNum)) {
                statusLabel.setStyle("-fx-text-fill: #2e7d32;");
                statusLabel.setText("✅ Batch " + batchNum + " saved successfully inside SSMS!");
                batchField.clear();
            }
        } catch (Exception e) {
            statusLabel.setText("⚠️ Invalid card counts!");
        }
    }

    @FXML
    private void handleMobileCloudSync(ActionEvent event) {
        System.out.println("🔄 Initializing background mobile registration import task...");
        Alert loadingAlert = new Alert(Alert.AlertType.INFORMATION, "Connecting to mobile gateway cloud pipeline...", ButtonType.OK);
        loadingAlert.show();
        
        new Thread(() -> {
            try {
                HttpSyncClient syncClient = new HttpSyncClient();
                String jsonResponse = syncClient.fetchPendingRegistrations();
                
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
                
                List<java.util.Map<String, Object>> remoteStudents = mapper.readValue(jsonResponse,
                    new com.fasterxml.jackson.core.type.TypeReference<List<java.util.Map<String, Object>>>() {});
                    
                if (remoteStudents.isEmpty()) {
                    javafx.application.Platform.runLater(() -> {
                        loadingAlert.close();
                        new Alert(Alert.AlertType.INFORMATION, "No new parent mobile registrations available to import.").showAndWait();
                    });
                    return;
                }

                List<Integer> successfullyImportedIds = new ArrayList<>();
                int importedCount = 0;

                String localPassportDir = System.getProperty("user.home") + File.separator + "SchoolSystem" + File.separator + "passports";
                File dir = new File(localPassportDir);
                if (!dir.exists()) {
                    dir.mkdirs();
                }

                for (java.util.Map<String, Object> remote : remoteStudents) {
                    int remoteId = (Integer) remote.get("id");
                    String targetClass = (String) remote.get("target_class");
                    String prefix = targetClass.startsWith("SS") ? "SS" : "JS";
                    String generatedAppId = prefix + "/2026/" + java.util.UUID.randomUUID().toString().substring(0, 5).toUpperCase();
                    String generatedPassword = "PIN-" + java.util.UUID.randomUUID().toString().substring(0, 4).toUpperCase();
                    java.time.LocalDate dob = java.time.LocalDate.parse((String) remote.get("date_of_birth"));

                    String cloudUrl = (String) remote.get("passport_url");
                    String finalLocalPath = "/assets/passports/default.png"; 

                    if (cloudUrl != null && !cloudUrl.trim().isEmpty()) {
                        try {
                            String safeFileName = generatedAppId.replace("/", "-") + ".png";
                            File localImageFile = new File(dir, safeFileName);
                            
                            java.net.URL url = new java.net.URI(cloudUrl).toURL();
                            try (java.io.InputStream in = url.openStream()) {
                                java.nio.file.Files.copy(in, localImageFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                            finalLocalPath = localImageFile.getAbsolutePath();
                        } catch (Exception imgEx) {
                            System.err.println("⚠️ Warning: Could not download image file, reverting to default icon: " + imgEx.getMessage());
                        }
                    }

                    Student localStudent = new Student(
                        0,                                                  // 1. studentId
                        generatedAppId,                                     // 2. applicationId
                        (String) remote.get("first_name"),                  // 3. firstName
                        (String) remote.get("last_name"),                   // 4. lastName
                        ((String) remote.get("gender")).substring(0, 1),    // 5. gender
                        dob,                                                // 6. dateOfBirth
                        (String) remote.get("state_of_origin"),             // 7. stateOfOrigin
                        (String) remote.get("lga"),                         // 8. lga
                        (String) remote.get("home_address"),                // 9. homeAddress
                        targetClass,                                        // 10. currentClass
                        (String) remote.get("guardian_name"),               // 11. guardianName
                        "Parent",                                           // 12. guardianRelationship
                        (String) remote.get("guardian_phone"),              // 13. guardianPhone
                        finalLocalPath,                                     // 14. localPassportPath
                        java.time.LocalDateTime.now(),                      // 15. enrollmentDate
                        true,                                               // 16. isActive
                        generatedPassword,                                  // 17. studentPassword
                        null,                                               // 18. webPassportBase64
                        "Pending Online Submission",                        // 19. prevPrimarySchool
                        "0000",                                             // 20. primaryFromYear
                        "0000",                                             // 21. primaryToYear
                        "Pending Online Submission",                        // 22. prevJuniorSecSchool
                        "0000",                                             // 23. juniorSecFromYear
                        "0000"                                              // 24. juniorSecToYear
                    );

                    if (studentDAO.insertStudent(localStudent)) {
                        // 🌟 Sync assigned credentials back to Python Server
                        syncClient.pushAssignedCredentials(remoteId, generatedAppId, generatedPassword);
                        
                        successfullyImportedIds.add(remoteId);
                        importedCount++;
                    }
                }

                if (!successfullyImportedIds.isEmpty()) {
                    String ackJsonPayload = mapper.writeValueAsString(successfullyImportedIds);
                    syncClient.acknowledgeImport(ackJsonPayload);
                }

                final int finalCount = importedCount;
                javafx.application.Platform.runLater(() -> {
                    loadingAlert.close();
                    loadStudentData();
                    Alert success = new Alert(Alert.AlertType.INFORMATION);
                    success.setTitle("Import Complete");
                    success.setHeaderText("Hybrid Sync Completed Successfully!");
                    success.setContentText("Successfully imported " + finalCount + " new parent mobile applications straight into your local MS SQL Server instance.");
                    success.showAndWait();
                });
            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> {
                    loadingAlert.close();
                    Alert err = new Alert(Alert.AlertType.ERROR, "Sync Execution Fault: Check connection stream pipe parameters.");
                    err.showAndWait();
                });
                e.printStackTrace();
            }
        }).start();
    }

    @FXML
    private void handleIssueAdmissionLetter(ActionEvent event) {
        Student selectedStudent = studentTable.getSelectionModel().getSelectedItem();
        if (selectedStudent == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "Please highlight a student row inside the directory registry list first!");
            alert.showAndWait();
            return;
        }
        try {
            String path = com.nigeriaschools.util.AdmissionLetterEngine.generateAdmissionLetter(selectedStudent);
            
            try {
                java.awt.Desktop.getDesktop().open(new File(path));
            } catch (Exception dEx) {
                System.err.println("⚠️ Document saved but default system PDF viewer failed to initialize: " + dEx.getMessage());
            }
            
            Alert success = new Alert(Alert.AlertType.INFORMATION);
            success.setTitle("Letter Generated");
            success.setHeaderText("Provisional Admission Document Ready!");
            success.setContentText("The clearance letter has been written to:\n" + path);
            success.showAndWait();
        } catch (IOException e) {
            Alert error = new Alert(Alert.AlertType.ERROR, "Failed to compile PDF content stream layout: " + e.getMessage());
            error.showAndWait();
            e.printStackTrace();
        }
    }

    @FXML
    private void handleCompileReportCard(ActionEvent event) {
        Student selectedStudent = studentTable.getSelectionModel().getSelectedItem();
        if (selectedStudent == null) {
            Alert warning = new Alert(Alert.AlertType.WARNING, "Please select a student row from the grid first!");
            warning.showAndWait();
            return;
        }

        List<String> termsList = Arrays.asList("First", "Second", "Third");
        ChoiceDialog<String> termPrompt = new ChoiceDialog<>("First", termsList);
        termPrompt.setTitle("Select Report Parameters");
        termPrompt.setHeaderText("Set Academic Context for: " + selectedStudent.getFirstName().toUpperCase() + " " + selectedStudent.getLastName().toUpperCase());
        termPrompt.setContentText("Choose Academic Term Target Row:");
        
        Optional<String> chosenTerm = termPrompt.showAndWait();
        if (!chosenTerm.isPresent()) return;

        String activeRegNo = selectedStudent.getApplicationId();
        String activeClass = selectedStudent.getCurrentClass();
        String activeTerm = chosenTerm.get();
        String targetSession = "2025/2026"; 

        Task<String> reportCompilationTask = new Task<>() {
            @Override
            protected String call() throws Exception {
                return com.nigeriaschools.util.TerminalReportCardEngine.generateReportCardPdf(
                    activeRegNo, activeClass, targetSession, activeTerm
                );
            }
        };

        reportCompilationTask.setOnSucceeded(e -> {
            String pathResult = reportCompilationTask.getValue();
            try {
                java.awt.Desktop.getDesktop().open(new File(pathResult));
            } catch (Exception desktopEx) {
                System.err.println("⚠️ Document written but failed to spawn viewer.");
            }
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "Terminal Report Card Generated Successfully!\nPath: " + pathResult);
            alert.showAndWait();
        });

        reportCompilationTask.setOnFailed(e -> {
            Throwable causeException = reportCompilationTask.getException();
            if (causeException instanceof IllegalAccessException) {
                Alert lockAlert = new Alert(Alert.AlertType.ERROR);
                lockAlert.setTitle("Access Intercepted (Financial Lock Out)");
                lockAlert.setHeaderText("Bursary System Policy Enforcement Guard Triggered!");
                lockAlert.setContentText(causeException.getMessage());
                lockAlert.showAndWait();
            } else {
                Alert pipelineAlert = new Alert(Alert.AlertType.ERROR, "Generation failed internally: " + causeException.getMessage());
                pipelineAlert.showAndWait();
            }
        });

        new Thread(reportCompilationTask).start();
    }

    private void loadStudentData() {
        try {
            List<Student> studentList = studentDAO.getAllStudents();
            studentTable.setItems(FXCollections.observableArrayList(studentList));
            updateHomeDashboardMetrics(studentList.size());
        } catch (Exception e) {
            System.err.println("⚠️ Roster Table Refresh Interrupted: " + e.getMessage());
        }
    }
}