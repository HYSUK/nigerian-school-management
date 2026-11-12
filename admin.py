"""
Web admin for the Nigerian Hybrid School Management gateway.

Mount in main.py (after load_dotenv()):
    from admin import router as admin_router
    app.include_router(admin_router)

Environment variables:
    DATABASE_URL          Supabase Postgres URI (same one main.py uses)
    ADMIN_SESSION_SECRET  random string, 32+ characters
    ADMIN_COOKIE_SECURE   "false" only for local http testing (default "true")

Uses only the standard library for hashing and sessions, so there are no new dependencies.
"""
import base64
import hashlib
import hmac
import json
import logging
import os
import secrets
import time
from contextlib import contextmanager
from datetime import date
from decimal import Decimal
from typing import List, Literal, Optional

import psycopg2
import psycopg2.errors
import psycopg2.extras
from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel, Field, field_validator, model_validator

log = logging.getLogger("hysuk.admin")
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
templates = Jinja2Templates(directory=os.path.join(BASE_DIR, "templates"))
router = APIRouter(prefix="/admin")

COOKIE_NAME = "hysuk_admin"
SESSION_TTL = 8 * 60 * 60
ROLE_AREAS = {
    "admin": {"screening", "assessments", "fees", "cards"},
    "exams": {"screening", "assessments"},
    "bursar": {"fees", "cards"},
}
Term = Literal["First", "Second", "Third"]
SESSION_RE = r"^\d{4}/\d{4}$"


# ---------------------------------------------------------------- security
def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    n, r, p = 2 ** 14, 8, 1
    key = hashlib.scrypt(password.encode(), salt=salt, n=n, r=r, p=p, dklen=32)
    return f"scrypt${n}${r}${p}${_b64(salt)}${_b64(key)}"


def verify_password(password: str, stored: str) -> bool:
    try:
        scheme, n, r, p, salt, key = stored.split("$")
        if scheme != "scrypt":
            return False
        expected = _unb64(key)
        calc = hashlib.scrypt(password.encode(), salt=_unb64(salt), n=int(n), r=int(r),
                              p=int(p), dklen=len(expected))
        return hmac.compare_digest(calc, expected)
    except Exception:
        return False


_DUMMY_HASH = hash_password("not-a-real-password")


def _secret() -> bytes:
    value = os.getenv("ADMIN_SESSION_SECRET", "")
    if len(value) < 32:
        raise HTTPException(503, "Admin is not configured: set ADMIN_SESSION_SECRET (32+ characters).")
    return value.encode()


def _sign(payload: dict) -> str:
    body = _b64(json.dumps(payload, separators=(",", ":")).encode())
    sig = _b64(hmac.new(_secret(), body.encode(), hashlib.sha256).digest())
    return f"{body}.{sig}"


def _unsign(token: str) -> Optional[dict]:
    try:
        body, sig = token.split(".")
        good = _b64(hmac.new(_secret(), body.encode(), hashlib.sha256).digest())
        if not hmac.compare_digest(sig, good):
            return None
        payload = json.loads(_unb64(body))
        return payload if payload.get("exp", 0) > time.time() else None
    except HTTPException:
        raise
    except Exception:
        return None


_FAILS: dict = {}


def _lock_keys(request: Request, username: str) -> List[str]:
    ip = request.client.host if request.client else "unknown"
    return [f"u:{username}", f"ip:{ip}"]


def _check_lock(keys: List[str]) -> None:
    now = time.time()
    for k in keys:
        _FAILS[k] = [t for t in _FAILS.get(k, []) if now - t < 900]
        if len(_FAILS[k]) >= 8:
            raise HTTPException(429, "Too many failed sign-ins. Try again in 15 minutes.")


# ---------------------------------------------------------------- database
@contextmanager
def db():
    conn = None
    try:
        dsn = os.getenv("DATABASE_URL")
        if not dsn:
            raise HTTPException(503, "Admin database is not configured (DATABASE_URL).")
        conn = psycopg2.connect(dsn)
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            yield cur
        conn.commit()
    except HTTPException:
        if conn:
            conn.rollback()
        raise
    except psycopg2.errors.UniqueViolation:
        if conn:
            conn.rollback()
        raise HTTPException(409, "That record already exists (duplicate reference or entry).")
    except psycopg2.Error:
        if conn:
            conn.rollback()
        log.exception("Admin database error")
        raise HTTPException(500, "Database error. Nothing was saved; please try again.")
    finally:
        if conn:
            conn.close()


