package com.nigeriaschools.controller;

import com.nigeriaschools.dao.TeacherDAO;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;

public class LoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Button loginButton;
    @FXML private Label errorLabel;

    private final TeacherDAO teacherDAO = new TeacherDAO();

    @FXML
    void handleLogin(ActionEvent event) {
        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();

        if (username.isEmpty() || password.isEmpty()) {
            errorLabel.setText("⚠️ Enter both portal identifiers!");
            return;
        }

        // Run validation task off the UI thread
        new Thread(() -> {
            try {
                boolean success = teacherDAO.validateLogin(username, password);
                
                Platform.runLater(() -> {
                    if (success) {
                        try {
                            // 1. Load the master layout blueprint
                            FXMLLoader loader = new FXMLLoader(getClass().getResource("/Dashboard.fxml"));
                            Parent root = loader.load();
                            
                            // 2. Safely extract the master controller reference mapping
                            DashboardController dashboardController = loader.getController();
                            
                            // 3. Forward the authenticated credentials token down the workspace pipeline
                            String currentStaff = username.toUpperCase();
                            dashboardController.setAuthenticatedUser(currentStaff);
                            
                            // 4. Swap the window scene matrix frames
                            Stage stage = (Stage) loginButton.getScene().getWindow();
                            stage.setScene(new Scene(root));
                            stage.centerOnScreen();
                        } catch (Exception ex) {
                            errorLabel.setText("❌ Failed loading layout resource map workspace.");
                            ex.printStackTrace();
                        }
                    } else {
                        errorLabel.setText("❌ Invalid authentication tokens. Access Denied.");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> errorLabel.setText("❌ Connection exception error. Check link pipe."));
                e.printStackTrace();
            }
        }).start();
    }
}
