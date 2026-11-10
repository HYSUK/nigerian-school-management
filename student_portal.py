"""
Student-facing security for the portal.

* Login issues a signed, HttpOnly cookie. Every student page and API reads the
  student's identity from that cookie, never from the address bar.
* "Forgot Application ID or password" recovery: card serial + PIN, plus the date
  of birth if registration was completed. A NEW password is issued and shown once.
* Registration, dashboard and registration pages live here so they can all check the cookie.
* A staff password reset for the admin (for parents who lost their card).

Mount in main.py:
    from student_portal import router as student_router
    app.include_router(student_router)
"""
import hashlib
import hmac
import json
import os
import secrets
import time
from datetime import date, datetime
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel, ConfigDict, Field

from admin import _b64, _secret, _unb64, audit, db, require
from admin_cards import guard_check, guard_clear, guard_fail

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
templates = Jinja2Templates(directory=os.path.join(BASE_DIR, "templates"))
router = APIRouter()

COOKIE = "hysuk_student"
SESSION_TTL = 3 * 60 * 60
PLACEHOLDER_PHOTO = ("https://images.unsplash.com/photo-1534528741775-53994a69daeb"
                     "?q=80&w=256&auto=format&fit=crop")
RECOVER_FAILED = ("We could not verify those details. Check the card serial number and PIN, and enter "
                  "the date of birth exactly as it was submitted if you completed registration.")


# ---------------------------------------------------------------- session cookie
def _key() -> bytes:
    # Derived from the same secret as the admin login, but a different key,
    # so a student cookie can never be accepted as an admin cookie.
    return hmac.new(_secret(), b"student-session-v1", hashlib.sha256).digest()


def _sign(app_id: str) -> str:
    body = _b64(json.dumps({"sid": app_id, "exp": int(time.time()) + SESSION_TTL},
                           separators=(",", ":")).encode())
    return f"{body}.{_b64(hmac.new(_key(), body.encode(), hashlib.sha256).digest())}"


def _student_id(request: Request) -> Optional[str]:
    token = request.cookies.get(COOKIE)
    if not token:
        return None
    try:
        body, sig = token.split(".")
        if not hmac.compare_digest(sig, _b64(hmac.new(_key(), body.encode(), hashlib.sha256).digest())):
            return None
        data = json.loads(_unb64(body))
        return data["sid"] if data.get("exp", 0) > time.time() else None
    except HTTPException:
        raise
    except Exception:
        return None


def require_student(request: Request) -> str:
    """Return the signed-in student's application ID, or raise 401."""
    sid = _student_id(request)
    if not sid:
        raise HTTPException(401, "Please sign in again.")
    return sid


def _no_store(response):
    response.headers["Cache-Control"] = "no-store"
    return response


# ---------------------------------------------------------------- helpers
def _load_student(cur, app_id: str) -> Optional[dict]:
    cur.execute(
        """SELECT application_id, first_name, last_name, target_class, screening_status, screening_score,
                  cutoff_mark, passport_base64, gender, date_of_birth, state_of_origin, lga, home_address,
                  guardian_name, guardian_relationship, guardian_phone, prev_primary_school,
                  primary_from_year, primary_to_year, prev_junior_sec_school, junior_sec_from_year,
                  junior_sec_to_year
           FROM public.cloud_students_staging WHERE UPPER(TRIM(application_id)) = UPPER(%s)""",
        (app_id,),
    )
    return cur.fetchone()


def _is_pending(row: dict) -> bool:
    name = (row.get("first_name") or "").strip().upper()
    return not name or name == "PENDING"


def _as_date(value) -> Optional[date]:
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    try:
        return datetime.strptime(str(value).strip()[:10], "%Y-%m-%d").date()
    except ValueError:
        return None


def _clean(value) -> str:
    return str(value).strip() if value is not None else ""


def _new_password() -> str:
    return "PWD-" + secrets.token_hex(3).upper()


# ---------------------------------------------------------------- login / logout
class LoginIn(BaseModel):
    username: str = Field(min_length=1, max_length=60)
    password: str = Field(min_length=1, max_length=100)


@router.post("/api/v1/auth/login")
def student_login(payload: LoginIn, request: Request):
    username = payload.username.strip().replace("\\", "/")
    guard_check(request, username)  # 5 wrong tries per ID, 20 per address, 15 minutes
    with db() as cur:
        cur.execute(
            """SELECT application_id, student_password, first_name, last_name, target_class
               FROM public.cloud_students_staging WHERE UPPER(TRIM(application_id)) = UPPER(%s)""",
            (username,),
        )
        row = cur.fetchone()
    stored = ((row or {}).get("student_password") or "").strip().upper().encode()
    given = payload.password.strip().upper().encode()
    if not (row and stored and hmac.compare_digest(stored, given)):
        guard_fail(request, username)
        raise HTTPException(401, "Invalid Application ID or Password.")
    guard_clear(request, username)

    app_id = row["application_id"].strip()
    response = JSONResponse({
        "success": True,
        "application_id": app_id,
        "first_name": _clean(row["first_name"]),
        "last_name": _clean(row["last_name"]),
        "target_class": _clean(row["target_class"]) or "N/A",
        "is_profile_pending": _is_pending(row),
    })
    response.set_cookie(COOKIE, _sign(app_id), max_age=SESSION_TTL, httponly=True, samesite="lax",
                        secure=os.getenv("ADMIN_COOKIE_SECURE", "true").lower() != "false", path="/")
    return response


