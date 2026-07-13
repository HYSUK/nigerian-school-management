package com.nigeriaschools.controller;

import com.nigeriaschools.dao.ScratchCardDAO;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;
import java.time.LocalDate;
import java.util.UUID;

public class PreValidateController {

    @FXML private ComboBox<String> classBox; // Ensure this is exactly 'classBox' to match FXML fx:id
    @FXML private TextField serialField, pinField;
    @FXML private Label errorLabel;
    @FXML private Button validateBtn;

    private final ScratchCardDAO scratchCardDAO = new ScratchCardDAO();
    
    private boolean validated = false;
    private String generatedAppId;
    private String generatedPassword;
    private String validatedClass; // Named 'validatedClass'

    @FXML
    void handleValidation() {
        String selectedClass = classBox.getValue(); // e.g., "JS1" or "SS3"
        String serial = serialField.getText().trim();
        String pin = pinField.getText().trim();

        if (selectedClass == null || serial.isEmpty() || pin.isEmpty()) {
            errorLabel.setStyle("-fx-text-fill: #c62828;");
            errorLabel.setText("⚠️ Please fill out all validation parameters!");
            return;
        }

        // 1. Extract the section prefix (JS or SS) from the selected class level
        String requiredSection = selectedClass.startsWith("JS") ? "JS" : "SS";

        // 2. Get the full 4-digit current academic year (e.g., 2026)
        String yearFull = String.valueOf(LocalDate.now().getYear());

        // 3. Generate a distinct random unique alphanumeric suffix code (5 characters)
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 5).toUpperCase();

        // 4. Combine into format: JS/2026/XXXXX or SS/2026/XXXXX
        String appId = requiredSection + "/" + yearFull + "/" + uniqueSuffix;
        
        // Generate a clean portal security password key
        String password = "PWD-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        // 5. Run the database verification rules matching your JS/SS criteria
        int result = scratchCardDAO.validateAndUseCard(serial, pin, requiredSection, appId);

        // 🔥 STRICT ERROR INTERCEPTION DIRECTLY FROM DATABASE
        if (result == -1) {
            errorLabel.setStyle("-fx-text-fill: #c62828;");
            errorLabel.setText("❌ Invalid Scratch Card Serial Number or PIN!");
            return;
        } else if (result == -2) {
            errorLabel.setStyle("-fx-text-fill: #c62828;");
            errorLabel.setText("❌ This Scratch Card has already been used!");
            return;
        } else if (result == -3) {
            errorLabel.setStyle("-fx-text-fill: #c62828;");
            errorLabel.setText("🔒 Section Lock Mismatch: Selected class requires a " + requiredSection + " card!");
            return;
        } else if (result != 1) {
            errorLabel.setStyle("-fx-text-fill: #c62828;");
            errorLabel.setText("⚠️ Database connection error occurred. Try again.");
            return;
        }

        // Pass successful credentials only when DB verification returns 1
        this.generatedAppId = appId;
        this.generatedPassword = password;
        this.validatedClass = selectedClass;

        showCredentialsAlert();
    }

    private void showCredentialsAlert() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Portal Credentials Security Notice");
        alert.setHeaderText("✅ SCRATCH CARD VERIFIED SUCCESSFULLY!");
        
        String content = "Please copy and secure these student login details before registration:\n\n"
                       + "🌐 PORTAL USERNAME (Application ID): " + generatedAppId + "\n"
                       + "🔐 PORTAL ACCESS PASSWORD: " + generatedPassword + "\n\n"
                       + "Click DONE to proceed to the Comprehensive Enrollment form.";
        
        alert.setContentText(content);
        
        // Customize the confirmation button text to say "DONE"
        Button doneButton = (Button) alert.getDialogPane().lookupButton(ButtonType.OK);
        if (doneButton != null) {
            doneButton.setText("DONE");
        }

        alert.showAndWait();

        // Mark checkpoint flag as clear and close the validation pane window
        this.validated = true;
        ((Stage) validateBtn.getScene().getWindow()).close();
    }

    public boolean isValidated() { return validated; }
    public String getGeneratedAppId() { return generatedAppId; }
    public String getGeneratedPassword() { return generatedPassword; }
    public String getValidatedClass() { return validatedClass; } 
}