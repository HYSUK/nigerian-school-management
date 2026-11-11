"""
Payments overview, "who still owes" list (with CSV) and PDF receipts.
Uses the fee structure and payment records you already have; needs no new tables.

Mount in main.py:
    from admin_payments import router as payments_router
    app.include_router(payments_router)
"""
import csv
import io
import os
from decimal import Decimal
from typing import Literal, Optional
from xml.sax.saxutils import escape

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import Response
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A5
from reportlab.lib.styles import ParagraphStyle
from reportlab.platypus import HRFlowable, Image, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

from admin import db, require
from admission_letter import GREEN, GREY, LOGO_PATH, NAVY, SCHOOL_LINES, SCHOOL_NAME, _fit

router = APIRouter(prefix="/admin/api/fees")
SESSION_RE = r"^\d{4}/\d{4}$"
Term = Literal["First", "Second", "Third"]

# Every admitted student, what they are charged this term (the most specific fee wins:
# class > JS/SS > ALL), and what they have paid (voided payments ignored).
LEDGER_SQL = """
WITH students AS (
  SELECT TRIM(application_id) AS application_id,
         TRIM(COALESCE(first_name, '') || ' ' || COALESCE(last_name, '')) AS name,
         UPPER(REPLACE(COALESCE(target_class, ''), ' ', '')) AS klass
  FROM public.cloud_students_staging
  WHERE screening_status ILIKE ANY (ARRAY['passed%%', 'approved%%', 'imported%%'])
), fees AS (
  SELECT DISTINCT ON (s.application_id, f.fee_name) s.application_id, f.amount
  FROM students s JOIN public.cloud_fee_structure f
    ON f.session = %(session)s AND f.term = %(term)s
   AND f.class_level IN ('ALL', LEFT(s.klass, 2), s.klass)
  ORDER BY s.application_id, f.fee_name,
           CASE WHEN f.class_level = 'ALL' THEN 0 WHEN LENGTH(f.class_level) = 2 THEN 1 ELSE 2 END DESC
), owed AS (
  SELECT application_id, SUM(amount) AS owed FROM fees GROUP BY application_id
), paid AS (
  SELECT application_id, SUM(amount_paid) AS paid FROM public.cloud_fee_payments
  WHERE session = %(session)s AND term = %(term)s AND voided_at IS NULL GROUP BY application_id
)
SELECT s.application_id, s.name, s.klass AS class, o.owed, COALESCE(p.paid, 0) AS paid,
       o.owed - COALESCE(p.paid, 0) AS balance
FROM students s JOIN owed o USING (application_id) LEFT JOIN paid p USING (application_id)
ORDER BY s.klass, s.name
"""


def _ledger(cur, session: str, term: str) -> list:
    cur.execute(LEDGER_SQL, {"session": session, "term": term})
    return cur.fetchall()


def _debtors(cur, session: str, term: str, klass: str) -> list:
    klass = klass.strip().upper()
    return [r for r in _ledger(cur, session, term) if r["balance"] > 0 and (not klass or r["class"] == klass)]


@router.get("/overview")
def overview(session: str = Query(pattern=SESSION_RE), term: Term = "First",
             admin: dict = Depends(require("fees"))):
    where = "WHERE session = %s AND term = %s AND voided_at IS NULL"
    with db() as cur:
        cur.execute(f"""SELECT method, COUNT(*) AS n, SUM(amount_paid) AS total
                        FROM public.cloud_fee_payments {where} GROUP BY method ORDER BY total DESC""",
                    (session, term))
        by_method = cur.fetchall()
        cur.execute(f"""SELECT paid_on, COUNT(*) AS n, SUM(amount_paid) AS total
                        FROM public.cloud_fee_payments {where} GROUP BY paid_on
                        ORDER BY paid_on DESC LIMIT 31""", (session, term))
        by_day = cur.fetchall()
        rows = _ledger(cur, session, term)
        cur.execute("SELECT COALESCE(SUM(price), 0) AS total FROM public.cloud_card_sales WHERE voided_at IS NULL")
        card_sales = cur.fetchone()["total"]
    return {
        "success": True,
        "collected": sum((m["total"] for m in by_method), Decimal(0)),
        "expected": sum((r["owed"] for r in rows), Decimal(0)),
        "outstanding": sum((r["balance"] for r in rows if r["balance"] > 0), Decimal(0)),
        "card_sales": card_sales,
        "by_method": by_method,
        "by_day": by_day,
    }


@router.get("/debtors")
def debtors(session: str = Query(pattern=SESSION_RE), term: Term = "First", target_class: str = "",
            admin: dict = Depends(require("fees"))):
    with db() as cur:
        rows = _debtors(cur, session, term, target_class)
    return {"success": True, "debtors": rows}


@router.get("/debtors.csv")
def debtors_csv(session: str = Query(pattern=SESSION_RE), term: Term = "First", target_class: str = "",
                admin: dict = Depends(require("fees"))):
    with db() as cur:
        rows = _debtors(cur, session, term, target_class)
    out = io.StringIO()
    writer = csv.writer(out)
    writer.writerow(["Application ID", "Name", "Class", "Charged", "Paid", "Balance"])
    for r in rows:
        writer.writerow([r["application_id"], r["name"], r["class"], r["owed"], r["paid"], r["balance"]])
    name = f"debtors-{session.replace('/', '-')}-{term}.csv"
    return Response(out.getvalue(), media_type="text/csv",
                    headers={"Content-Disposition": f'attachment; filename="{name}"'})


