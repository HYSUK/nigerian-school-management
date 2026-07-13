package com.nigeriaschools.model;

import javafx.beans.property.*;
import java.math.BigDecimal;

public class SchoolFeesPayment {
    private final IntegerProperty paymentId = new SimpleIntegerProperty();
    private final IntegerProperty studentId = new SimpleIntegerProperty();
    private final StringProperty studentRegNo = new SimpleStringProperty();
    private final StringProperty currentClass = new SimpleStringProperty();
    private final StringProperty schoolSection = new SimpleStringProperty();
    private final StringProperty academicYear = new SimpleStringProperty();
    private final StringProperty term = new SimpleStringProperty();
    
    private final ObjectProperty<BigDecimal> amountDue = new SimpleObjectProperty<>(BigDecimal.ZERO);
    private final ObjectProperty<BigDecimal> amountPaid = new SimpleObjectProperty<>(BigDecimal.ZERO);
    private final ObjectProperty<BigDecimal> balance = new SimpleObjectProperty<>(BigDecimal.ZERO);
    private final StringProperty paymentStatus = new SimpleStringProperty();

    // Default Constructor
    public SchoolFeesPayment() {}

    // Comprehensive Constructor for Reading Records
    public SchoolFeesPayment(int paymentId, int studentId, String studentRegNo, String currentClass, 
                             String schoolSection, String academicYear, String term, 
                             BigDecimal amountDue, BigDecimal amountPaid, BigDecimal balance, String paymentStatus) {
        this.paymentId.set(paymentId);
        this.studentId.set(studentId);
        this.studentRegNo.set(studentRegNo);
        this.currentClass.set(currentClass);
        this.schoolSection.set(schoolSection);
        this.academicYear.set(academicYear);
        this.term.set(term);
        this.amountDue.set(amountDue != null ? amountDue : BigDecimal.ZERO);
        this.amountPaid.set(amountPaid != null ? amountPaid : BigDecimal.ZERO);
        this.balance.set(balance != null ? balance : BigDecimal.ZERO);
        this.paymentStatus.set(paymentStatus);
    }

    // JavaFX Property Hooks (For TableView Column Data Binding)
    public IntegerProperty paymentIdProperty() { return paymentId; }
    public IntegerProperty studentIdProperty() { return studentId; }
    public StringProperty studentRegNoProperty() { return studentRegNo; }
    public StringProperty currentClassProperty() { return currentClass; }
    public StringProperty schoolSectionProperty() { return schoolSection; }
    public StringProperty academicYearProperty() { return academicYear; }
    public StringProperty termProperty() { return term; }
    public ObjectProperty<BigDecimal> amountDueProperty() { return amountDue; }
    public ObjectProperty<BigDecimal> amountPaidProperty() { return amountPaid; }
    public ObjectProperty<BigDecimal> balanceProperty() { return balance; }
    public StringProperty paymentStatusProperty() { return paymentStatus; }

    // Standard Java Getters & Setters
    public int getPaymentId() { return paymentId.get(); }
    public void setPaymentId(int id) { this.paymentId.set(id); }

    public int getStudentId() { return studentId.get(); }
    public void setStudentId(int id) { this.studentId.set(id); }

    public String getStudentRegNo() { return studentRegNo.get(); }
    public void setStudentRegNo(String regNo) { this.studentRegNo.set(regNo); }

    public String getCurrentClass() { return currentClass.get(); }
    public void setCurrentClass(String cls) { this.currentClass.set(cls); }

    public String getSchoolSection() { return schoolSection.get(); }
    public void setSchoolSection(String sec) { this.schoolSection.set(sec); }

    public String getAcademicYear() { return academicYear.get(); }
    public void setAcademicYear(String year) { this.academicYear.set(year); }

    public String getTerm() { return term.get(); }
    public void setTerm(String termVal) { this.term.set(termVal); }

    public java.math.BigDecimal getAmountDue() { return amountDue.get(); }
    public void setAmountDue(java.math.BigDecimal due) { this.amountDue.set(due); }

    public java.math.BigDecimal getAmountPaid() { return amountPaid.get(); }
    public void setAmountPaid(java.math.BigDecimal paid) { 
        this.amountPaid.set(paid); 
        // Recalculate computed parameters locally on inline edit shifts
        this.balance.set(this.amountDue.get().subtract(paid));
    }

    public java.math.BigDecimal getBalance() { return balance.get(); }
    public void setBalance(java.math.BigDecimal bal) { this.balance.set(bal); }

    public String getPaymentStatus() { return paymentStatus.get(); }
    public void setPaymentStatus(String status) { this.paymentStatus.set(status); }
}
