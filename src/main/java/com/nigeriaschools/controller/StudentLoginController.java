package com.nigeriaschools.controller;

import com.nigeriaschools.dao.StudentDAO;
import com.nigeriaschools.model.Student;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;

public class StudentLoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;
    @FXML private Button loginBtn;

    private final StudentDAO studentDAO = new StudentDAO();

    private boolean authenticated = false;
    private Student loggedInStudent;

    public boolean isAuthenticated() { return authenticated; }
    public Student getLoggedInStudent() { return loggedInStudent; }

    @FXML
    void handleLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();

        if (username.isEmpty() || password.isEmpty()) {
            errorLabel.setText("⚠ Please enter both username and password!");
            return;
        }

        boolean success = studentDAO.authenticate(username, password);
        if (success) {
            loggedInStudent = studentDAO.getStudentByDetails(username);
            if (loggedInStudent != null) {
                errorLabel.setText("✔ Login successful!");
                authenticated = true;

                // Close login dialog
                Stage stage = (Stage) loginBtn.getScene().getWindow();
                stage.close();
            } else {
                errorLabel.setText("✘ Student record not found.");
            }
        } else {
            errorLabel.setText("✘ Invalid credentials. Try again.");
        }
    }
}
