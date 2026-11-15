"""
"My Fees" for parents: what is charged, what is paid, and printable receipts.
Everything is read from the signed-in student's cookie, so a student can only ever see their own account.

Mount in main.py:
    from student_fees import router as fees_router
    app.include_router(fees_router)
"""
import os

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse, Response
from fastapi.templating import Jinja2Templates

from admin import db
from admin_payments import _ledger, build_receipt
from student_portal import _clean, _load_student, _no_store, _student_id, require_student

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
templates = Jinja2Templates(directory=os.path.join(BASE_DIR, "templates"))
router = APIRouter()


@router.get("/fees", response_class=HTMLResponse, include_in_schema=False)
def fees_page(request: Request):
    if not _student_id(request):
        return RedirectResponse("/apply", status_code=303)
    return _no_store(templates.TemplateResponse(request=request, name="fees.html", context={}))


@router.get("/api/v1/student/me/fees")
def my_fees(request: Request, session: str = "", term: str = ""):
    sid = require_student(request)
    with db() as cur:
        me = _load_student(cur, sid)
        if not me:
            raise HTTPException(404, "Student not found.")
        klass = _clean(me["target_class"]).upper().replace(" ", "")

        # Terms that have fees for this student's class, newest first
        cur.execute(
            """SELECT f.session, f.term FROM public.cloud_fee_structure f
               WHERE f.class_level IN ('ALL', %s, %s)
               GROUP BY f.session, f.term
               ORDER BY f.session DESC, CASE f.term WHEN 'Third' THEN 3 WHEN 'Second' THEN 2 ELSE 1 END DESC""",
            (klass[:2], klass),
        )
        periods = cur.fetchall()
        name = f"{_clean(me['first_name'])} {_clean(me['last_name'])}".strip()
        if not periods:
            return {"success": True, "name": name, "class": klass, "periods": [], "items": [], "payments": []}

        chosen = next((p for p in periods if p["session"] == session and p["term"] == term), periods[0])
        s, t = chosen["session"], chosen["term"]

        cur.execute(
            """SELECT f.fee_name, f.amount, COALESCE(p.paid, 0) AS paid
               FROM (SELECT DISTINCT ON (fee_name) fee_name, amount FROM public.cloud_fee_structure
                     WHERE session = %s AND term = %s AND class_level IN ('ALL', %s, %s)
                     ORDER BY fee_name,
                              CASE WHEN class_level = 'ALL' THEN 0 WHEN LENGTH(class_level) = 2 THEN 1 ELSE 2 END DESC) f
               LEFT JOIN (SELECT fee_name, SUM(amount_paid) AS paid FROM public.cloud_fee_payments
                          WHERE UPPER(TRIM(application_id)) = UPPER(%s) AND session = %s AND term = %s
                            AND voided_at IS NULL
                          GROUP BY fee_name) p ON p.fee_name = f.fee_name
               ORDER BY f.fee_name""",
            (s, t, klass[:2], klass, sid, s, t),
        )
        items = [{"fee_name": r["fee_name"], "charged": r["amount"], "paid": r["paid"],
                  "balance": max(r["amount"] - r["paid"], 0)} for r in cur.fetchall()]

        cur.execute(
            """SELECT payment_id, fee_name, amount_paid, method, reference, paid_on
               FROM public.cloud_fee_payments
               WHERE UPPER(TRIM(application_id)) = UPPER(%s) AND session = %s AND term = %s AND voided_at IS NULL
               ORDER BY paid_on DESC, payment_id DESC""",
            (sid, s, t),
        )
        payments = cur.fetchall()
    return {"success": True, "name": name, "class": klass, "session": s, "term": t,
            "periods": periods, "items": items, "payments": payments}


@router.get("/api/v1/student/me/payments/{payment_id}/receipt")
def my_receipt(payment_id: int, request: Request):
    sid = require_student(request)
    with db() as cur:
        cur.execute(
            """SELECT p.payment_id, p.application_id, p.session, p.term, p.fee_name, p.amount_paid, p.method,
                      p.reference, p.paid_on, p.recorded_by, p.recorded_at,
                      TRIM(COALESCE(s.first_name, '') || ' ' || COALESCE(s.last_name, '')) AS name, s.target_class
               FROM public.cloud_fee_payments p
               LEFT JOIN public.cloud_students_staging s ON UPPER(TRIM(s.application_id)) = UPPER(TRIM(p.application_id))
               WHERE p.payment_id = %s AND UPPER(TRIM(p.application_id)) = UPPER(%s) AND p.voided_at IS NULL""",
            (payment_id, sid),
        )
        r = cur.fetchone()
        if not r:  # same answer whether it does not exist or belongs to someone else
            raise HTTPException(404, "Receipt not found.")
        mine = [x for x in _ledger(cur, r["session"], r["term"]) if x["application_id"] == r["application_id"].strip()]
    pdf = build_receipt(r, mine[0]["balance"] if mine else None)
    return Response(pdf, media_type="application/pdf", headers={
        "Content-Disposition": f'inline; filename="RECEIPT-{payment_id:06d}.pdf"', "Cache-Control": "no-store"})
