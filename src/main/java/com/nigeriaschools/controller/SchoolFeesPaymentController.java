package com.nigeriaschools.controller;

import com.nigeriaschools.dao.SchoolFeesPaymentDAO;
import com.nigeriaschools.model.SchoolFeesPayment;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.util.converter.BigDecimalStringConverter;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

public class SchoolFeesPaymentController {

    // FXML Top Filter Panel Controls
    @FXML private ComboBox<String> classComboBox;
    @FXML private ComboBox<String> sessionComboBox;
    @FXML private ComboBox<String> termComboBox;
    @FXML private Button loadLedgerButton;
    @FXML private Button syncChangesButton;
    @FXML private ProgressIndicator progressIndicator;

    // FXML Interactive Table Data Grid
    @FXML private TableView<SchoolFeesPayment> feesTableView;
    @FXML private TableColumn<SchoolFeesPayment, String> regNoColumn;
    @FXML private TableColumn<SchoolFeesPayment, BigDecimal> amountDueColumn;
    @FXML private TableColumn<SchoolFeesPayment, BigDecimal> amountPaidColumn;
    @FXML private TableColumn<SchoolFeesPayment, BigDecimal> balanceColumn;
    @FXML private TableColumn<SchoolFeesPayment, String> statusColumn;

    private final SchoolFeesPaymentDAO feesDAO = new SchoolFeesPaymentDAO();
    private final ObservableList<SchoolFeesPayment> masterLedgerList = FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        setupDropdownFilters();
        configureTableStructure();
    }

    /**
     * Initializes localized parameters for sorting student financial ledgers.
     */
    private void setupDropdownFilters() {
        classComboBox.setItems(FXCollections.observableArrayList("JS1", "JS2", "JS3", "SS1", "SS2", "SS3"));
        sessionComboBox.setItems(FXCollections.observableArrayList("2025/2026", "2026/2027"));
        termComboBox.setItems(FXCollections.observableArrayList("First", "Second", "Third"));
    }

    /**
     * Configures the layout table to enable spreadsheet-style inline cash posting edits.
     */
    private void configureTableStructure() {
        feesTableView.setEditable(true);
        feesTableView.setItems(masterLedgerList);

        // Map core identification columns
        regNoColumn.setCellValueFactory(cellData -> cellData.getValue().studentRegNoProperty());
        amountDueColumn.setCellValueFactory(cellData -> cellData.getValue().amountDueProperty());

        // Setup editable column for manual fee payment capturing
        amountPaidColumn.setCellValueFactory(cellData -> cellData.getValue().amountPaidProperty());
        amountPaidColumn.setCellFactory(TextFieldTableCell.forTableColumn(new BigDecimalStringConverter()));
        amountPaidColumn.setOnEditCommit(event -> {
            SchoolFeesPayment record = event.getRowValue();
            
            // 🌟 FIXED: Validates input safely against the row's actual localized AmountDue
            BigDecimal validatedAmount = validateCashInput(event.getNewValue(), record.getAmountDue());
            
            record.setAmountPaid(validatedAmount);
            
            // 🌟 FIXED: Recalculates metrics and triggers a forced table refresh
            recalculateLocalStatus(record);
            feesTableView.refresh(); 
        });

        

        // Map computed data display columns
        balanceColumn.setCellValueFactory(cellData -> cellData.getValue().balanceProperty());
        statusColumn.setCellValueFactory(cellData -> cellData.getValue().paymentStatusProperty());
    }

    /**
     * Enforces mathematical sanity checks to prevent faulty bank posting allocations.
     */
    private BigDecimal validateCashInput(BigDecimal input, BigDecimal amountDue) {
        if (input == null || input.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (input.compareTo(amountDue) > 0) {
            showAlert("Overpayment Alert", "The input payment exceeds the total fee due. Truncating to exact fee amount.", Alert.AlertType.WARNING);
            return amountDue;
        }
        return input;
    }

    /**
     * Updates balances and status markers in real-time before pushing to the server.
     */
    private void recalculateLocalStatus(SchoolFeesPayment record) {
        BigDecimal due = record.getAmountDue();
        BigDecimal paid = record.getAmountPaid();
        BigDecimal bal = due.subtract(paid);
        
        record.setBalance(bal);

        if (paid.compareTo(BigDecimal.ZERO) == 0) {
            record.setPaymentStatus("Unpaid");
        } else if (paid.compareTo(due) >= 0) {
            record.setPaymentStatus("Fully Paid");
        } else {
            record.setPaymentStatus("Partially Paid");
        }
    }
    
    /**
     * Extracts the highlighted student row and exports an official bursary clearance invoice directly.
     */
    @FXML
    private void handlePrintReceipt() {
        // Fetch the highlighted row model token from your active table view selection
        SchoolFeesPayment selectedPayment = feesTableView.getSelectionModel().getSelectedItem();

        if (selectedPayment == null) {
            showAlert("Row Selection Required", "Please click on a specific student record row inside the ledger grid workspace first before generating a receipt voucher.", Alert.AlertType.WARNING);
            return;
        }

        try {
            System.out.println("🔄 Launching Apache background ledger document builder streams...");
            String pdfPath = com.nigeriaschools.util.FeesReceiptEngine.generateReceiptPdf(selectedPayment);
            
            Alert successAlert = new Alert(Alert.AlertType.INFORMATION);
            successAlert.setTitle("Receipt Compiled");
            successAlert.setHeaderText("Bursary Clearing Clearance Document Processed!");
            successAlert.setContentText("The official school fees transaction receipt has been exported directly to your desktop workspace:\n" + pdfPath);
            successAlert.showAndWait();
            
        } catch (IOException e) {
            showAlert("Compilation Failure", "Failed to construct native PDF content stream blueprint matrices: " + e.getMessage(), Alert.AlertType.ERROR);
            e.printStackTrace();
        }
    }


    /**
     * Pulls active ledger rows or generates a clean blank invoice template on action click.
     */
    @FXML
    private void handleLoadLedger() {
        String targetClass = classComboBox.getValue();
        String year = sessionComboBox.getValue();
        String term = termComboBox.getValue();

        if (targetClass == null || year == null || term == null) {
            showAlert("Filter Error", "Please specify all dropdown sorting criteria before querying the system ledger.", Alert.AlertType.ERROR);
            return;
        }

        progressIndicator.setVisible(true);
        masterLedgerList.clear();

        Task<List<SchoolFeesPayment>> loadTask = new Task<>() {
            @Override
            protected List<SchoolFeesPayment> call() throws Exception {
                List<SchoolFeesPayment> existing = feesDAO.getPaymentsByFilter(targetClass, year, term);
                if (existing.isEmpty()) {
                    return feesDAO.generateBlankLedgerForClass(targetClass, year, term);
                }
                return existing;
            }
        };

        loadTask.setOnSucceeded(e -> {
            progressIndicator.setVisible(false);
            masterLedgerList.addAll(loadTask.getValue());
            if (masterLedgerList.isEmpty()) {
                showAlert("Roster Empty", "No active student directory records match the selected class tier assignment.", Alert.AlertType.INFORMATION);
            }
        });

        loadTask.setOnFailed(e -> {
            progressIndicator.setVisible(false);
            showAlert("Pipeline Error", "Failed to retrieve cash records: " + loadTask.getException().getMessage(), Alert.AlertType.ERROR);
        });

        new Thread(loadTask).start();
    }

    /**
     * Pushes all rows down the pipeline transaction batch in a single optimized block query.
     */
    @FXML
    private void handleSaveChanges() {
        if (masterLedgerList.isEmpty()) {
            showAlert("Action Blocked", "The data collection register grid is empty. No financial records found to save.", Alert.AlertType.WARNING);
            return;
        }

        progressIndicator.setVisible(true);

        Task<Void> saveTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                feesDAO.saveOrUpdatePaymentsBatch(masterLedgerList);
                return null;
            }
        };

        saveTask.setOnSucceeded(e -> {
            progressIndicator.setVisible(false);
            showAlert("Sync Complete", "All fee balances have been successfully written and synchronized with MS SQL Server!", Alert.AlertType.INFORMATION);
            handleLoadLedger(); // Refresh table view to assign database identity keys
        });

        saveTask.setOnFailed(e -> {
            progressIndicator.setVisible(false);
            showAlert("Server Synchronization Failure", "Batch processing failed: " + saveTask.getException().getMessage(), Alert.AlertType.ERROR);
        });

        new Thread(saveTask).start();
    }

    private void showAlert(String title, String message, Alert.AlertType type) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
}