def audit(cur, admin: dict, action: str, target: str, detail: Optional[dict] = None) -> None:
    cur.execute(
        "INSERT INTO public.cloud_admin_audit (admin, action, target, detail) VALUES (%s, %s, %s, %s)",
        (admin["username"], action, target, psycopg2.extras.Json(detail or {})),
    )


def _norm_id(value: str) -> str:
    return value.strip().replace("\\", "/")


def _get_student(cur, app_id: str) -> dict:
    cur.execute(
        """SELECT application_id, first_name, last_name, target_class
           FROM public.cloud_students_staging
           WHERE UPPER(TRIM(application_id)) = UPPER(%s)""",
        (_norm_id(app_id),),
    )
    row = cur.fetchone()
    if not row:
        raise HTTPException(404, f"Application ID '{app_id}' was not found.")
    row["application_id"] = row["application_id"].strip()
    return row


# ---------------------------------------------------------------- sessions
def _session_from(request: Request) -> Optional[dict]:
    token = request.cookies.get(COOKIE_NAME)
    sess = _unsign(token) if token else None
    if not sess:
        return None
    with db() as cur:
        cur.execute(
            "SELECT username, role FROM public.cloud_admin_users WHERE username = %s AND is_active",
            (sess["u"],),
        )
        row = cur.fetchone()
    if not row:
        return None
    return {"username": row["username"], "role": row["role"], "csrf": sess["csrf"]}


def require(*areas: str):
    def dependency(request: Request) -> dict:
        sess = _session_from(request)
        if not sess:
            raise HTTPException(401, "Your session has ended. Sign in again.")
        if not set(areas) & ROLE_AREAS.get(sess["role"], set()):
            raise HTTPException(403, "Your role cannot do this.")
        if request.method not in ("GET", "HEAD", "OPTIONS"):
            sent = request.headers.get("x-csrf-token", "")
            if not hmac.compare_digest(sent, sess["csrf"]):
                raise HTTPException(403, "Invalid request token. Reload the page.")
        return sess
    return dependency


# ---------------------------------------------------------------- pages
def _no_store(response):
    response.headers["Cache-Control"] = "no-store"
    return response


@router.get("/login", response_class=HTMLResponse, include_in_schema=False)
def login_page(request: Request):
    return _no_store(templates.TemplateResponse(request=request, name="admin_login.html", context={}))


@router.get("", response_class=HTMLResponse, include_in_schema=False)
@router.get("/", response_class=HTMLResponse, include_in_schema=False)
def admin_home(request: Request):
    sess = _session_from(request)
    if not sess:
        return RedirectResponse("/admin/login", status_code=303)
    return _no_store(templates.TemplateResponse(request=request, name="admin.html", context={
        "username": sess["username"], "role": sess["role"], "csrf": sess["csrf"],
        "areas": sorted(ROLE_AREAS[sess["role"]]),
    }))


class LoginIn(BaseModel):
    username: str = Field(min_length=1, max_length=60)
    password: str = Field(min_length=1, max_length=200)


@router.post("/api/login")
def api_login(payload: LoginIn, request: Request):
    username = payload.username.strip().lower()
    keys = _lock_keys(request, username)
    _check_lock(keys)
    with db() as cur:
        cur.execute(
            """SELECT username, password_hash, is_active FROM public.cloud_admin_users
               WHERE lower(username) = %s""", (username,))
        row = cur.fetchone()
    ok = verify_password(payload.password, row["password_hash"] if row else _DUMMY_HASH)
    if not (row and row["is_active"] and ok):
        for k in keys:
            _FAILS.setdefault(k, []).append(time.time())
        raise HTTPException(401, "Incorrect username or password.")
    for k in keys:
        _FAILS.pop(k, None)
    with db() as cur:
        cur.execute("UPDATE public.cloud_admin_users SET last_login_at = now() WHERE username = %s",
                    (row["username"],))
    token = _sign({"u": row["username"], "csrf": secrets.token_urlsafe(24),
                   "exp": int(time.time()) + SESSION_TTL})
    response = JSONResponse({"success": True, "redirect": "/admin"})
    response.set_cookie(COOKIE_NAME, token, max_age=SESSION_TTL, httponly=True, samesite="strict",
                        secure=os.getenv("ADMIN_COOKIE_SECURE", "true").lower() != "false",
                        path="/admin")
    return response


@router.post("/api/logout")
def api_logout():
    response = JSONResponse({"success": True})
    response.delete_cookie(COOKIE_NAME, path="/admin")
    return response


