package com.nigeriaschools.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class Student {
    private int studentId;
    private String applicationId;
    private String firstName;
    private String lastName;
    private String gender;
    private LocalDate dateOfBirth;
    private String stateOfOrigin;
    private String lga;
    private String homeAddress;
    private String currentClass;
    private String guardianName;
    private String guardianRelationship;
    private String guardianPhone;
    private String localPassportPath;
    private LocalDateTime enrollmentDate;
    private boolean isActive;
    private String studentPassword;
    private String webPassportBase64; // 🌟 Tracks web portal heavy image data streams
    
    // Academic History Profile Metrics
    private String prevPrimarySchool;
    private String primaryFromYear;
    private String primaryToYear;
    private String prevJuniorSecSchool;
    private String juniorSecFromYear;
    private String juniorSecToYear;

    // Default No-Arg Constructor (Highly recommended for flexible JavaFX cell bindings)
    public Student() {}

    // 🌟 FIXED Comprehensive Constructor: Seamlessly mapping all 24 structural properties
    public Student(int studentId, String applicationId, String firstName, String lastName, String gender,
                   LocalDate dateOfBirth, String stateOfOrigin, String lga, String homeAddress, String currentClass,
                   String guardianName, String guardianRelationship, String guardianPhone, String localPassportPath,
                   LocalDateTime enrollmentDate, boolean isActive, String studentPassword, String webPassportBase64,
                   String prevPrimarySchool, String primaryFromYear, String primaryToYear, 
                   String prevJuniorSecSchool, String juniorSecFromYear, String juniorSecToYear) {
        this.studentId = studentId;
        this.applicationId = applicationId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.gender = gender;
        this.dateOfBirth = dateOfBirth;
        this.stateOfOrigin = stateOfOrigin;
        this.lga = lga;
        this.homeAddress = homeAddress;
        this.currentClass = currentClass;
        this.guardianName = guardianName;
        this.guardianRelationship = guardianRelationship;
        this.guardianPhone = guardianPhone;
        this.localPassportPath = localPassportPath;
        this.enrollmentDate = enrollmentDate;
        this.isActive = isActive;
        this.studentPassword = studentPassword;
        this.webPassportBase64 = webPassportBase64; // Initialized properly
        this.prevPrimarySchool = prevPrimarySchool;
        this.primaryFromYear = primaryFromYear;
        this.primaryToYear = primaryToYear;
        this.prevJuniorSecSchool = prevJuniorSecSchool;
        this.juniorSecFromYear = juniorSecFromYear;
        this.juniorSecToYear = juniorSecToYear;
    }

    // Standardized Getters & Setters
    public int getStudentId() { return studentId; }
    public void setStudentId(int studentId) { this.studentId = studentId; }

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }

    public String getStateOfOrigin() { return stateOfOrigin; }
    public void setStateOfOrigin(String stateOfOrigin) { this.stateOfOrigin = stateOfOrigin; }

    public String getLga() { return lga; }
    public void setLga(String lga) { this.lga = lga; }

    public String getHomeAddress() { return homeAddress; }
    public void setHomeAddress(String homeAddress) { this.homeAddress = homeAddress; }

    public String getCurrentClass() { return currentClass; }
    public void setCurrentClass(String currentClass) { this.currentClass = currentClass; }

    public String getGuardianName() { return guardianName; }
    public void setGuardianName(String guardianName) { this.guardianName = guardianName; }

    public String getGuardianRelationship() { return guardianRelationship; }
    public void setGuardianRelationship(String guardianRelationship) { this.guardianRelationship = guardianRelationship; }

    public String getGuardianPhone() { return guardianPhone; }
    public void setGuardianPhone(String guardianPhone) { this.guardianPhone = guardianPhone; }

    public String getLocalPassportPath() { return localPassportPath; }
    public void setLocalPassportPath(String localPassportPath) { this.localPassportPath = localPassportPath; }

    public LocalDateTime getEnrollmentDate() { return enrollmentDate; }
    public void setEnrollmentDate(LocalDateTime enrollmentDate) { this.enrollmentDate = enrollmentDate; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { this.isActive = active; }

    public String getStudentPassword() { return studentPassword; }
    public void setStudentPassword(String studentPassword) { this.studentPassword = studentPassword; }

    public String getWebPassportBase64() { return webPassportBase64; }
    public void setWebPassportBase64(String webPassportBase64) { this.webPassportBase64 = webPassportBase64; }

    // 🌟 FIXED: Added Missing Setters for Previous School History Profiles
    public String getPrevPrimarySchool() { return prevPrimarySchool; }
    public void setPrevPrimarySchool(String prevPrimarySchool) { this.prevPrimarySchool = prevPrimarySchool; }

    public String getPrimaryFromYear() { return primaryFromYear; }
    public void setPrimaryFromYear(String primaryFromYear) { this.primaryFromYear = primaryFromYear; }

    public String getPrimaryToYear() { return primaryToYear; }
    public void setPrimaryToYear(String primaryToYear) { this.primaryToYear = primaryToYear; }

    public String getPrevJuniorSecSchool() { return prevJuniorSecSchool; }
    public void setPrevJuniorSecSchool(String prevJuniorSecSchool) { this.prevJuniorSecSchool = prevJuniorSecSchool; }

    public String getJuniorSecFromYear() { return juniorSecFromYear; }
    public void setJuniorSecFromYear(String juniorSecFromYear) { this.juniorSecFromYear = juniorSecFromYear; }

    public String getJuniorSecToYear() { return juniorSecToYear; }
    public void setJuniorSecToYear(String juniorSecToYear) { this.juniorSecToYear = juniorSecToYear; }
}
