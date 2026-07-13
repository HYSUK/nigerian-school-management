package com.nigeriaschools.model;

import javafx.beans.property.*;
import java.math.BigDecimal;

public class AcademicRecord {
    // 1. Unified JavaFX Property Field Declarations
    private final IntegerProperty recordId = new SimpleIntegerProperty();
    private final IntegerProperty studentId = new SimpleIntegerProperty();
    private final StringProperty studentRegNo = new SimpleStringProperty();
    private final StringProperty academicYear = new SimpleStringProperty(); // 🌟 Synchronized
    private final StringProperty academicTerm = new SimpleStringProperty();
    private final StringProperty subjectName = new SimpleStringProperty();
    private final StringProperty currentClass = new SimpleStringProperty();
    
    // Using ObjectProperty<BigDecimal> to match financial/precise academic score metrics
    private final ObjectProperty<BigDecimal> firstCA = new SimpleObjectProperty<>(BigDecimal.ZERO);
    private final ObjectProperty<BigDecimal> secondCA = new SimpleObjectProperty<>(BigDecimal.ZERO);
    private final ObjectProperty<BigDecimal> examScore = new SimpleObjectProperty<>(BigDecimal.ZERO);
    private final ObjectProperty<BigDecimal> totalScore = new SimpleObjectProperty<>(BigDecimal.ZERO);
    
    private final StringProperty grade = new SimpleStringProperty();
    private final StringProperty remarks = new SimpleStringProperty();

    // Default Constructor
    public AcademicRecord() {}

    // 2. Corrected INSERT Constructor (Auto-wraps values into JavaFX Properties)
    public AcademicRecord(int studentId, String academicYear, String academicTerm, String subjectName, 
                          BigDecimal firstCA, BigDecimal secondCA, BigDecimal examScore, String grade, String remarks) {
        this.studentId.set(studentId);
        this.academicYear.set(academicYear); // 🌟 Synchronized
        this.academicTerm.set(academicTerm);
        this.subjectName.set(subjectName);
        this.firstCA.set(firstCA != null ? firstCA : BigDecimal.ZERO);
        this.secondCA.set(secondCA != null ? secondCA : BigDecimal.ZERO);
        this.examScore.set(examScore != null ? examScore : BigDecimal.ZERO);
        this.grade.set(grade);
        this.remarks.set(remarks);
        calculateAndSetTotal();
    }

    // 3. Corrected SELECT/READ Constructor (Includes RecordID and computed TotalScore)
    public AcademicRecord(int recordId, int studentId, String academicYear, String academicTerm, String subjectName,
                          BigDecimal firstCA, BigDecimal secondCA, BigDecimal examScore, BigDecimal totalScore, 
                          String grade, String remarks) {
        this.recordId.set(recordId);
        this.studentId.set(studentId);
        this.academicYear.set(academicYear); // 🌟 Synchronized
        this.academicTerm.set(academicTerm);
        this.subjectName.set(subjectName);
        this.firstCA.set(firstCA != null ? firstCA : BigDecimal.ZERO);
        this.secondCA.set(secondCA != null ? secondCA : BigDecimal.ZERO);
        this.examScore.set(examScore != null ? examScore : BigDecimal.ZERO);
        this.totalScore.set(totalScore != null ? totalScore : BigDecimal.ZERO);
        this.grade.set(grade);
        this.remarks.set(remarks);
    }

    // Helper method to locally compute total if database hasn't persisted it yet
    private void calculateAndSetTotal() {
        BigDecimal total = this.firstCA.get().add(this.secondCA.get()).add(this.examScore.get());
        this.totalScore.set(total);
    }

    // 4. Cleaned Standard Java Getters & Setters
    public int getRecordId() { return recordId.get(); }
    public void setRecordId(int id) { this.recordId.set(id); }

    public int getStudentId() { return studentId.get(); }
    public void setStudentId(int id) { this.studentId.set(id); }

    public String getStudentRegNo() { return studentRegNo.get(); }
    public void setStudentRegNo(String regNo) { this.studentRegNo.set(regNo); }

    public String getCurrentClass() { return currentClass.get(); }
    public void setCurrentClass(String className) { this.currentClass.set(className); }
    
    public String getAcademicYear() { return academicYear.get(); } // 🌟 Synchronized getter name
    public void setAcademicYear(String year) { this.academicYear.set(year); }

    public String getAcademicTerm() { return academicTerm.get(); }
    public void setAcademicTerm(String term) { this.academicTerm.set(term); }

    public String getSubjectName() { return subjectName.get(); }
    public void setSubjectName(String name) { this.subjectName.set(name); }

    public BigDecimal getFirstCA() { return firstCA.get(); }
    public void setFirstCA(BigDecimal score) { this.firstCA.set(score); calculateAndSetTotal(); }

    public BigDecimal getSecondCA() { return secondCA.get(); }
    public void setSecondCA(BigDecimal score) { this.secondCA.set(score); calculateAndSetTotal(); }

    public BigDecimal getExamScore() { return examScore.get(); }
    public void setExamScore(BigDecimal score) { this.examScore.set(score); calculateAndSetTotal(); }

    public BigDecimal getTotalScore() { return totalScore.get(); }
    public void setTotalScore(BigDecimal total) { this.totalScore.set(total); }

    public String getGrade() { return grade.get(); }
    public void setGrade(String gradeValue) { this.grade.set(gradeValue); }

    public String getRemarks() { return remarks.get(); }
    public void setRemarks(String remarksValue) { this.remarks.set(remarksValue); }

    // 5. Essential JavaFX Property Architecture Hooks (For TableView Column Data Binding)
    public IntegerProperty recordIdProperty() { return recordId; }
    public IntegerProperty studentIdProperty() { return studentId; }
    public StringProperty studentRegNoProperty() { return studentRegNo; }
    public StringProperty currentClassProperty() { return currentClass; }
    public StringProperty academicYearProperty() { return academicYear; } // 🌟 Synchronized hook name
    public StringProperty academicTermProperty() { return academicTerm; }
    public StringProperty subjectNameProperty() { return subjectName; }
    public ObjectProperty<BigDecimal> firstCAProperty() { return firstCA; }
    public ObjectProperty<BigDecimal> secondCAProperty() { return secondCA; }
    public ObjectProperty<BigDecimal> examScoreProperty() { return examScore; }
    public ObjectProperty<BigDecimal> totalScoreProperty() { return totalScore; }
    public StringProperty gradeProperty() { return grade; }
    public StringProperty remarksProperty() { return remarks; }
}
