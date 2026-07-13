package com.nigeriaschools.main;

import com.nigeriaschools.util.DatabaseConnection;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import java.io.IOException;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) {
        try {
            System.out.println("Compiling Graphical Layout Framework...");
            
            // Locates Dashboard.fxml inside src/main/resources
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/Dashboard.fxml"));
            Parent root = loader.load();
            
            Scene scene = new Scene(root, 900, 600);
            
            primaryStage.setTitle("Hysuk-Hybrid School Administration System - Offline Control Desk");
            primaryStage.setScene(scene);
            primaryStage.show();
            
            System.out.println("✅ UI Dashboard Screen Rendered Successfully!");
            
        } catch (IOException e) {
            System.err.println("❌ Critical Error: Unable to extract Dashboard.fxml");
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        System.out.println("Booting School Admin Infrastructure...");
        
        // 1. Double check database status
        DatabaseConnection.testConnection();
        
        // 2. Start JavaFX Environment
        launch(args);
    }
}
