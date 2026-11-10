"""
Admission letter PDF for students who passed screening.

Mount in main.py, next to the admin router:
    from admission_letter import router as letter_router
    app.include_router(letter_router)

Needs one new package:  pip install reportlab
Files used (both optional, the letter still builds without them):
    static/images/logo.png        header logo and faint centre watermark
    static/images/signature.png   signature image above the registrar line
"""
import io
import os
import re
from datetime import datetime
from xml.sax.saxutils import escape

import psycopg2
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import Response
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.utils import ImageReader
from reportlab.platypus import HRFlowable, Image, Paragraph, SimpleDocTemplate, Spacer

from student_portal import require_student

router = APIRouter()
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
LOGO_PATH = os.path.join(BASE_DIR, "static", "images", "logo.png")
SIGNATURE_PATH = os.path.join(BASE_DIR, "static", "images", "signature.png")

# ---- Edit the wording here -------------------------------------------------
SCHOOL_NAME = "NIGERIA SECONDARY SCHOOL SYSTEM"
SCHOOL_LINES = [
    "Official Portal Operations Hub, Registry Department",
    "Email: registry@nigeriaschools.edu.ng",
]
SIGN_OFF = ["Office of the Registrar Board", "Nigeria Secondary School Network Operations"]
BODY = [
    "We are pleased to inform you that you have been offered provisional admission under the "
    "reference registration index: {app_id}. Your placement has been processed successfully "
    "into class {klass}.",
    "Please ensure you generate your official bursary payment receipt voucher from your "
    "dashboard to complete tuition registration. Terminal grade record printing remains "
    "locked until all school fee entries are cleared.",
]
# ----------------------------------------------------------------------------

NAVY = colors.HexColor("#1F3B5C")
GREEN = colors.HexColor("#2E7D32")
GREY = colors.HexColor("#555555")

S_TITLE = ParagraphStyle("title", fontName="Helvetica-Bold", fontSize=17, leading=21,
                         alignment=TA_CENTER, textColor=NAVY)
S_SUB = ParagraphStyle("sub", fontName="Helvetica", fontSize=9.5, leading=13,
                       alignment=TA_CENTER, textColor=GREY)
S_BODY = ParagraphStyle("body", fontName="Helvetica", fontSize=11, leading=16, spaceAfter=10)
S_BOLD = ParagraphStyle("bold", parent=S_BODY, fontName="Helvetica-Bold")
S_HEAD = ParagraphStyle("head", fontName="Helvetica-Bold", fontSize=12.5, leading=16,
                        alignment=TA_CENTER, textColor=GREEN)


def _lagos_today() -> str:
    try:
        from zoneinfo import ZoneInfo
        now = datetime.now(ZoneInfo("Africa/Lagos"))
    except Exception:
        now = datetime.now()
    return f"{now:%B} {now.day}, {now:%Y}"


def _fit(path: str, width: float):
    w, h = ImageReader(path).getSize()
    return width, width * h / w


def _decorate(canvas, doc):
    """Page border and the faint logo watermark."""
    page_w, page_h = A4
    canvas.saveState()
    canvas.setStrokeColor(NAVY)
    canvas.setLineWidth(1.5)
    canvas.rect(28, 28, page_w - 56, page_h - 56)
    if os.path.exists(LOGO_PATH):
        w, h = _fit(LOGO_PATH, 330)
        canvas.setFillAlpha(0.08)
        canvas.drawImage(LOGO_PATH, (page_w - w) / 2, page_h * 0.30, w, h, mask="auto")
    canvas.restoreState()


def build_letter_pdf(full_name: str, klass: str, app_id: str, date_text: str) -> bytes:
    buf = io.BytesIO()
    doc = SimpleDocTemplate(buf, pagesize=A4, leftMargin=52, rightMargin=52, topMargin=58,
                            bottomMargin=50, title=f"Admission Letter {app_id}", author=SCHOOL_NAME)
    story = []
    if os.path.exists(LOGO_PATH):
        w, h = _fit(LOGO_PATH, 78)
        story += [Image(LOGO_PATH, width=w, height=h), Spacer(1, 10)]
    story.append(Paragraph(escape(SCHOOL_NAME), S_TITLE))
    story += [Paragraph(escape(line), S_SUB) for line in SCHOOL_LINES]
    story += [Spacer(1, 12), HRFlowable(width="100%", thickness=0.6, color=colors.HexColor("#cccccc")),
              Spacer(1, 14)]
    story.append(Paragraph(f"Date: {escape(date_text)}", S_BODY))
    story.append(Spacer(1, 6))
    story.append(Paragraph(f"Dear {escape(full_name)},", S_BOLD))
    story.append(Spacer(1, 8))
    story.append(Paragraph(f"<u>OFFER OF PROVISIONAL ADMISSION INTO {escape(klass)}</u>", S_HEAD))
    story.append(Spacer(1, 14))
    for text in BODY:
        story.append(Paragraph(text.format(app_id=f"<b>{escape(app_id)}</b>", klass=f"<b>{escape(klass)}</b>"), S_BODY))
    story += [Spacer(1, 26), Paragraph("Yours faithfully,", S_BODY)]
    if os.path.exists(SIGNATURE_PATH):
        w, h = _fit(SIGNATURE_PATH, 110)
        sig = Image(SIGNATURE_PATH, width=w, height=h)
        sig.hAlign = "LEFT"
        story.append(sig)
    else:
        story.append(Spacer(1, 40))
    story.append(HRFlowable(width=150, thickness=0.8, color=colors.black, hAlign="LEFT", spaceAfter=4))
    story += [Paragraph(escape(line), ParagraphStyle("so", parent=S_BODY, spaceAfter=1)) for line in SIGN_OFF]
    doc.build(story, onFirstPage=_decorate, onLaterPages=_decorate)
    return buf.getvalue()


@router.get("/api/v1/admission-letter/{app_id:path}")
def admission_letter(app_id: str, request: Request):
    clean_id = app_id.strip().replace("\\", "/")
    me = require_student(request)  # must be signed in
    if clean_id.upper() != me.upper():
        raise HTTPException(403, "You can only open your own admission letter.")
    dsn = os.getenv("DATABASE_URL")
    if not dsn:
        raise HTTPException(503, "The portal database is not configured.")
    conn = None
    try:
        conn = psycopg2.connect(dsn)
        with conn.cursor() as cur:
            cur.execute(
                """SELECT application_id, first_name, last_name, target_class, screening_status
                   FROM public.cloud_students_staging
                   WHERE UPPER(TRIM(application_id)) = UPPER(%s)""",
                (clean_id,),
            )
            row = cur.fetchone()
    except psycopg2.Error:
        raise HTTPException(500, "Could not load the student record. Please try again.")
    finally:
        if conn:
            conn.close()

    if not row:
        raise HTTPException(404, "Student not found.")
    stored_id, first, last, klass, status = row
    if not any(w in (status or "").lower() for w in ("passed", "approved", "imported")):
        raise HTTPException(403, "The admission letter is only available after you pass screening.")

    stored_id = stored_id.strip()
    name = f"{(first or '').strip()} {(last or '').strip()}".strip().upper()
    pdf = build_letter_pdf(name, (klass or "").strip().upper(), stored_id.upper(), _lagos_today())
    safe = re.sub(r"[^A-Za-z0-9_-]", "-", stored_id)
    return Response(pdf, media_type="application/pdf", headers={
        "Content-Disposition": f'inline; filename="ADMISSION-LETTER-{safe}.pdf"',
        "Cache-Control": "no-store",
    })