# ---------------------------------------------------------------- students + screening
@router.get("/api/students")
def search_students(
    q: str = "", target_class: str = "", status: str = "",
    limit: int = Query(50, ge=1, le=200), offset: int = Query(0, ge=0),
    admin: dict = Depends(require("screening", "assessments", "fees")),
):
    like = "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    with db() as cur:
        cur.execute(
            """SELECT application_id, first_name, last_name, target_class, screening_status,
                      screening_score, cutoff_mark, COUNT(*) OVER() AS total
               FROM public.cloud_students_staging
               WHERE (%(q)s = '' OR application_id ILIKE %(like)s OR first_name ILIKE %(like)s
                      OR last_name ILIKE %(like)s)
                 AND (%(cls)s = '' OR UPPER(target_class) = UPPER(%(cls)s))
                 AND (%(st)s = '' OR screening_status = %(st)s)
               ORDER BY application_id LIMIT %(limit)s OFFSET %(offset)s""",
            {"q": q.strip(), "like": like, "cls": target_class.strip(), "st": status.strip(),
             "limit": limit, "offset": offset},
        )
        rows = cur.fetchall()
    total = rows[0]["total"] if rows else 0
    for r in rows:
        r.pop("total")
        r["application_id"] = (r["application_id"] or "").strip()
    return {"success": True, "students": rows, "total": total}


class ScreeningEntry(BaseModel):
    application_id: str = Field(min_length=3, max_length=40)
    score: int = Field(ge=0, le=100)
    cutoff: Optional[int] = Field(default=None, ge=0, le=100)


class ScreeningBatch(BaseModel):
    entries: List[ScreeningEntry] = Field(min_length=1, max_length=200)


@router.post("/api/screening")
def update_screening(payload: ScreeningBatch, admin: dict = Depends(require("screening"))):
    updated, not_found = [], []
    with db() as cur:
        for e in payload.entries:
            cur.execute(
                """UPDATE public.cloud_students_staging
                   SET screening_score = %(score)s,
                       cutoff_mark = COALESCE(%(cutoff)s, cutoff_mark, 50),
                       screening_status = CASE WHEN %(score)s >= COALESCE(%(cutoff)s, cutoff_mark, 50)
                                               THEN 'Passed' ELSE 'Failed' END,
                       needs_desktop_sync = TRUE, admin_updated_at = now(), admin_updated_by = %(by)s
                   WHERE UPPER(TRIM(application_id)) = UPPER(%(app)s)
                   RETURNING application_id, screening_score, cutoff_mark, screening_status""",
                {"score": e.score, "cutoff": e.cutoff, "by": admin["username"],
                 "app": _norm_id(e.application_id)},
            )
            row = cur.fetchone()
            if row:
                row["application_id"] = row["application_id"].strip()
                updated.append(row)
                audit(cur, admin, "screening.update", row["application_id"],
                      {"score": e.score, "status": row["screening_status"]})
            else:
                not_found.append(e.application_id)
    return {"success": True, "updated": updated, "not_found": not_found}


# ---------------------------------------------------------------- assessments
class AssessmentIn(BaseModel):
    application_id: str = Field(min_length=3, max_length=40)
    session: str = Field(pattern=SESSION_RE)
    term: Term
    subject: str = Field(min_length=1, max_length=60)
    assessment_type: str = Field(min_length=1, max_length=30)
    score: Decimal = Field(ge=0, max_digits=6, decimal_places=2)
    max_score: Decimal = Field(default=Decimal(100), gt=0, max_digits=6, decimal_places=2)

    @model_validator(mode="after")
    def score_within_max(self):
        if self.score > self.max_score:
            raise ValueError("score cannot be higher than max_score")
        return self


class AssessmentBatch(BaseModel):
    entries: List[AssessmentIn] = Field(min_length=1, max_length=100)


@router.get("/api/assessments")
def list_assessments(application_id: str, session: str = Query(pattern=SESSION_RE), term: Term = "First",
                     admin: dict = Depends(require("assessments"))):
    with db() as cur:
        stu = _get_student(cur, application_id)
        cur.execute(
            """SELECT assessment_id, subject, assessment_type, score, max_score, updated_by, updated_at
               FROM public.cloud_assessments
               WHERE application_id = %s AND session = %s AND term = %s
               ORDER BY subject, assessment_type""",
            (stu["application_id"], session, term),
        )
        rows = cur.fetchall()
    return {"success": True, "student": stu, "assessments": rows}


