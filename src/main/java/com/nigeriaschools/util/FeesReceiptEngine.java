package com.nigeriaschools.util;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.nigeriaschools.model.SchoolFeesPayment;

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class FeesReceiptEngine {

    /**
     * Programmatically compiles an authoritative, secure A4 Student Fees Receipt Voucher with an embedded signature image using OpenPDF.
     */
    public static String generateReceiptPdf(SchoolFeesPayment payment) throws IOException {
        String userHome = System.getProperty("user.home");
        String safeFileName = "RECEIPT-" + payment.getStudentRegNo().replace("/", "-") + "-" + payment.getTerm() + ".pdf";
        File destinationFile = new File(userHome + File.separator + "Desktop" + File.separator + safeFileName);

        // 🔥 PERMANENT SAFEGUARD: Intercept early if the file is locked by Windows
        if (destinationFile.exists() && !destinationFile.canWrite()) {
            throw new IOException("The target document file is currently open in another viewer window.\n" +
                    "Please close Adobe Reader or your browser window displaying this receipt and try again.");
        }

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
        String timestamp = dtf.format(LocalDateTime.now());

        // Define Document with 36pt (0.5 inch) uniform margins
        Document document = new Document(PageSize.A4, 36, 36, 36, 36);

        try {
            PdfWriter writer = PdfWriter.getInstance(document, new FileOutputStream(destinationFile));
            
            // 1. OUTER DOCUMENT BOUNDARY BORDER (Dynamic Event Hook)
            writer.setPageEvent(new com.lowagie.text.pdf.PdfPageEventHelper() {
                @Override
                public void onEndPage(PdfWriter writer, Document doc) {
                    com.lowagie.text.pdf.PdfContentByte cb = writer.getDirectContent();
                    cb.setLineWidth(1.5f);
                    cb.setColorStroke(new Color(30, 61, 89)); // School theme #1e3d59
                    cb.rectangle(30, 30, doc.getPageSize().getWidth() - 60, doc.getPageSize().getHeight() - 60);
                    cb.stroke();
                }
            });

            document.open();

            // 2. OFFICIAL BANNER TOP GRAPHICS
            Paragraph officialBanner = new Paragraph("OFFICIAL BURSARY RECEIPT VOUCHER", 
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Font.BOLD, new Color(46, 125, 50))); // Nigerian Green #2E7D32
            officialBanner.setAlignment(Element.ALIGN_CENTER);
            document.add(officialBanner);

            Paragraph systemTitle = new Paragraph("NIGERIA SECONDARY SCHOOL SYSTEM", 
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, Font.BOLD, new Color(30, 61, 89)));
            systemTitle.setAlignment(Element.ALIGN_CENTER);
            systemTitle.setSpacingAfter(5f);
            document.add(systemTitle);

            // Horizontal Separator Line
            Paragraph hr = new Paragraph("_______________________________________________________________________________", 
                    FontFactory.getFont(FontFactory.HELVETICA, 10, Color.LIGHT_GRAY));
            hr.setAlignment(Element.ALIGN_CENTER);
            hr.setSpacingAfter(20f);
            document.add(hr);

            // 3. TRANSACTION LEDGER PROFILE BOX
            PdfPTable ledgerTable = new PdfPTable(2);
            ledgerTable.setWidthPercentage(100);
            ledgerTable.setWidths(new float[]{40f, 60f}); // Structure distribution ratio

            String[][] receiptData = {
                {"Student Registration ID:", payment.getStudentRegNo()},
                {"Assigned Class Level:", payment.getCurrentClass()},
                {"Academic Session / Term:", payment.getAcademicYear() + " - " + payment.getTerm() + " Term"},
                {"Total Institutional Fee due:", "NGN " + payment.getAmountDue().toString()},
                {"Total Cash Amount Paid:", "NGN " + payment.getAmountPaid().toString()},
                {"Outstanding Balance Deficit:", "NGN " + payment.getBalance().toString()}
            };

            // Loop through default rows safely
            for (String[] row : receiptData) {
                addLedgerRow(ledgerTable, row[0], row[1], Color.BLACK, false);
            }

            // Apply Conditional Styling rules for the Status Row 
            Color statusColor = "Fully Paid".equalsIgnoreCase(payment.getPaymentStatus()) 
                    ? new Color(46, 125, 50)  // Green
                    : new Color(211, 47, 47); // Red
            addLedgerRow(ledgerTable, "Account Ledger Status:", payment.getPaymentStatus().toUpperCase(), statusColor, true);

            document.add(ledgerTable);

            // 4. LOWER FOOTER META AND SIGNATURE BLOCK (Side-by-Side Dual Column Grid Layout)
            PdfPTable footerTable = new PdfPTable(2);
            footerTable.setWidthPercentage(100);
            footerTable.setWidths(new float[]{60f, 40f});
            footerTable.setSpacingBefore(50f);
            footerTable.getDefaultCell().setBorder(Rectangle.NO_BORDER);

            // Left Column Cell: Audit Tokens
            PdfPCell leftCell = new PdfPCell();
            leftCell.setBorder(Rectangle.NO_BORDER);
            
            Paragraph auditStamp = new Paragraph("Bursary Audit Stamp Track Token: " + UUID.randomUUID().toString().toUpperCase(),
                    FontFactory.getFont(FontFactory.TIMES_ITALIC, 8, Color.GRAY));
            Paragraph timestampPara = new Paragraph("Transaction processed on: " + timestamp,
                    FontFactory.getFont(FontFactory.TIMES_ITALIC, 8, Color.GRAY));
            
            leftCell.addElement(auditStamp);
            leftCell.addElement(timestampPara);
            footerTable.addCell(leftCell);

            // Right Column Cell: Signature Asset Box
            PdfPCell rightCell = new PdfPCell();
            rightCell.setBorder(Rectangle.NO_BORDER);
            rightCell.setHorizontalAlignment(Element.ALIGN_CENTER);

            // Fetch transparent asset stream straight from app JAR resources folder
            try (InputStream is = FeesReceiptEngine.class.getResourceAsStream("/assets/signature.png")) {
                if (is != null) {
                    byte[] imageBytes = is.readAllBytes();
                    Image signatureImg = Image.getInstance(imageBytes);
                    signatureImg.scaleAbsolute(80, 35); // Keep original width/height proportions
                    signatureImg.setAlignment(Element.ALIGN_CENTER);
                    rightCell.addElement(signatureImg);
                } else {
                    Paragraph errorPlaceholder = new Paragraph("[ NO IMAGE SIGNATURE ]", FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, Color.RED));
                    errorPlaceholder.setAlignment(Element.ALIGN_CENTER);
                    rightCell.addElement(errorPlaceholder);
                }
            } catch (Exception sigEx) {
                System.err.println("⚠️ OpenPDF Signature Loading Block Interrupted: " + sigEx.getMessage());
            }

            // 🔥 FIXED: Added the complete closing layout sequences to print out metadata signatures cleanly
            Paragraph signatureLine = new Paragraph("_______________________", FontFactory.getFont(FontFactory.HELVETICA, 10, Color.GRAY));
            signatureLine.setAlignment(Element.ALIGN_CENTER);
            Paragraph authorizedTag = new Paragraph("Authorized Signatory", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, new Color(30, 61, 89)));
            authorizedTag.setAlignment(Element.ALIGN_CENTER);

            rightCell.addElement(signatureLine);
            rightCell.addElement(authorizedTag);
            footerTable.addCell(rightCell);

            document.add(footerTable);

        } catch (DocumentException e) {
            throw new IOException("OpenPDF Document pipeline initialization failure: " + e.getMessage(), e);
        } finally {
            // 🔥 FIXED: Enforces proper document termination so the operating system instantly drops the file lock
            document.close();
        }

        return destinationFile.getAbsolutePath();
    }

    /**
     * Formatting helper logic to push key-value cells clean with spatial heights
     */
    private static void addLedgerRow(PdfPTable table, String field, String value, Color textColour, boolean isBoldValue) {
        PdfPCell keyCell = new PdfPCell(new Paragraph(field, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK)));
        keyCell.setBorder(Rectangle.NO_BORDER);
        keyCell.setPaddingBottom(12f); 

        int valueWeight = isBoldValue ? Font.BOLD : Font.NORMAL;
        PdfPCell valCell = new PdfPCell(new Paragraph(value, FontFactory.getFont(FontFactory.HELVETICA, 10, valueWeight, textColour)));
        valCell.setBorder(Rectangle.NO_BORDER);
        valCell.setPaddingBottom(12f);

        table.addCell(keyCell);
        table.addCell(valCell);
    }
}
