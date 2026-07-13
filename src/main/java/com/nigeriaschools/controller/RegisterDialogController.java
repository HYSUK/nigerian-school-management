package com.nigeriaschools.controller;

import com.nigeriaschools.dao.StudentDAO;
import com.nigeriaschools.model.Student;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import javafx.application.Platform;
import javafx.stage.Modality;
import javafx.stage.Stage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

public class RegisterDialogController {

    // Core Demographics
    @FXML private TextField firstNameField, lastNameField, addressField, guardianNameField, guardianPhoneField;
    @FXML private ComboBox<String> genderBox, classGroupBox, stateComboBox, lgaComboBox;
    @FXML private DatePicker dobPicker;
    @FXML private Label errorLabel, filePathLabel;
    @FXML private Button submitBtn, browseBtn;
    @FXML private ImageView passportImageView;

    // Previous Academic History Input Nodes
    @FXML private HBox juniorSecHistoryBox; 
    @FXML private TextField primarySchoolField, primaryFromField, primaryToField;
    @FXML private TextField juniorSecSchoolField, juniorSecFromField, juniorSecToField;

    private final StudentDAO studentDAO = new StudentDAO();
    private final Map<String, List<String>> nigeriaGeoMap = new TreeMap<>();
    
    private boolean saveClicked = false;
    private String studentAppId;
    private String studentPassword;
    private String uploadedPassportPath = "/assets/passports/default.png";

