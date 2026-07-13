package com.nigeriaschools.controller;

import com.nigeriaschools.model.Student;
import com.nigeriaschools.util.AdmissionPdfEngine; // 🔥 Import your updated OpenPDF asset engine
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.print.PrinterJob;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.File;

public class AdmissionSlipController {

    @FXML private VBox slipPrintContainer;
    @FXML private Label appIdLabel, nameLabel, bioLabel, originLabel, addressLabel, pinLabel;
    @FXML private Label guardianNameLabel, guardianPhoneLabel;
    @FXML private Label primaryInstLabel, primaryDurationLabel;
    @FXML private HBox slipJuniorSecRow;
    @FXML private Label juniorSecInstLabel, juniorSecDurationLabel;
    @FXML private ImageView slipPassportImage;

    // Track the active model reference locally for document extraction tasks
    private Student currentStudent;
    private String rawPasswordToken;

    /**
     * Map every single column element from the Student profile directly into the UI nodes
     */
    public void populateSlipData(Student student, String unhashedPassword, Image loadedPassport) {
        // Cache object parameters locally for file printer threads
        this.currentStudent = student;
        this.rawPasswordToken = unhashedPassword;

        // Core Demographics & Identity
        appIdLabel.setText(student.getApplicationId() + " [" + student.getCurrentClass() + "]");
        nameLabel.setText(student.getFirstName().toUpperCase() + " " + student.getLastName().toUpperCase());
        
        String genderText = "M".equalsIgnoreCase(student.getGender()) ? "Male" : "Female";
        bioLabel.setText(genderText + " / Date of Birth: " + student.getDateOfBirth().toString());
        pinLabel.setText(unhashedPassword);

        originLabel.setText(student.getStateOfOrigin() + " State / " + student.getLga() + " LGA");
        addressLabel.setText(student.getHomeAddress());

        // Guardian parameters
        guardianNameLabel.setText(student.getGuardianName());
        guardianPhoneLabel.setText(student.getGuardianPhone());

        // Primary History details 
        primaryInstLabel.setText(student.getPrevPrimarySchool());
        primaryDurationLabel.setText("(" + student.getPrimaryFromYear() + " - " + student.getPrimaryToYear() + ")");

        // Context-aware Secondary details layout logic
        if (student.getCurrentClass().startsWith("SS") && student.getPrevJuniorSecSchool() != null && !student.getPrevJuniorSecSchool().isEmpty()) {
            juniorSecInstLabel.setText(student.getPrevJuniorSecSchool());
            juniorSecDurationLabel.setText("(" + student.getJuniorSecFromYear() + " - " + student.getJuniorSecToYear() + ")");
            slipJuniorSecRow.setOpacity(1.0);
        } else {
            juniorSecInstLabel.setText("Not Applicable (Fresh Junior Intake Profile)");
            juniorSecDurationLabel.setText("");
            slipJuniorSecRow.setOpacity(0.5); 
        }

        if (loadedPassport != null) {
            slipPassportImage.setImage(loadedPassport);
        }
    }

    /**
     * 🔥 FIXED METHOD: Compiles a premium vector A4 document layout using OpenPDF background engines
     * Link this method to an FXML button click: onAction="#handleGenerateOpenPdfSlip"
     */
    @FXML
    private void handleGenerateOpenPdfSlip(ActionEvent event) {
        if (currentStudent == null) {
            showUiAlert("Missing Reference", "No student profile is currently cached in the portal window context.", Alert.AlertType.WARNING);
            return;
        }

        // Initialize background task infrastructure to keep FX application thread fully responsive
        // Changed to File parameter tracking layout
        Task<File> openPdfTask = new Task<>() {
            @Override
            protected File call() throws Exception {
                // 1. Standardize and sanitize the filename safely
                String cleanAppId = currentStudent.getApplicationId().trim().replace("/", "_").replace("\\", "_");
                
                // 2. Point directly to your designated workspace folder
                File outputFolder = new File("C:\\Users\\TOSHIBA\\Desktop\\SchoolApiServer\\clearance_slips");
                if (!outputFolder.exists()) {
                    outputFolder.mkdirs();
                }
                
                File targetPdfFile = new File(outputFolder, cleanAppId + "-Clearance.pdf");
                
                // 3. Invoke the modified OpenPDF engine with all 3 required parameters ✅
                AdmissionPdfEngine.generateAdmissionSlipPdf(currentStudent, rawPasswordToken, targetPdfFile);
                
                return targetPdfFile;
            }
        };

        openPdfTask.setOnSucceeded(e -> {
            File compiledFile = openPdfTask.getValue();
            
            // Instantly open up the compiled PDF file on the user's desktop for inspection checks
            try {
                if (compiledFile.exists() && java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(compiledFile);
                }
            } catch (Exception desktopEx) {
                System.err.println("⚠️ OpenPDF generated safely, but desktop viewer failed to open file window: " + desktopEx.getMessage());
            }

            showUiAlert("Dossier Compiled", "Provisional OpenPDF admission slip compiled clean to the API workspace server path!\nPath: " + compiledFile.getAbsolutePath(), Alert.AlertType.INFORMATION);
        });

        openPdfTask.setOnFailed(e -> {
            Throwable ex = openPdfTask.getException();
            showUiAlert("Pipeline Failure", "OpenPDF compilation process was interrupted: " + ex.getMessage(), Alert.AlertType.ERROR);
            ex.printStackTrace();
        });

        new Thread(openPdfTask).start();
    }

    /**
     * Spawns standard OS dialogue layout pipeline to transfer view graphics to paper/PDF
     */
    @FXML
    private void executeNativePrint() {
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job != null && job.showPrintDialog(slipPrintContainer.getScene().getWindow())) {
            boolean success = job.printPage(slipPrintContainer);
            if (success) {
                job.endJob();
                showUiAlert("System Spooled", "Print job spooled to system engine successfully!", Alert.AlertType.INFORMATION);
                ((Stage) slipPrintContainer.getScene().getWindow()).close();
            }
        }
    }

    private void showUiAlert(String title, String content, Alert.AlertType type) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }
}
