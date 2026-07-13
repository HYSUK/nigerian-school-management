package com.nigeriaschools.model;

import java.time.LocalDateTime;

public class ScratchCard {
    private int cardId;
    private String serialNumber;
    private String pinNumber;
    private String schoolSection; // "JS" or "SS"
    private String cardStatus;    // "Unused", "Used", "Expired"
    private String batchNumber;
    private String usedByApplicationId;
    private LocalDateTime dateUsed;

    // Constructor for creating new cards
    public ScratchCard(String serialNumber, String pinNumber, String schoolSection, String batchNumber) {
        this.serialNumber = serialNumber;
        this.pinNumber = pinNumber;
        this.schoolSection = schoolSection;
        this.batchNumber = batchNumber;
        this.cardStatus = "Unused";
    }

    // Full Constructor for reading records from DB
    public ScratchCard(int cardId, String serialNumber, String pinNumber, String schoolSection, 
                       String cardStatus, String batchNumber, String usedByApplicationId, LocalDateTime dateUsed) {
        this.cardId = cardId;
        this.serialNumber = serialNumber;
        this.pinNumber = pinNumber;
        this.schoolSection = schoolSection;
        this.cardStatus = cardStatus;
        this.batchNumber = batchNumber;
        this.usedByApplicationId = usedByApplicationId;
        this.dateUsed = dateUsed;
    }

    // Getters
    public int getCardId() { return cardId; }
    public String getSerialNumber() { return serialNumber; }
    public String getPinNumber() { return pinNumber; }
    public String getSchoolSection() { return schoolSection; }
    public String getCardStatus() { return cardStatus; }
    public String getBatchNumber() { return batchNumber; }
    public String getUsedByApplicationId() { return usedByApplicationId; }
    public LocalDateTime getDateUsed() { return dateUsed; }
}