# ---------------------------------------------------------------- receipt
def _money(value) -> str:
    return f"NGN {Decimal(value):,.2f}"  # the standard PDF fonts have no naira sign


def build_receipt(r: dict, balance: Optional[Decimal]) -> bytes:
    number = f"RCP-{r['recorded_at'].year}-{r['payment_id']:06d}"
    paid_on = r["paid_on"]
    label = ParagraphStyle("l", fontName="Helvetica", fontSize=9, textColor=GREY, leading=12)
    value = ParagraphStyle("v", fontName="Helvetica-Bold", fontSize=10.5, leading=13)
    rows = [
        ("Receipt No.", number),
        ("Date", f"{paid_on:%B} {paid_on.day}, {paid_on:%Y}"),
        ("Received from", r["name"] or r["application_id"]),
        ("Application ID", r["application_id"]),
        ("Class", (r["target_class"] or "").strip().upper() or "-"),
        ("Session / Term", f"{r['session']}, {r['term']} term"),
        ("Payment for", r["fee_name"]),
        ("Method", r["method"] + (f" (Ref: {r['reference']})" if r["reference"] else "")),
    ]
    table = Table([[Paragraph(escape(a), label), Paragraph(escape(str(b)), value)] for a, b in rows],
                  colWidths=[95, 230])
    table.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "TOP"), ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
                               ("LINEBELOW", (0, 0), (-1, -1), 0.3, colors.HexColor("#dddddd"))]))
    amount = Table([[Paragraph("AMOUNT PAID", label),
                     Paragraph(_money(r["amount_paid"]), ParagraphStyle("a", fontName="Helvetica-Bold",
                                                                         fontSize=16, textColor=GREEN, leading=20))]],
                   colWidths=[95, 230])
    amount.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#f1f7f1")),
                                ("TOPPADDING", (0, 0), (-1, -1), 8), ("BOTTOMPADDING", (0, 0), (-1, -1), 8)]))

    story = []
    if os.path.exists(LOGO_PATH):
        w, h = _fit(LOGO_PATH, 52)
        story += [Image(LOGO_PATH, width=w, height=h), Spacer(1, 6)]
    story.append(Paragraph(escape(SCHOOL_NAME), ParagraphStyle("t", fontName="Helvetica-Bold", fontSize=13,
                                                               textColor=NAVY, alignment=TA_CENTER, leading=16)))
    sub = ParagraphStyle("s", fontName="Helvetica", fontSize=8.5, textColor=GREY, alignment=TA_CENTER, leading=11)
    story += [Paragraph(escape(line), sub) for line in SCHOOL_LINES]
    story += [Spacer(1, 8), HRFlowable(width="100%", thickness=0.6, color=colors.HexColor("#cccccc")), Spacer(1, 8),
              Paragraph("<u>OFFICIAL PAYMENT RECEIPT</u>", ParagraphStyle("h", fontName="Helvetica-Bold", fontSize=11.5,
                                                                         textColor=GREEN, alignment=TA_CENTER)),
              Spacer(1, 10), table, Spacer(1, 8), amount]
    if balance is not None:
        story += [Spacer(1, 6), Paragraph(f"Balance still owing for this term: <b>{_money(max(balance, 0))}</b>",
                                          ParagraphStyle("b", fontName="Helvetica", fontSize=9.5, leading=13))]
    story += [Spacer(1, 22), HRFlowable(width=140, thickness=0.8, color=colors.black, hAlign="LEFT", spaceAfter=3),
              Paragraph(f"Received by: {escape(r['recorded_by'] or 'School office')}",
                        ParagraphStyle("f", fontName="Helvetica", fontSize=9, leading=12))]

    def border(canvas, doc):
        width, height = A5
        canvas.saveState()
        canvas.setStrokeColor(NAVY)
        canvas.setLineWidth(1.2)
        canvas.rect(18, 18, width - 36, height - 36)
        canvas.restoreState()

    buf = io.BytesIO()
    SimpleDocTemplate(buf, pagesize=A5, leftMargin=36, rightMargin=36, topMargin=34, bottomMargin=30,
                      title=f"Receipt {number}", author=SCHOOL_NAME).build(story, onFirstPage=border)
    return buf.getvalue()


@router.get("/payments/{payment_id}/receipt")
def payment_receipt(payment_id: int, admin: dict = Depends(require("fees"))):
    with db() as cur:
        cur.execute(
            """SELECT p.payment_id, p.application_id, p.session, p.term, p.fee_name, p.amount_paid, p.method,
                      p.reference, p.paid_on, p.recorded_by, p.recorded_at, p.voided_at,
                      TRIM(COALESCE(s.first_name, '') || ' ' || COALESCE(s.last_name, '')) AS name, s.target_class
               FROM public.cloud_fee_payments p
               LEFT JOIN public.cloud_students_staging s ON UPPER(TRIM(s.application_id)) = UPPER(p.application_id)
               WHERE p.payment_id = %s""", (payment_id,))
        r = cur.fetchone()
        if not r:
            raise HTTPException(404, "Payment not found.")
        if r["voided_at"]:
            raise HTTPException(409, "This payment was voided, so no receipt can be printed.")
        mine = [x for x in _ledger(cur, r["session"], r["term"]) if x["application_id"] == r["application_id"].strip()]
    pdf = build_receipt(r, mine[0]["balance"] if mine else None)
    return Response(pdf, media_type="application/pdf", headers={
        "Content-Disposition": f'inline; filename="RECEIPT-{payment_id:06d}.pdf"', "Cache-Control": "no-store"})
