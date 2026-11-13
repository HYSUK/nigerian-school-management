import os
from datetime import datetime
from typing import Optional
import uuid
from admin import router as admin_router
from online_cards import public_router as online_cards_router, admin_gen_router as card_gen_router
from admin_payments import router as payments_router
from admin_cards import router as cards_router, card_problem, guard_check, guard_fail, guard_clear
from admission_letter import router as letter_router
from student_portal import router as student_router
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Request, status
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from fastapi.exceptions import RequestValidationError
from pydantic import BaseModel, ConfigDict
import psycopg2
import pyodbc

# 🔑 Load environment variables from local .env file
load_dotenv()

DEFAULT_SUPABASE_URI = (
    "postgresql://postgres:YOUR_PASSWORD_HERE@aws-1-eu-central-1.pooler.supabase.com:5432/postgres"
)
SUPABASE_DB_URI = os.getenv("DATABASE_URL", DEFAULT_SUPABASE_URI)

# Key the Java desktop app sends with its sync calls
SYNC_API_KEY = os.getenv("SYNC_API_KEY", "hysuk_sync_token_2026")

# Connection pooling for local SQL Server
pyodbc.pooling = True

app = FastAPI(
    title="Nigerian Hybrid School Management Cloud Gateway",
    description="Cleaned & Polished Supabase Cloud-Native Gateway Server with MSSQL Compatibility",
    version="3.2.0",
)

# --- SECURE DATABASE CONNECTION STRING ---
SQL_SERVER_HOST = os.getenv("SQL_SERVER_HOST", "localhost,1433")
SQL_SERVER_DB = os.getenv("SQL_SERVER_DB", "SchoolManagementDB")
SQL_SERVER_USER = os.getenv("SQL_SERVER_USER", "sa")
SQL_SERVER_PASSWORD = os.getenv("SQL_SERVER_PASSWORD", "")

DB_CONN_STR = (
    "DRIVER={ODBC Driver 17 for SQL Server};"
    f"SERVER={SQL_SERVER_HOST};"
    f"DATABASE={SQL_SERVER_DB};"
    f"UID={SQL_SERVER_USER};"
    f"PWD={SQL_SERVER_PASSWORD};"
    "Encrypt=no;"
)

app.include_router(admin_router)
app.include_router(cards_router)
app.include_router(online_cards_router)
app.include_router(card_gen_router)
app.include_router(letter_router)
app.include_router(student_router)
app.include_router(payments_router)

# Mount the static directory to serve static files like images, CSS, etc.
app.mount("/static", StaticFiles(directory="static"), name="static")

# 🌐 EXPLICIT GLOBAL EXCEPTION HANDLERS (Ensures standard application/json outputs on errors)
@app.exception_handler(HTTPException)
async def custom_http_exception_handler(request: Request, exc: HTTPException):
    return JSONResponse(
        status_code=exc.status_code,
        content={"success": False, "detail": exc.detail}
    )

@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    return JSONResponse(
        status_code=422,
        content={"success": False, "detail": "Missing fields or invalid payload parameters.", "errors": exc.errors()}
    )

@app.exception_handler(Exception)
async def global_generic_exception_handler(request: Request, exc: Exception):
    print(f"--> [UNHANDLED ERROR]: {exc!r}")
    return JSONResponse(
        status_code=500,
        content={"success": False, "detail": "Something went wrong on the server. Please try again."}
    )


# 🔒 Local SQL Server Database Connection String (Used for desktop app sync)
DB_CONN_STR = (
    "DRIVER={ODBC Driver 17 for SQL Server};"
    "SERVER=localhost,1433;"
    "DATABASE=SchoolManagementDB;"
    "UID=sa;"
    "PWD=hysuk;"
    "Encrypt=no;"
)

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
TEMPLATES_DIR = os.path.join(BASE_DIR, "templates")
templates = Jinja2Templates(directory=TEMPLATES_DIR)