@router.post("/api/v1/auth/logout")
def student_logout():
    response = JSONResponse({"success": True})
    response.delete_cookie(COOKIE, path="/")
    return response


# ---------------------------------------------------------------- own details
@router.get("/api/v1/student/me")
def my_details(request: Request):
    sid = require_student(request)
    with db() as cur:
        r = _load_student(cur, sid)
    if not r:
        raise HTTPException(404, "Student not found.")
    dob = _as_date(r["date_of_birth"]) if r["date_of_birth"] else None
    return _no_store(JSONResponse({
        "success": True,
        "application_id": _clean(r["application_id"]),
        "first_name": _clean(r["first_name"]),
        "last_name": _clean(r["last_name"]),
        "current_class": _clean(r["target_class"]),
        "target_class": _clean(r["target_class"]),
        "screening_status": _clean(r["screening_status"]) or "Pending",
        "screening_score": r["screening_score"] if r["screening_score"] is not None else 0,
        "cutoff_mark": r["cutoff_mark"],
        "passport_base64": r["passport_base64"] or "",
        "gender": _clean(r["gender"]),
        "date_of_birth": dob.isoformat() if dob else "",
        "state_of_origin": _clean(r["state_of_origin"]),
        "lga": _clean(r["lga"]),
        "home_address": _clean(r["home_address"]),
        "guardian_name": _clean(r["guardian_name"]),
        "guardian_relationship": _clean(r["guardian_relationship"]),
        "guardian_phone": _clean(r["guardian_phone"]),
        "prev_primary_school": _clean(r["prev_primary_school"]),
        "primary_from_year": r["primary_from_year"],
        "primary_to_year": r["primary_to_year"],
        "prev_junior_sec_school": _clean(r["prev_junior_sec_school"]),
        "junior_sec_from_year": r["junior_sec_from_year"],
        "junior_sec_to_year": r["junior_sec_to_year"],
    }))


# ---------------------------------------------------------------- registration
class ProfileIn(BaseModel):
    model_config = ConfigDict(extra="ignore")  # an old page may still send application_id; it is ignored
    first_name: str = Field(min_length=1, max_length=50)
    last_name: str = Field(min_length=1, max_length=50)
    gender: str = Field(min_length=1, max_length=10)
    date_of_birth: str = Field(min_length=8, max_length=10)
    state_of_origin: str = Field(min_length=1, max_length=50)
    lga: str = Field(min_length=1, max_length=50)
    home_address: Optional[str] = Field(default=None, max_length=300)
    guardian_name: Optional[str] = Field(default=None, max_length=100)
    guardian_relationship: Optional[str] = Field(default=None, max_length=30)
    guardian_phone: str = Field(min_length=7, max_length=15)
    prev_primary_school: Optional[str] = Field(default=None, max_length=120)
    primary_from_year: Optional[int] = Field(default=None, ge=1950, le=2100)
    primary_to_year: Optional[int] = Field(default=None, ge=1950, le=2100)
    prev_junior_sec_school: Optional[str] = Field(default=None, max_length=120)
    junior_sec_from_year: Optional[int] = Field(default=None, ge=1950, le=2100)
    junior_sec_to_year: Optional[int] = Field(default=None, ge=1950, le=2100)
    passport_base64: Optional[str] = Field(default=None, max_length=1_500_000)


@router.post("/api/v1/register")
def complete_profile(payload: ProfileIn, request: Request):
    sid = require_student(request)  # the ID comes from the cookie, never from the request body
    try:
        dob = datetime.strptime(payload.date_of_birth.strip(), "%Y-%m-%d").date()
    except ValueError:
        raise HTTPException(422, "Enter the date of birth as a valid date.")
    if dob >= date.today() or dob.year < 1990:
        raise HTTPException(422, "Check the date of birth. It does not look right.")

    with db() as cur:
        current = _load_student(cur, sid)
        if not current:
            raise HTTPException(404, "Student application profile not found.")
        if not _is_pending(current):
            raise HTTPException(409, "Your registration is already complete. "
                                     "Contact the school office if something must be changed.")
        cur.execute(
            """UPDATE public.cloud_students_staging
               SET first_name = %s, last_name = %s, gender = %s, date_of_birth = %s, state_of_origin = %s,
                   lga = %s, home_address = %s, guardian_name = %s, guardian_relationship = %s,
                   guardian_phone = %s, prev_primary_school = %s, primary_from_year = %s,
                   primary_to_year = %s, prev_junior_sec_school = %s, junior_sec_from_year = %s,
                   junior_sec_to_year = %s, passport_base64 = %s, sync_status = 'PENDING_IMPORT'
               WHERE UPPER(TRIM(application_id)) = UPPER(%s)""",
            (payload.first_name.strip(), payload.last_name.strip(), payload.gender.strip(), dob,
             payload.state_of_origin.strip(), payload.lga.strip(), payload.home_address,
             payload.guardian_name, payload.guardian_relationship or "Parent",
             payload.guardian_phone.strip(), payload.prev_primary_school, payload.primary_from_year,
             payload.primary_to_year, payload.prev_junior_sec_school, payload.junior_sec_from_year,
             payload.junior_sec_to_year, payload.passport_base64, sid),
        )
    return {"success": True, "message": "Profile saved successfully."}


