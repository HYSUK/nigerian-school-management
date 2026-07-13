package com.nigeriaschools.util;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import com.nigeriaschools.dao.AcademicRecordDAO;
import com.nigeriaschools.dao.SchoolFeesPaymentDAO;
import com.nigeriaschools.model.AcademicRecord;

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class TerminalReportCardEngine {

    private static final SchoolFeesPaymentDAO feesDAO = new SchoolFeesPaymentDAO();
    private static final AcademicRecordDAO recordDAO = new AcademicRecordDAO();

    public static String generateReportCardPdf(String studentRegNo, String currentClass, String session, String term) throws Exception {

        // 🔒 Financial clearance guard
        boolean isCleared = feesDAO.isStudentFinanciallyCleared(studentRegNo, session, term);
        if (!isCleared) {
            throw new IllegalAccessException("ACCESS BLOCKED: Outstanding tuition fees detected. Report card generation locked.");
        }

        // ✅ Use existing DAO method and filter records in Java
        List<AcademicRecord> studentPerformanceRows = new ArrayList<>();
        try {
            List<AcademicRecord> globalRecordsList = recordDAO.getRecordsByFilter(currentClass, session, term, "%");
            for (AcademicRecord record : globalRecordsList) {
                if (record.getStudentRegNo().equalsIgnoreCase(studentRegNo)) {
                    studentPerformanceRows.add(record);
                }
            }
        } catch (SQLException e) {
            throw new IOException("Database query failed: " + e.getMessage(), e);
        }

        // Target file
        String userHome = System.getProperty("user.home");
        String safeFileName = "REPORT-" + studentRegNo.replace("/", "-") + "-" + term + ".pdf";
        File destinationFile = new File(userHome + File.separator + "Desktop" + File.separator + safeFileName);

        // Safer margins
        Document document = new Document(PageSize.A4, 60, 60, 60, 60);

        try {
            PdfWriter writer = PdfWriter.getInstance(document, new FileOutputStream(destinationFile));

            // Border aligned with margins
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

            // Header
            Paragraph schoolTitle = new Paragraph("NIGERIA SECONDARY SCHOOL SYSTEM",
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, Font.BOLD, new Color(30, 61, 89)));
            schoolTitle.setAlignment(Element.ALIGN_CENTER);
            document.add(schoolTitle);

            Paragraph reportSubtitle = new Paragraph("OFFICIAL TERMINAL PERFORMANCE REPORT CARD",
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Font.BOLD, new Color(46, 125, 50)));
            reportSubtitle.setAlignment(Element.ALIGN_CENTER);
            reportSubtitle.setSpacingAfter(15f);
            document.add(reportSubtitle);

            // Metadata
            PdfPTable metaTable = new PdfPTable(4);
            metaTable.setWidthPercentage(95);
            metaTable.setSpacingAfter(20f);
            addMetaCell(metaTable, "Student Reg ID:", studentRegNo);
            addMetaCell(metaTable, "Class:", currentClass);
            addMetaCell(metaTable, "Term:", term);
            addMetaCell(metaTable, "Session:", session);
            document.add(metaTable);

            // Score table
            PdfPTable scoreTable = new PdfPTable(7);
            scoreTable.setWidthPercentage(95);
            scoreTable.setWidths(new float[]{30f, 10f, 10f, 10f, 12f, 10f, 18f});
            scoreTable.setSplitLate(false);
            scoreTable.setSplitRows(true);

            String[] headers = {"Subject", "CA1 (20)", "CA2 (20)", "Exam (60)", "Total", "Grade", "Remarks"};
            for (String headerText : headers) {
                PdfPCell cell = new PdfPCell(new Paragraph(headerText,
                        FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE)));
                cell.setBackgroundColor(new Color(30, 61, 89));
                cell.setPadding(6);
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                scoreTable.addCell(cell);
            }

            BigDecimal totalAccumulatedScores = BigDecimal.ZERO;
            int totalSubjectsCount = 0;

            for (AcademicRecord record : studentPerformanceRows) {
                scoreTable.addCell(createGridCell(record.getSubjectName(), Element.ALIGN_LEFT, false, Color.BLACK));
                scoreTable.addCell(createGridCell(record.getFirstCA().toString(), Element.ALIGN_CENTER, false, Color.BLACK));
                scoreTable.addCell(createGridCell(record.getSecondCA().toString(), Element.ALIGN_CENTER, false, Color.BLACK));
                scoreTable.addCell(createGridCell(record.getExamScore().toString(), Element.ALIGN_CENTER, false, Color.BLACK));

                // Total column bold
                scoreTable.addCell(createGridCell(record.getTotalScore().toString(), Element.ALIGN_CENTER, true, new Color(30, 61, 89)));

                // Grade coloring
                Color gradeColor = record.getGrade().startsWith("F") ? Color.RED : new Color(46, 125, 50);
                scoreTable.addCell(createGridCell(record.getGrade(), Element.ALIGN_CENTER, true, gradeColor));

                // Remarks
                scoreTable.addCell(createGridCell(record.getRemarks(), Element.ALIGN_CENTER, false, Color.BLACK));

                totalAccumulatedScores = totalAccumulatedScores.add(record.getTotalScore());
                totalSubjectsCount++;
            }

            if (totalSubjectsCount == 0) {
                PdfPCell fallback = new PdfPCell(new Paragraph("No academic records found.",
                        FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 10, Color.GRAY)));
                fallback.setColspan(7);
                fallback.setPadding(12);
                fallback.setHorizontalAlignment(Element.ALIGN_CENTER);
                scoreTable.addCell(fallback);
            }

            document.add(scoreTable);

            // Summary + Footer
            PdfPTable summaryTable = new PdfPTable(2);
            summaryTable.setWidthPercentage(95);
            summaryTable.setSpacingBefore(20f);
            summaryTable.setWidths(new float[]{60f, 40f});
            summaryTable.getDefaultCell().setBorder(Rectangle.NO_BORDER);

            if (totalSubjectsCount > 0) {
                double termAverage = totalAccumulatedScores.doubleValue() / totalSubjectsCount;
                Paragraph analyticalSummary = new Paragraph(
                        String.format("Cumulative Marks: %s\nTerm Average: %.2f%%",
                                totalAccumulatedScores.toString(), termAverage),
                        FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.DARK_GRAY));
                PdfPCell summaryCell = new PdfPCell(analyticalSummary);
                summaryCell.setBorder(Rectangle.NO_BORDER);
                summaryTable.addCell(summaryCell);
            } else {
                summaryTable.addCell(new PdfPCell(new Paragraph("No summary available.")));
            }

            // ✅ Timestamp + Exam Officer sign-off
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy hh:mm");
            String todayStr = LocalDateTime.now().format(formatter);

            PdfPCell signCell = new PdfPCell();
            signCell.setBorder(Rectangle.NO_BORDER);
            signCell.setHorizontalAlignment(Element.ALIGN_RIGHT);

            Paragraph line = new Paragraph("_______________________",
                    FontFactory.getFont(FontFactory.HELVETICA, 10, Color.GRAY));
            Paragraph label = new Paragraph("Exam Officer Signature",
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, new Color(30, 61, 89)));
            Paragraph date = new Paragraph("Generated on: " + todayStr,
                    FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, Color.DARK_GRAY));

            line.setAlignment(Element.ALIGN_RIGHT);
            label.setAlignment(Element.ALIGN_RIGHT);
            date.setAlignment(Element.ALIGN_RIGHT);

            signCell.addElement(line);
            signCell.addElement(label);
            signCell.addElement(date);

            summaryTable.addCell(signCell);
            document.add(summaryTable);

        } catch (DocumentException e) {
            throw new IOException("OpenPDF compilation failed: " + e.getMessage(), e);
        } finally {
            if (document.isOpen()) {
                document.close();
            }
        }

        return destinationFile.getAbsolutePath();
    }

    private static void addMetaCell(PdfPTable table, String label, String val) {
        Font lFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.BLACK);
        Font vFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.DARK_GRAY);

        PdfPCell cLabel = new PdfPCell(new Paragraph(label, lFont));
        cLabel.setBorder(Rectangle.NO_BORDER);
        cLabel.setPadding(4);

        PdfPCell cVal = new PdfPCell(new Paragraph(val, vFont));
        cVal.setBorder(Rectangle.NO_BORDER);
        cVal.setPadding(4);

        table.addCell(cLabel);
        table.addCell(cVal);
    }

    private static PdfPCell createGridCell(String text, int alignment, boolean isBold, Color textColour) {
        int weight = isBold ? Font.BOLD : Font.NORMAL;
        PdfPCell cell = new PdfPCell(new Paragraph(text,
                FontFactory.getFont(FontFactory.HELVETICA, 9, weight, textColour)));
        cell.setPadding(6);
        cell.setHorizontalAlignment(alignment);
        cell.setBorder(Rectangle.NO_BORDER); // keeps layout clean
        return cell;
    }
}