# ==========================================
#  📋 DATA SCHEMAS
# ==========================================
class CardVerificationRequest(BaseModel):
    serial: str
    pin: str
    target_class: str
    model_config = ConfigDict(str_strip_whitespace=True)


class ScreeningUpdatePayload(BaseModel):
    model_config = ConfigDict(extra="allow")
    application_id: Optional[str] = None
    applicationId: Optional[str] = None
    app_id: Optional[str] = None

    screening_score: Optional[int] = None
    screeningScore: Optional[int] = None
    exam_score: Optional[int] = None
    examScore: Optional[int] = None
    score: Optional[int] = None

    cutoff_mark: Optional[int] = 50
    cutoffMark: Optional[int] = 50
    cutoff: Optional[int] = 50

    @property
    def clean_id(self) -> str:
        raw = self.application_id or self.applicationId or self.app_id or ""
        return raw.strip().replace("\\", "/")

    @property
    def final_score(self) -> int:
        for val in [
            self.screening_score,
            self.screeningScore,
            self.exam_score,
            self.examScore,
            self.score,
        ]:
            if val is not None:
                return val
        return 0

    @property
    def final_cutoff(self) -> int:
        for val in [self.cutoff_mark, self.cutoffMark, self.cutoff]:
            if val is not None:
                return val
        return 50


# ==========================================
#  💳 SCRATCH CARD VERIFICATION (creates the student account)
# ==========================================
@app.post("/api/v1/cards/verify", status_code=status.HTTP_201_CREATED)
async def verify_and_generate_credentials(payload: CardVerificationRequest, request: Request):
    raw_class = payload.target_class.strip().upper().replace(" ", "")
    required_section = "JS" if raw_class.startswith("JS") else "SS"

    clean_serial = payload.serial.strip()
    clean_pin = payload.pin.strip()

    # Block repeated wrong guesses before touching the database
    guard_check(request, clean_serial)

    try:
        with psycopg2.connect(SUPABASE_DB_URI) as conn:
            with conn.cursor() as cursor:
                # 1) Find the card by serial AND pin, and lock the row so it cannot be redeemed twice at once
                cursor.execute(
                    """
                    SELECT c.card_id, c.card_status, c.school_section,
                           EXISTS (
                               SELECT 1 FROM public.cloud_card_sales s
                               WHERE s.serial_number = c.serial_number AND s.voided_at IS NULL
                           ) AS is_sold
                    FROM public.cloud_scratch_cards c
                    WHERE UPPER(TRIM(c.serial_number)) = UPPER(%s)
                      AND UPPER(TRIM(c.pin_number)) = UPPER(%s)
                    FOR UPDATE OF c
                    """,
                    (clean_serial, clean_pin),
                )
                card_row = cursor.fetchone()

                if not card_row:
                    guard_fail(request, clean_serial)
                    raise HTTPException(
                        status_code=404,
                        detail="Invalid Scratch Card. Serial or PIN number is incorrect.",
                    )

                card_id, card_status, card_section, is_sold = card_row

                # 2) Is the "only sold cards" rule switched on? (defaults to on)
                cursor.execute(
                    "SELECT value FROM public.cloud_portal_settings WHERE key = 'require_sold_card'"
                )
                setting = cursor.fetchone()
                require_sold = (setting[0] if setting else "true").strip().lower() != "false"

                # 3) Used, voided, wrong section, or not sold yet?
                problem = card_problem(card_status, card_section, is_sold, required_section, require_sold)
                if problem:
                    raise HTTPException(status_code=400, detail=problem)

                # 4) Generate credentials
                gen_app_id = f"{required_section}/{datetime.now().year}/{str(uuid.uuid4())[:5].upper()}"
                gen_pwd = f"PWD-{str(uuid.uuid4())[:6].upper()}"

                # 5) Mark the card as used (the sync engine carries this down to the desktop)
                cursor.execute(
                    """
                    UPDATE public.cloud_scratch_cards
                    SET card_status = 'Used',
                        used_by_application_id = %s,
                        date_used = NOW(),
                        sync_status = 'PENDING_SYNC'
                    WHERE card_id = %s
                    """,
                    (gen_app_id, card_id),
                )

                # 6) Create the provisional profile inside the staging table
                cursor.execute(
                    """
                    INSERT INTO public.cloud_students_staging
                    (application_id, student_password, target_class, first_name, last_name, sync_status)
                    VALUES (%s, %s, %s, 'PENDING', 'PROFILE', 'PENDING_REGISTRATION')
                    """,
                    (gen_app_id, gen_pwd, raw_class),
                )

                conn.commit()
                guard_clear(request, clean_serial)

                return {
                    "success": True,
                    "application_id": gen_app_id,
                    "portal_password": gen_pwd,
                }

    except HTTPException as http_ex:
        raise http_ex
    except Exception as e:
        print(f"--> [CARD VERIFY ERROR]: {e!r}")
        raise HTTPException(
            status_code=500, detail="Could not verify the card right now. Please try again."
        )