@router.post("/api/assessments")
def save_assessments(payload: AssessmentBatch, admin: dict = Depends(require("assessments"))):
    saved = 0
    with db() as cur:
        for e in payload.entries:
            stu = _get_student(cur, e.application_id)
            cur.execute(
                """INSERT INTO public.cloud_assessments
                     (application_id, session, term, subject, assessment_type, score, max_score, updated_by)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                   ON CONFLICT (application_id, session, term, subject, assessment_type)
                   DO UPDATE SET score = EXCLUDED.score, max_score = EXCLUDED.max_score,
                                 updated_by = EXCLUDED.updated_by, updated_at = now(),
                                 needs_desktop_sync = TRUE""",
                (stu["application_id"], e.session, e.term, e.subject.strip(), e.assessment_type.strip(),
                 e.score, e.max_score, admin["username"]),
            )
            audit(cur, admin, "assessment.save", stu["application_id"],
                  {"subject": e.subject, "type": e.assessment_type, "score": float(e.score)})
            saved += 1
    return {"success": True, "saved": saved}


@router.delete("/api/assessments/{assessment_id}")
def delete_assessment(assessment_id: int, admin: dict = Depends(require("assessments"))):
    with db() as cur:
        cur.execute("DELETE FROM public.cloud_assessments WHERE assessment_id = %s RETURNING application_id",
                    (assessment_id,))
        row = cur.fetchone()
        if not row:
            raise HTTPException(404, "Assessment not found.")
        audit(cur, admin, "assessment.delete", row["application_id"], {"assessment_id": assessment_id})
    return {"success": True}


# ---------------------------------------------------------------- fees
class FeeIn(BaseModel):
    session: str = Field(pattern=SESSION_RE)
    term: Term
    class_level: str
    fee_name: str = Field(min_length=1, max_length=80)
    amount: Decimal = Field(gt=0, max_digits=12, decimal_places=2)

    @field_validator("class_level", mode="before")
    @classmethod
    def clean_class(cls, v):
        v = str(v).strip().upper().replace(" ", "")
        if v not in {"ALL", "JS", "SS", "JS1", "JS2", "JS3", "SS1", "SS2", "SS3"}:
            raise ValueError("class_level must be ALL, JS, SS or a class such as JS1")
        return v


class PaymentIn(BaseModel):
    application_id: str = Field(min_length=3, max_length=40)
    session: str = Field(pattern=SESSION_RE)
    term: Term
    fee_name: str = Field(min_length=1, max_length=80)
    amount_paid: Decimal = Field(gt=0, max_digits=12, decimal_places=2)
    method: Literal["Cash", "Bank transfer", "POS", "Online"]
    reference: Optional[str] = Field(default=None, max_length=80)
    paid_on: Optional[date] = None


@router.get("/api/fees/structure")
def list_fee_structure(session: str = Query(pattern=SESSION_RE), term: Term = "First",
                       admin: dict = Depends(require("fees"))):
    with db() as cur:
        cur.execute("""SELECT fee_id, class_level, fee_name, amount FROM public.cloud_fee_structure
                       WHERE session = %s AND term = %s ORDER BY class_level, fee_name""", (session, term))
        return {"success": True, "fees": cur.fetchall()}


@router.post("/api/fees/structure")
def save_fee(payload: FeeIn, admin: dict = Depends(require("fees"))):
    with db() as cur:
        cur.execute(
            """INSERT INTO public.cloud_fee_structure (session, term, class_level, fee_name, amount)
               VALUES (%s, %s, %s, %s, %s)
               ON CONFLICT (session, term, class_level, fee_name) DO UPDATE SET amount = EXCLUDED.amount""",
            (payload.session, payload.term, payload.class_level, payload.fee_name.strip(), payload.amount),
        )
        audit(cur, admin, "fee.save", payload.class_level,
              {"fee": payload.fee_name, "amount": float(payload.amount), "term": payload.term})
    return {"success": True}


@router.delete("/api/fees/structure/{fee_id}")
def delete_fee(fee_id: int, admin: dict = Depends(require("fees"))):
    with db() as cur:
        cur.execute("DELETE FROM public.cloud_fee_structure WHERE fee_id = %s RETURNING fee_name", (fee_id,))
        row = cur.fetchone()
        if not row:
            raise HTTPException(404, "Fee not found.")
        audit(cur, admin, "fee.delete", str(fee_id), {"fee": row["fee_name"]})
    return {"success": True}


