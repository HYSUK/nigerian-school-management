package com.nigeriaschools.util;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import com.nigeriaschools.model.Student;

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public class AdmissionPdfEngine {

    /**
     * Compiles student data streams directly into a clean, vector OpenPDF layout context.
     * Fixed signature to accept an explicit destination File pointer injected dynamically.
     */
    public static void generateAdmissionSlipPdf(Student student, String rawPassword, File destinationFile) throws IOException {
        
        // Safer margins: 60 pts (~0.8 inch)
        Document document = new Document(PageSize.A4, 60, 60, 60, 60);

        try {
            PdfWriter writer = PdfWriter.getInstance(document, new FileOutputStream(destinationFile));

            // Adjusted border rectangle to respect margins
            writer.setPageEvent(new PdfPageEventHelper() {
                @Override
                public void onEndPage(PdfWriter writer, Document doc) {
                    PdfContentByte cb = writer.getDirectContent();
                    cb.setLineWidth(2f);
                    cb.setColorStroke(new Color(30, 61, 89));
                    cb.rectangle(60, 60, doc.getPageSize().getWidth() - 120, doc.getPageSize().getHeight() - 120);
                    cb.stroke();
                }
            });

            document.open();

            // Header section
            PdfPTable headerGrid = new PdfPTable(2);
            headerGrid.setWidthPercentage(95);
            headerGrid.setWidths(new float[]{15f, 85f});
            headerGrid.getDefaultCell().setBorder(Rectangle.NO_BORDER);

            PdfPCell logoCell = new PdfPCell();
            logoCell.setBorder(Rectangle.NO_BORDER);
            logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            try (InputStream logoStream = AdmissionPdfEngine.class.getResourceAsStream("/assets/logo.png")) {
                if (logoStream != null) {
                    Image logoImg = Image.getInstance(logoStream.readAllBytes());
                    logoImg.scaleToFit(55, 55);
                    logoCell.addElement(logoImg);
                }
            } catch (Exception e) {
                System.err.println("⚠️ Logo not found: " + e.getMessage());
            }
            headerGrid.addCell(logoCell);

            PdfPCell textCell = new PdfPCell();
            textCell.setBorder(Rectangle.NO_BORDER);
            textCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            textCell.addElement(new Paragraph("FEDERAL REPUBLIC OF NIGERIA",
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Font.BOLD, new Color(46, 125, 50))));
            textCell.addElement(new Paragraph("COMPREHENSIVE STUDENT ENROLLMENT DOSSIER",
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Font.BOLD, new Color(30, 61, 89))));
            headerGrid.addCell(textCell);

            document.add(headerGrid);

            // Separator line
            Paragraph hr = new Paragraph("____________________________________________________________",
                    FontFactory.getFont(FontFactory.HELVETICA, 10, Color.LIGHT_GRAY));
            hr.setSpacingAfter(10f);
            document.add(hr);

            // Master layout table
            PdfPTable masterLayoutTable = new PdfPTable(2);
            masterLayoutTable.setWidthPercentage(92);
            masterLayoutTable.setWidths(new float[]{75f, 25f});
            masterLayoutTable.setSplitLate(false);
            masterLayoutTable.setSplitRows(true);
            masterLayoutTable.getDefaultCell().setBorder(Rectangle.NO_BORDER);

            // Data grid table
            PdfPTable dataGridTable = new PdfPTable(2);
            dataGridTable.setWidthPercentage(95);
            dataGridTable.setWidths(new float[]{35f, 65f});

            String[][] dataGrid = {
                    {"Application ID:", student.getApplicationId() + " [" + student.getCurrentClass() + "]"},
                    {"Full Student Name:", student.getFirstName().toUpperCase() + " " + student.getLastName().toUpperCase()},
                    {"Gender / DOB:", ("M".equalsIgnoreCase(student.getGender()) ? "Male" : "Female") + " / " + student.getDateOfBirth().toString()},
                    {"State / LGA of Origin:", student.getStateOfOrigin() + " State / " + student.getLga() + " LGA"},
                    {"Home Address:", student.getHomeAddress()},
                    {"Portal Security PIN:", rawPassword},
                    {"Guardian Contact:", student.getGuardianName() + " (" + student.getGuardianPhone() + ")"},
                    {"Primary History:", student.getPrevPrimarySchool() + " (" + student.getPrimaryFromYear() + "-" + student.getPrimaryToYear() + ")"}
            };

            for (String[] row : dataGrid) {
                addGridCell(dataGridTable, row[0], row[1]);
            }

            if (student.getCurrentClass().startsWith("SS") && student.getPrevJuniorSecSchool() != null && !student.getPrevJuniorSecSchool().isEmpty()) {
                addGridCell(dataGridTable, "Junior Sec History:", student.getPrevJuniorSecSchool() + " (" + student.getJuniorSecFromYear() + "-" + student.getJuniorSecToYear() + ")");
            }

            PdfPCell leftCellWrapper = new PdfPCell(dataGridTable);
            leftCellWrapper.setBorder(Rectangle.NO_BORDER);
            masterLayoutTable.addCell(leftCellWrapper);

            // Passport photo
            PdfPCell rightCellWrapper = new PdfPCell();
            rightCellWrapper.setBorder(Rectangle.NO_BORDER);
            rightCellWrapper.setHorizontalAlignment(Element.ALIGN_RIGHT);

            File imgFile = new File(student.getLocalPassportPath());
            if (imgFile.exists() && imgFile.isFile()) {
                try {
                    Image passportImg = Image.getInstance(student.getLocalPassportPath());
                    passportImg.scaleToFit(80, 100);
                    passportImg.setAlignment(Element.ALIGN_RIGHT);
                    rightCellWrapper.addElement(passportImg);
                } catch (Exception imgEx) {
                    System.err.println("⚠️ Passport picture read fault: " + imgEx.getMessage());
                }
            } else {
                rightCellWrapper.addElement(new Paragraph("[ NO PASSPORT IMAGE ]",
                        FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, Color.LIGHT_GRAY)));
            }

            masterLayoutTable.addCell(rightCellWrapper);
            document.add(masterLayoutTable);

            // Instructional notes
            PdfPTable directiveBox = new PdfPTable(1);
            directiveBox.setWidthPercentage(95);
            directiveBox.setSpacingBefore(12f);

            PdfPCell innerBox = new PdfPCell();
            innerBox.setBackgroundColor(new Color(247, 250, 252));
            innerBox.setBorderColor(new Color(226, 232, 240));
            innerBox.setPadding(8);
            innerBox.addElement(new Paragraph("INSTRUCTIONAL DIRECTIVE NOTES FOR FRESH INTAKES:",
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Font.BOLD, new Color(45, 55, 72))));
            innerBox.addElement(new Paragraph("Please present this generated PDF clearance layout directly to the bursar workspace console to activate school fees tracking profiles.",
                    FontFactory.getFont(FontFactory.HELVETICA, 8, Color.DARK_GRAY)));
            directiveBox.addCell(innerBox);

            document.add(directiveBox);

            // Footer
            PdfPTable footerGridRow = new PdfPTable(2);
            footerGridRow.setWidthPercentage(95);
            footerGridRow.setSpacingBefore(30f);
            footerGridRow.setWidths(new float[]{60f, 40f});
            footerGridRow.getDefaultCell().setBorder(Rectangle.NO_BORDER);

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy");
            String todayStr = LocalDate.now().format(formatter);

            PdfPCell dateStampCell = new PdfPCell();
            dateStampCell.setBorder(Rectangle.NO_BORDER);
            dateStampCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
            dateStampCell.addElement(new Paragraph("Official System Stamp Date: " + todayStr,
                    FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, Color.DARK_GRAY)));
            footerGridRow.addCell(dateStampCell);

            PdfPCell signCell = new PdfPCell();
            signCell.setBorder(Rectangle.NO_BORDER);
            signCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            Paragraph line = new Paragraph("_______________________", FontFactory.getFont(FontFactory.HELVETICA, 10, Color.GRAY));
            Paragraph label = new Paragraph("Registrar / Academic Board Stamp Copy",
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, new Color(30, 61, 89)));
            line.setAlignment(Element.ALIGN_RIGHT);
            label.setAlignment(Element.ALIGN_RIGHT);
            signCell.addElement(line);
            signCell.addElement(label);
            footerGridRow.addCell(signCell);

            document.add(footerGridRow);

        } catch (DocumentException e) {
            throw new IOException("OpenPDF Document initialization failed: " + e.getMessage(), e);
        } finally {
            if (document.isOpen()) {
                document.close();
            }
        }
    }

    private static void addGridCell(PdfPTable table, String label, String value) {
        PdfPCell cellKey = new PdfPCell(new Paragraph(label,
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK)));
        cellKey.setBorder(Rectangle.NO_BORDER);
        cellKey.setPaddingBottom(5f);

        PdfPCell cellVal = new PdfPCell(new Paragraph(value,
                FontFactory.getFont(FontFactory.HELVETICA, 10, Color.DARK_GRAY)));
        cellVal.setBorder(Rectangle.NO_BORDER);
        cellVal.setPaddingBottom(5f);

        table.addCell(cellKey);
        table.addCell(cellVal);
    }
}