# ---------------------------------------------------------------- pages
@router.get("/register", response_class=HTMLResponse, include_in_schema=False)
def register_page(request: Request):
    sid = _student_id(request)
    if not sid:
        return RedirectResponse("/apply", status_code=303)
    with db() as cur:
        row = _load_student(cur, sid)
    if row and not _is_pending(row):
        return RedirectResponse("/dashboard", status_code=303)
    return _no_store(templates.TemplateResponse(request=request, name="register.html", context={}))


@router.get("/dashboard", response_class=HTMLResponse, include_in_schema=False)
def dashboard_page(request: Request):
    sid = _student_id(request)  # any ?app_id= in the address is ignored
    if not sid:
        return RedirectResponse("/apply", status_code=303)
    with db() as cur:
        r = _load_student(cur, sid)
    if not r:
        return RedirectResponse("/apply", status_code=303)
    if _is_pending(r):
        return RedirectResponse("/register", status_code=303)

    photo = (r["passport_base64"] or "").strip()
    if photo and not photo.startswith("data:"):
        photo = f"data:image/jpeg;base64,{photo}"
    return _no_store(templates.TemplateResponse(request=request, name="dashboard.html", context={
        "application_id": _clean(r["application_id"]),
        "first_name": _clean(r["first_name"]) or "Student",
        "last_name": _clean(r["last_name"]),
        "current_class": _clean(r["target_class"]) or "N/A",
        "target_class": _clean(r["target_class"]) or "N/A",
        "admission_status": _clean(r["screening_status"]) or "Awaiting Academic Screening",
        "screening_score": str(r["screening_score"]) if r["screening_score"] is not None else "N/A",
        "passport_url": photo or PLACEHOLDER_PHOTO,
    }))


# ---------------------------------------------------------------- recovery
class RecoverIn(BaseModel):
    serial: str = Field(min_length=3, max_length=30)
    pin: str = Field(min_length=3, max_length=30)
    date_of_birth: Optional[str] = Field(default=None, max_length=10)


@router.post("/api/v1/auth/recover")
def recover_credentials(payload: RecoverIn, request: Request):
    serial, pin = payload.serial.strip(), payload.pin.strip()
    guard_check(request, serial)
    with db() as cur:
        cur.execute(
            """SELECT s.application_id, s.first_name, s.date_of_birth
               FROM public.cloud_scratch_cards c
               JOIN public.cloud_students_staging s
                 ON UPPER(TRIM(s.application_id)) = UPPER(TRIM(c.used_by_application_id))
               WHERE UPPER(TRIM(c.serial_number)) = UPPER(%s) AND UPPER(TRIM(c.pin_number)) = UPPER(%s)
                 AND UPPER(COALESCE(c.card_status, '')) = 'USED'""",
            (serial, pin),
        )
        row = cur.fetchone()
        complete = bool(row) and not _is_pending(row)
        verified = row is not None
        if verified and complete and row["date_of_birth"] is not None:
            try:
                given = datetime.strptime((payload.date_of_birth or "").strip(), "%Y-%m-%d").date()
            except ValueError:
                given = None
            verified = given is not None and given == _as_date(row["date_of_birth"])
        if not verified:
            guard_fail(request, serial)
            raise HTTPException(400, RECOVER_FAILED)  # same message for every kind of failure

        new_password = _new_password()
        cur.execute("UPDATE public.cloud_students_staging SET student_password = %s WHERE application_id = %s",
                    (new_password, row["application_id"]))
    guard_clear(request, serial)
    return _no_store(JSONResponse({
        "success": True,
        "application_id": row["application_id"].strip(),
        "new_password": new_password,
        "registration_complete": complete,
    }))


# ---------------------------------------------------------------- staff reset (admin)
class ResetIn(BaseModel):
    application_id: str = Field(min_length=3, max_length=40)


@router.post("/admin/api/students/reset-password")
def staff_reset_password(payload: ResetIn, admin: dict = Depends(require("screening"))):
    new_password = _new_password()
    with db() as cur:
        cur.execute(
            """UPDATE public.cloud_students_staging SET student_password = %s
               WHERE UPPER(TRIM(application_id)) = UPPER(%s) RETURNING application_id""",
            (new_password, payload.application_id.strip().replace("\\", "/")),
        )
        row = cur.fetchone()
        if not row:
            raise HTTPException(404, "Application ID not found.")
        audit(cur, admin, "student.reset_password", row["application_id"].strip(), {})
    return {"success": True, "application_id": row["application_id"].strip(), "new_password": new_password}