    @FXML
    public void initialize() {
        loadAllNigerianStatesAndLGAs();
        stateComboBox.setItems(FXCollections.observableArrayList(nigeriaGeoMap.keySet()));
        
        stateComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) {
                lgaComboBox.setItems(FXCollections.observableArrayList(nigeriaGeoMap.get(newV)));
                lgaComboBox.getSelectionModel().selectFirst();
            }
        });
    }

    /**
     * Called by parent Dashboard to inject pre-validated data and toggle history fields
     */
    public void setPreValidatedData(String appId, String password, String targetClass) {
        this.studentAppId = appId;
        this.studentPassword = password;
        
        classGroupBox.setItems(FXCollections.observableArrayList(targetClass));
        classGroupBox.getSelectionModel().select(targetClass);
        classGroupBox.setDisable(true); 

        // AUTOMATED SCREEN DYNAMICS: Hide or show Junior Secondary inputs based on class prefix
        if (targetClass.startsWith("JS")) {
            juniorSecHistoryBox.setVisible(false);
            juniorSecHistoryBox.setManaged(false); 
        } else {
            juniorSecHistoryBox.setVisible(true);
            juniorSecHistoryBox.setManaged(true);
        }
    }

    @FXML
    private void handlePassportUpload() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Student Passport Photograph Image");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Image Files (*.png, *.jpg, *.jpeg)", "*.png", "*.jpg", "*.jpeg")
        );
        
        File selectedFile = fileChooser.showOpenDialog(browseBtn.getScene().getWindow());
        if (selectedFile != null) {
            this.uploadedPassportPath = selectedFile.getAbsolutePath();
            filePathLabel.setText(selectedFile.getName());
            Image image = new Image(selectedFile.toURI().toString());
            passportImageView.setImage(image);
        }
    }

    /**
     * Handles student demographic processing and securely synchronizes passport image files 
     * directly into the web application's centralized templates repository context.
     */
    @FXML
    private void handleSaveStudent() {
        String fn = firstNameField.getText().trim();
        String ln = lastNameField.getText().trim();
        String gen = genderBox.getValue();
        LocalDate dob = dobPicker.getValue();
        String cls = classGroupBox.getValue();
        String state = stateComboBox.getValue();
        String lga = lgaComboBox.getValue();
        String addr = addressField.getText().trim();
        String gName = guardianNameField.getText().trim();
        String gPhone = guardianPhoneField.getText().trim();

        String pSchool = primarySchoolField.getText().trim();
        String pFrom = primaryFromField.getText().trim();
        String pTo = primaryToField.getText().trim();
        
        String jsSchool = "";
        String jsFrom = "";
        String jsTo = "";

        if (fn.isEmpty() || ln.isEmpty() || gen == null || dob == null || cls == null || state == null || lga == null || addr.isEmpty() || gName.isEmpty() || gPhone.isEmpty() || pSchool.isEmpty() || pFrom.isEmpty() || pTo.isEmpty()) {
            errorLabel.setText("⚠️ Complete all required demographic and school history fields!");
            return;
        }

        if (cls.startsWith("SS")) {
            jsSchool = juniorSecSchoolField.getText().trim();
            jsFrom = juniorSecFromField.getText().trim();
            jsTo = juniorSecToField.getText().trim();
            
            if (jsSchool.isEmpty() || jsFrom.isEmpty() || jsTo.isEmpty()) {
                errorLabel.setText("⚠️ Senior applicants must complete Junior Secondary history rows!");
                return;
            }
        }

        // 🔄 1. CENTRALIZED IMAGE SYNCHRONIZATION BACKEND LOOP
        String finalPassportDatabasePath = this.uploadedPassportPath; 
        File sourceImageFile = new File(this.uploadedPassportPath);

        if (sourceImageFile.exists() && sourceImageFile.isFile()) {
            String serverDirPath = "C:\\Users\\TOSHIBA\\Desktop\\SchoolApiServer\\passports";
            File targetDir = new File(serverDirPath);
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }

            String cleanAppId = this.studentAppId.trim().replace("/", "_").replace("\\", "_");
            String ext = ".jpg";
            String origName = sourceImageFile.getName();
            int lastDot = origName.lastIndexOf('.');
            if (lastDot >= 0) {
                ext = origName.substring(lastDot).toLowerCase();
            }

            File destinationFile = new File(targetDir, cleanAppId + ext);
            try {
                java.nio.file.Files.copy(sourceImageFile.toPath(), destinationFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                finalPassportDatabasePath = destinationFile.getAbsolutePath();
            } catch (IOException ioEx) {
                System.err.println("❌ Failed copying passport snapshot across system layers: " + ioEx.getMessage());
            }
        }

        // 2. Map properties cleanly into model
        Student student = new Student(
            0, studentAppId, fn, ln, gen.substring(0, 1), dob, state, lga, addr, cls,
            gName, "Parent", gPhone, finalPassportDatabasePath, LocalDateTime.now(),
            true, studentPassword, null, pSchool, pFrom, pTo, jsSchool, jsFrom, jsTo
        );

        // 3. Persist and Dispatch Viewer Frame Immediately
        if (studentDAO.insertStudent(student)) {
            saveClicked = true;
            
            try {
                // Standardize filename to use Application ID safely (e.g., SS_2026_EAE89)
                String cleanAppId = this.studentAppId.trim().replace("/", "_").replace("\\", "_");
                
                File outputFolder = new File("C:\\Users\\TOSHIBA\\Desktop\\SchoolApiServer\\clearance_slips");
                if (!outputFolder.exists()) {
                    outputFolder.mkdirs();
                }
                
                File targetPdfFile = new File(outputFolder, cleanAppId + "-Clearance.pdf");
                
                // Invoke OpenPDF engine compilation directly 
                com.nigeriaschools.util.AdmissionPdfEngine.generateAdmissionSlipPdf(student, studentPassword, targetPdfFile);
                
                // 🔥 LAUNCH NATIVE PDF VIEWER INSTANTLY
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(targetPdfFile);
                } else {
                    System.err.println("❌ System AWT Desktop is unsupported on this platform instance.");
                }
                
            } catch (Exception pdfEx) {
                System.err.println("❌ Failure inside native OpenPDF handler pipeline: " + pdfEx.getMessage());
                pdfEx.printStackTrace();
            }
            
            // Close the intake window wizard layout stage context cleanly
            ((Stage) browseBtn.getScene().getWindow()).close(); 
        } else {
            errorLabel.setText("❌ SQL Database Constraint Rejected. Check console parameters.");
        }
    }

    @FXML
    private void handleCancel() {
        saveClicked = false;
        ((Stage) browseBtn.getScene().getWindow()).close();
    }
    
    private void triggerAdmissionClearanceSlip(Student student) {
        try {
            System.out.println("🔄 Loading AdmissionSlip.fxml...");
            FXMLLoader slipLoader = new FXMLLoader(getClass().getResource("/AdmissionSlip.fxml"));
            Parent slipRoot = slipLoader.load();
            
            Stage slipStage = new Stage();
            slipStage.setTitle("Official Registration Clearance Acknowledgement Slip");
            slipStage.initModality(Modality.APPLICATION_MODAL);
            
            if (browseBtn != null && browseBtn.getScene() != null && browseBtn.getScene().getWindow() != null) {
                slipStage.initOwner(browseBtn.getScene().getWindow());
            }
            
            slipStage.setScene(new Scene(slipRoot));
            
            AdmissionSlipController slipController = slipLoader.getController();
            slipController.populateSlipData(student, studentPassword, passportImageView.getImage());
            
            System.out.println("🚀 Spawning slip layout window...");
            slipStage.showAndWait(); 
            
        } catch (Exception e) {
            System.err.println("❌ ERROR DETAILS ON SLIP INITIALIZATION:");
            e.printStackTrace();
        }
    }

    public boolean isSaveClicked() { return saveClicked; }

    private void loadAllNigerianStatesAndLGAs() {
        nigeriaGeoMap.put("Abia", Arrays.asList("Aba North", "Aba South", "Arochukwu", "Ohafia", "Umuahia North", "Umuahia South"));
        nigeriaGeoMap.put("Adamawa", Arrays.asList("Fufore", "Ganye", "Mubi North", "Mubi South", "Yola North", "Yola South"));
        nigeriaGeoMap.put("Akwa Ibom", Arrays.asList("Eket", "Ikot Ekpene", "Oron", "Uyo", "Ikono"));
        nigeriaGeoMap.put("Anambra", Arrays.asList("Awka North", "Awka South", "Idemili North", "Onitsha North", "Onitsha South", "Nnewi North"));
        nigeriaGeoMap.put("Bauchi", Arrays.asList("Alkaleri", "Bauchi", "Katagum", "Misau", "Tafawa Balewa"));
        nigeriaGeoMap.put("Bayelsa", Arrays.asList("Brass", "Ekeremor", "Kolokuma/Opokuma", "Nembe", "Ogbia", "Yenagoa"));
        nigeriaGeoMap.put("Benue", Arrays.asList("Gboko", "Makurdi", "Otukpo", "Katsina-Ala", "Vandeikya"));
        nigeriaGeoMap.put("Borno", Arrays.asList("Biu", "Chibok", "Gwoza", "Maiduguri", "Jere"));
        nigeriaGeoMap.put("Cross River", Arrays.asList("Calabar Municipal", "Calabar South", "Ikom", "Obudu", "Ogoja"));
        nigeriaGeoMap.put("Delta", Arrays.asList("Asaba", "Warri South", "Ughelli North", "Sapele", "Agbor"));
        nigeriaGeoMap.put("Ebonyi", Arrays.asList("Abakaliki", "Afikpo North", "Onicha", "Ezza North"));
        nigeriaGeoMap.put("Edo", Arrays.asList("Benin City", "Oredo", "Esan North-East", "Etsako West"));
        nigeriaGeoMap.put("Ekiti", Arrays.asList("Ado Ekiti", "Ikere", "Oye", "Ikole"));
        nigeriaGeoMap.put("Enugu", Arrays.asList("Enugu East", "Enugu North", "Enugu South", "Nsukka", "Oji River"));
        nigeriaGeoMap.put("Gombe", Arrays.asList("Akko", "Balanga", "Billiri", "Gombe", "Kaltungo"));
        nigeriaGeoMap.put("Imo", Arrays.asList("Owerri Municipal", "Owerri North", "Orlu", "Okigwe", "Mbaitoli"));
        nigeriaGeoMap.put("Jigawa", Arrays.asList("Dutse", "Hadejia", "Kazaure", "Gumel", "Ringim"));
        nigeriaGeoMap.put("Kaduna", Arrays.asList("Birnin Gwari", "Chikun", "Giwa", "Igabi", "Ikara", "Jaba", "Jema'a", "Kachia", "Kaduna North", "Kaduna South", "Kagarko", "Kajuru", "Kaura", "Kauru", "Kubau", "Kudan", "Lere", "Makarfi", "Sabon Gari", "Sanga", "Soba", "Zangon Kataf", "Zaria"));
        nigeriaGeoMap.put("Kano", Arrays.asList("Dala", "Fagge", "Gwale", "Kano Municipal", "Tarauni"));
        nigeriaGeoMap.put("Katsina", Arrays.asList("Daura", "Funtua", "Katsina", "Malumfashi"));
        nigeriaGeoMap.put("Kebbi", Arrays.asList("Argungu", "Birnin Kebbi", "Jega", "Yauri"));
        nigeriaGeoMap.put("Kogi", Arrays.asList("Adavi", "Ajaokuta", "Ankpa", "Lokoja", "Okene"));
        nigeriaGeoMap.put("Kwara", Arrays.asList("Asa", "Ilorin East", "Ilorin South", "Ilorin West", "Offa"));
        nigeriaGeoMap.put("Lagos", Arrays.asList("Alimosho", "Badagry", "Ikeja", "Ikorodu", "Lagos Island", "Surulere"));
        nigeriaGeoMap.put("Nasarawa", Arrays.asList("Akwanga", "Keffi", "Lafia", "Nasarawa"));
        nigeriaGeoMap.put("Niger", Arrays.asList("Bida", "Chanchaga", "Kontagora", "Minna", "Suleja"));
        nigeriaGeoMap.put("Ogun", Arrays.asList("Abeokuta North", "Abeokuta South", "Ijebu Ode", "Obafemi Owode"));
        nigeriaGeoMap.put("Ondo", Arrays.asList("Akoko North-East", "Akure North", "Akure South", "Ondo West"));
        nigeriaGeoMap.put("Osun", Arrays.asList("Ede North", "Ife Central", "Ilesa East", "Osogbo"));
        nigeriaGeoMap.put("Oyo", Arrays.asList("Ibadan North", "Ibadan South-West", "Iseyin", "Ogbomosho North"));
        nigeriaGeoMap.put("Plateau", Arrays.asList("Barkin Ladi", "Jos North", "Jos South", "Pankshin"));
        nigeriaGeoMap.put("Rivers", Arrays.asList("Ahoada East", "Bonny", "Obio/Akpor", "Port Harcourt"));
        nigeriaGeoMap.put("Sokoto", Arrays.asList("Gwadabawa", "Illela", "Sokoto North", "Sokoto South"));
        nigeriaGeoMap.put("Taraba", Arrays.asList("Bali", "Jalingo", "Sardauna", "Wukari"));
        nigeriaGeoMap.put("Yobe", Arrays.asList("Bade", "Damaturu", "Gashua", "Potiskum"));
        nigeriaGeoMap.put("Zamfara", Arrays.asList("Anka", "Gusau", "Kaura Namoda", "Maradun"));
    }
}