# ==========================================
#  🌐 PUBLIC PAGES (login and registration pages are in student_portal.py)
# ==========================================
@app.get("/")
@app.get("/home")
async def serve_home_portal_view(request: Request):
    return templates.TemplateResponse(
        request=request, name="home.html", context={}
    )

@app.get("/apply", response_class=HTMLResponse)
async def serve_apply_page(request: Request):
    return templates.TemplateResponse(
        request=request, name="apply.html", context={}
    )


# ==========================================
#  🔄 SCREENING EXERCISE SYNC ENDPOINT (used by the Java desktop app)
# ==========================================
@app.post("/api/v1/sync/screening-result", status_code=status.HTTP_200_OK)
async def update_student_screening_result(
    payload: ScreeningUpdatePayload, request: Request
):
    api_key = request.headers.get("x-api-key") or request.headers.get("X-API-KEY")
    if api_key != SYNC_API_KEY:
        raise HTTPException(
            status_code=401, detail="Invalid API sync key."
        )

    clean_app_id = payload.clean_id
    if not clean_app_id:
        raise HTTPException(
            status_code=422,
            detail="Missing application_id in request payload.",
        )

    score = payload.final_score
    cutoff = payload.final_cutoff
    determined_status = "Passed" if score >= cutoff else "Failed"

    local_ok = False
    cloud_ok = False

    try:
        with psycopg2.connect(SUPABASE_DB_URI) as conn_cloud:
            with conn_cloud.cursor() as cursor_cloud:
                cursor_cloud.execute(
                    """
                    UPDATE public.cloud_students_staging
                    SET screening_score = %s, cutoff_mark = %s, screening_status = %s
                    WHERE UPPER(TRIM(application_id)) = UPPER(%s);
                    """,
                    (score, cutoff, determined_status, clean_app_id),
                )
                conn_cloud.commit()
                cloud_ok = True
    except Exception as e:
        print(f"--> [SUPABASE CLOUD ERROR]: {str(e)}")

    try:
        with pyodbc.connect(DB_CONN_STR) as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    """
                    UPDATE [SchoolManagementDB].[dbo].[Students]
                    SET ScreeningScore = ?, ExamScore = ?, ScreeningStatus = ?
                    WHERE UPPER(LTRIM(RTRIM(ApplicationID))) = UPPER(?)
                    """,
                    (score, score, determined_status, clean_app_id),
                )
                conn.commit()
                local_ok = True
    except Exception as e:
        print(f"--> [LOCAL SQL SERVER ERROR (Expected on Cloud Host)]: {str(e)}")

    if cloud_ok or local_ok:
        return {
            "status": "success",
            "message": f"Screening result updated to {determined_status}",
            "application_id": clean_app_id,
            "screening_status": determined_status,
            "cloud_synced": cloud_ok,
            "local_synced": local_ok,
        }

    raise HTTPException(
        status_code=404,
        detail=f"Application ID '{clean_app_id}' not found.",
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=False)