@router.get("/api/fees/ledger")
def fee_ledger(application_id: str, session: str = Query(pattern=SESSION_RE), term: Term = "First",
               admin: dict = Depends(require("fees"))):
    with db() as cur:
        stu = _get_student(cur, application_id)
        cls = (stu["target_class"] or "").strip().upper().replace(" ", "")
        rank = {"ALL": 0, cls[:2]: 1, cls: 2}
        cur.execute("""SELECT class_level, fee_name, amount FROM public.cloud_fee_structure
                       WHERE session = %s AND term = %s AND class_level IN ('ALL', %s, %s)""",
                    (session, term, cls[:2], cls))
        owed = {}
        for f in sorted(cur.fetchall(), key=lambda f: rank[f["class_level"]]):
            owed[f["fee_name"]] = f["amount"]  # most specific class wins
        cur.execute("""SELECT payment_id, fee_name, amount_paid, method, reference, paid_on, recorded_by
                       FROM public.cloud_fee_payments
                       WHERE application_id = %s AND session = %s AND term = %s AND voided_at IS NULL
                       ORDER BY paid_on DESC, payment_id DESC""",
                    (stu["application_id"], session, term))
        payments = cur.fetchall()
    paid = {}
    for p in payments:
        paid[p["fee_name"]] = paid.get(p["fee_name"], Decimal(0)) + p["amount_paid"]
    items = [{"fee_name": n, "amount": owed.get(n, Decimal(0)), "paid": paid.get(n, Decimal(0)),
              "balance": owed.get(n, Decimal(0)) - paid.get(n, Decimal(0))}
             for n in sorted(set(owed) | set(paid))]
    totals = {k: sum((i[k] for i in items), Decimal(0)) for k in ("amount", "paid", "balance")}
    totals["balance"] = sum((max(i["balance"], Decimal(0)) for i in items), Decimal(0))
    return {"success": True, "student": stu, "items": items, "payments": payments, "totals": totals}


@router.post("/api/fees/payments")
def record_payment(payload: PaymentIn, admin: dict = Depends(require("fees"))):
    with db() as cur:
        stu = _get_student(cur, payload.application_id)
        cls = (stu["target_class"] or "").strip().upper().replace(" ", "")
        rank = {"ALL": 0, cls[:2]: 1, cls: 2}
        cur.execute("""SELECT class_level, amount FROM public.cloud_fee_structure
                       WHERE session = %s AND term = %s AND fee_name = %s
                         AND class_level IN ('ALL', %s, %s)""",
                    (payload.session, payload.term, payload.fee_name.strip(), cls[:2], cls))
        fee_rows = sorted(cur.fetchall(), key=lambda f: rank[f["class_level"]])
        if not fee_rows:
            raise HTTPException(422, f"'{payload.fee_name}' is not a fee for this student's class this term.")
        cur.execute("""SELECT COALESCE(SUM(amount_paid), 0) AS paid FROM public.cloud_fee_payments
                       WHERE application_id = %s AND session = %s AND term = %s AND fee_name = %s
                         AND voided_at IS NULL""",
                    (stu["application_id"], payload.session, payload.term, payload.fee_name.strip()))
        left = fee_rows[-1]["amount"] - cur.fetchone()["paid"]
        if payload.amount_paid > left:
            raise HTTPException(422, f"That is more than the balance for {payload.fee_name} "
                                     f"(NGN {max(left, 0):,.2f} left). Check that you chose the right fee.")
        cur.execute(
            """INSERT INTO public.cloud_fee_payments
                 (application_id, session, term, fee_name, amount_paid, method, reference, paid_on, recorded_by)
               VALUES (%s, %s, %s, %s, %s, %s, %s, COALESCE(%s, current_date), %s)
               RETURNING payment_id""",
            (stu["application_id"], payload.session, payload.term, payload.fee_name.strip(),
             payload.amount_paid, payload.method, (payload.reference or "").strip() or None,
             payload.paid_on, admin["username"]),
        )
        payment_id = cur.fetchone()["payment_id"]
        audit(cur, admin, "payment.record", stu["application_id"],
              {"payment_id": payment_id, "fee": payload.fee_name, "amount": float(payload.amount_paid)})
    return {"success": True, "payment_id": payment_id}


@router.delete("/api/fees/payments/{payment_id}")
def void_payment(payment_id: int, admin: dict = Depends(require("fees"))):
    with db() as cur:
        cur.execute(
            """UPDATE public.cloud_fee_payments SET voided_at = now(), voided_by = %s,
                      needs_desktop_sync = TRUE
               WHERE payment_id = %s AND voided_at IS NULL RETURNING application_id, amount_paid""",
            (admin["username"], payment_id),
        )
        row = cur.fetchone()
        if not row:
            raise HTTPException(404, "Payment not found or already voided.")
        audit(cur, admin, "payment.void", row["application_id"],
              {"payment_id": payment_id, "amount": float(row["amount_paid"])})
    return {"success": True}
