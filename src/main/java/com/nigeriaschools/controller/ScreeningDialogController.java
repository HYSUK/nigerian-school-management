package com.nigeriaschools.controller;

import com.nigeriaschools.dao.StudentDAO;
import com.nigeriaschools.model.Student;
import com.nigeriaschools.sync.HttpSyncClient;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;

public class ScreeningDialogController {

    @FXML private Label studentInfoLabel;
    @FXML private TextField scoreField;
    @FXML private TextField cutoffField;
    @FXML private Label statusLabel;
    @FXML private Button saveButton;

    private Student currentStudent;
    private final StudentDAO studentDAO = new StudentDAO();
    private final HttpSyncClient syncClient = new HttpSyncClient();

    public void setStudent(Student student) {
        this.currentStudent = student;
        studentInfoLabel.setText(
            String.format("%s %s (%s)", 
                student.getFirstName(), 
                student.getLastName(), 
                student.getApplicationId())
        );
        // Default cutoff mark
        cutoffField.setText("50");
    }

    @FXML
    private void handleSaveAndSync(ActionEvent event) {
        String scoreText = scoreField.getText().trim();
        String cutoffText = cutoffField.getText().trim();

        if (scoreText.isEmpty() || cutoffText.isEmpty()) {
            setStatus("⚠️ Please fill in all fields!", "#c62828");
            return;
        }

        try {
            int score = Integer.parseInt(scoreText);
            int cutoff = Integer.parseInt(cutoffText);

            if (score < 0 || score > 100) {
                setStatus("⚠️ Score must be between 0 and 100!", "#c62828");
                return;
            }

            // Disable button during process
            saveButton.setDisable(true);
            setStatus("⏳ Saving to local database...", "#1565c0");

            // 1. LOCAL UPDATE FIRST (Instant SQL Server Save)
            boolean localSaved = studentDAO.updateScreeningResultLocally(
                currentStudent.getApplicationId(), 
                score, 
                cutoff
            );

            if (!localSaved) {
                setStatus("❌ Failed to save record to local database!", "#c62828");
                saveButton.setDisable(false);
                return;
            }

            // 2. BACKGROUND THREAD FOR CLOUD SYNC (Non-blocking UI)
            setStatus("✅ Saved locally! Syncing to cloud...", "#2e7d32");

            new Thread(() -> {
                boolean cloudSynced = syncClient.pushScreeningResult(
                    currentStudent.getApplicationId(), 
                    score, 
                    cutoff
                );

                // Safely update UI elements from background thread
                Platform.runLater(() -> {
                    saveButton.setDisable(false);

                    if (cloudSynced) {
                        setStatus("✅ Saved & Synced to Cloud Dashboard!", "#2e7d32");
                        
                        // Close dialog window smoothly
                        Stage stage = (Stage) scoreField.getScene().getWindow();
                        stage.close();
                    } else {
                        setStatus("⚠️ Saved locally, but Cloud Sync failed.", "#e65100");
                    }
                });
            }).start();

        } catch (NumberFormatException e) {
            setStatus("⚠️ Scores must be valid numbers!", "#c62828");
            saveButton.setDisable(false);
        }
    }

    private void setStatus(String message, String hexColor) {
        statusLabel.setStyle("-fx-text-fill: " + hexColor + ";");
        statusLabel.setText(message);
    }
}