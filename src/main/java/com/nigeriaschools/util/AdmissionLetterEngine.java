package com.nigeriaschools.util;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.*;
import com.nigeriaschools.model.Student;

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public class AdmissionLetterEngine {

    public static String generateAdmissionLetter(Student student) throws IOException {
        String userHome = System.getProperty("user.home");
        String safeFileName = "ADMISSION-LETTER-" + student.getApplicationId().replace("/", "-") + ".pdf";
        File destinationFile = new File(userHome + File.separator + "Desktop" + File.separator + safeFileName);

        Document document = new Document(PageSize.A4, 54, 54, 54, 54);

        try {
        	PdfWriter writer = PdfWriter.getInstance(document, new FileOutputStream(destinationFile));

        	// Elegant border + watermark
        	writer.setPageEvent(new PdfPageEventHelper() {
        	    @Override
        	    public void onEndPage(PdfWriter writer, Document doc) {
        	        PdfContentByte cb = writer.getDirectContentUnder(); // draw under text

        	        // Draw border
        	        cb.setLineWidth(1.5f);
        	        cb.setColorStroke(new Color(30, 61, 89));
        	        cb.rectangle(35, 35, doc.getPageSize().getWidth() - 70, doc.getPageSize().getHeight() - 70);
        	        cb.stroke();

        	        // Add watermark logo
        	        try {
        	            Image watermark = Image.getInstance(
        	                AdmissionLetterEngine.class.getResource("/assets/logo.png")
        	            );
        	            watermark.scaleToFit(300, 300);

        	            // Center position
        	            float x = (doc.getPageSize().getWidth() - watermark.getScaledWidth()) / 2;
        	            float y = (doc.getPageSize().getHeight() - watermark.getScaledHeight()) / 2;
        	            watermark.setAbsolutePosition(x, y);

        	            // Apply transparency
        	            PdfGState gs = new PdfGState();
        	            gs.setFillOpacity(0.10f); // 15% opacity
        	            cb.saveState();
        	            cb.setGState(gs);
        	            cb.addImage(watermark);
        	            cb.restoreState();
        	        } catch (Exception e) {
        	            System.err.println("⚠ Could not load watermark logo: " + e.getMessage());
        	        }
        	    }
        	});



            document.open();

            // 0. Logo
            try {
                Image logoImg = Image.getInstance(AdmissionLetterEngine.class.getResource("/assets/logo.png"));
                logoImg.scaleToFit(65, 65);
                logoImg.setAlignment(Element.ALIGN_CENTER);
                logoImg.setSpacingAfter(-3f);
                logoImg.setSpacingBefore(-3f);
                document.add(logoImg);
            } catch (Exception e) {
                System.err.println("⚠ Could not load school logo: " + e.getMessage());
            }

            // 1. School Name + Address
            Paragraph schoolName = new Paragraph("NIGERIA SECONDARY SCHOOL SYSTEM",
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, Font.BOLD, new Color(30, 61, 89)));
            schoolName.setAlignment(Element.ALIGN_CENTER);
            schoolName.setSpacingAfter(0f);
            schoolName.setSpacingBefore(-3f);
            document.add(schoolName);

            Paragraph address = new Paragraph("Official Portal Operations Hub, Registry Department\nEmail: registry@nigeriaschools.edu.ng",
                    FontFactory.getFont(FontFactory.HELVETICA, 10, Color.DARK_GRAY));
            address.setAlignment(Element.ALIGN_CENTER);
            address.setSpacingAfter(3f);
            document.add(address);

            // Separator
            Paragraph hr = new Paragraph("________________________________________________________",
                    FontFactory.getFont(FontFactory.HELVETICA, 15, Color.LIGHT_GRAY));
            hr.setAlignment(Element.ALIGN_CENTER);
            hr.setSpacingAfter(1f);
            document.add(hr);

            // 2. Date + Time
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy");
            String formattedDate = LocalDate.now().format(dateFormatter);
            
            Paragraph datePara = new Paragraph("Date: " + formattedDate,
                    FontFactory.getFont(FontFactory.HELVETICA, 11));
            datePara.setSpacingAfter(20f);
            document.add(datePara);

            // 3. Salutation
            String fullName = student.getFirstName().toUpperCase() + " " + student.getLastName().toUpperCase();
            Paragraph salutation = new Paragraph("Dear " + fullName + ",",
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12));
            salutation.setSpacingAfter(10f);
            document.add(salutation);

            // Subject Heading
            Paragraph subjectHeading = new Paragraph("OFFER OF PROVISIONAL ADMISSION INTO " + student.getCurrentClass(),
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Font.UNDERLINE, new Color(46, 125, 50)));
            subjectHeading.setSpacingAfter(20f);
            subjectHeading.setAlignment(Element.ALIGN_CENTER);
            document.add(subjectHeading);

            // 4. Body
            Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 11, Color.BLACK);

            Paragraph p1 = new Paragraph("We are pleased to inform you that you have been offered provisional admission under the reference registration index: "
                    + student.getApplicationId() + ". Your placement has been processed successfully into class " + student.getCurrentClass() + ".", bodyFont);
            p1.setLeading(18f);
            p1.setSpacingAfter(12f);
            document.add(p1);

            Paragraph p2 = new Paragraph("Please ensure you generate your official bursary payment receipt voucher from your dashboard to complete tuition registration. Terminal grade record printing remains locked until all school fee entries are cleared.", bodyFont);
            p2.setLeading(18f);
            p2.setSpacingAfter(40f);
            document.add(p2);

            // 5. Signature
            Paragraph closing = new Paragraph("Yours faithfully,", bodyFont);
            closing.setSpacingBefore(10f);
            document.add(closing);

            try {
                Image signatureImg = Image.getInstance(AdmissionLetterEngine.class.getResource("/assets/signature.png"));
                signatureImg.scaleToFit(95, 45);
                signatureImg.setAlignment(Element.ALIGN_LEFT);
                signatureImg.setSpacingAfter(-10f);
                document.add(signatureImg);
            } catch (Exception e) {
                System.err.println("⚠ Could not load registrar signature: " + e.getMessage());
            }
            Paragraph registrar = new Paragraph("_________________________\nOffice of the Registrar Board\nNigeria Secondary School Network Operations", bodyFont);
            registrar.setSpacingBefore(-10f);
            document.add(registrar);

        } catch (DocumentException e) {
            throw new IOException("OpenPDF letter generation chain broke: " + e.getMessage(), e);
        } finally {
            document.close();
        }

        return destinationFile.getAbsolutePath();
    }
}
