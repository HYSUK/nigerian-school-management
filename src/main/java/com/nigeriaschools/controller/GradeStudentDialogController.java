package com.nigeriaschools.controller;

import com.nigeriaschools.model.AcademicRecord;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;
import java.math.BigDecimal;

public class GradeStudentDialogController {

    @FXML private Label studentInfoLabel;
    @FXML private TextField firstCAField;
    @FXML private TextField secondCAField;
    @FXML private TextField examField;
    @FXML private Button saveBtn;

    private AcademicRecord targetRecord;
    private boolean applyClicked = false;

    public void setAcademicRecord(AcademicRecord record) {
        this.targetRecord = record;
        studentInfoLabel.setText("Student Registration ID: " + record.getStudentRegNo());
        firstCAField.setText(record.getFirstCA().toString());
        secondCAField.setText(record.getSecondCA().toString());
        examField.setText(record.getExamScore().toString());
    }

    public boolean isApplyClicked() { return applyClicked; }

    @FXML
    private void handleSave() {
        try {
            BigDecimal first = validateInput(firstCAField.getText(), 20);
            BigDecimal second = validateInput(secondCAField.getText(), 20);
            BigDecimal exam = validateInput(examField.getText(), 60);

            targetRecord.setFirstCA(first);
            targetRecord.setSecondCA(second);
            targetRecord.setExamScore(exam);

            applyClicked = true;
            closeStage();
        } catch (NumberFormatException e) {
            Alert alert = new Alert(Alert.AlertType.ERROR, "Please input clean numeric values inside fields!");
            alert.showAndWait();
        }
    }

    @FXML private void handleCancel() { closeStage(); }

    private BigDecimal validateInput(String text, double limit) {
        if (text == null || text.trim().isEmpty()) return BigDecimal.ZERO;
        BigDecimal val = new BigDecimal(text.trim());
        if (val.doubleValue() < 0) return BigDecimal.ZERO;
        if (val.doubleValue() > limit) return BigDecimal.valueOf(limit);
        return val;
    }

    private void closeStage() {
        Stage stage = (Stage) saveBtn.getScene().getWindow();
        stage.close();
    }
}
