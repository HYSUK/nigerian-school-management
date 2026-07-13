import base64
from datetime import date, datetime
import os
from typing import List, Optional
import uuid

from dotenv import load_dotenv
from fastapi import FastAPI, File, Form, Header, HTTPException, Request, UploadFile, status
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel, ConfigDict
import psycopg2
import pyodbc

# 🔑 Load environment variables from local .env file
load_dotenv()

DEFAULT_SUPABASE_URI = (
    "postgresql://postgres.iabqbxelyvsyifgkjcjz:uDxOwmyknWiW5t58@"
    "aws-1-eu-central-1.pooler.supabase.com:5432/postgres"
)
SUPABASE_DB_URI = os.getenv("DATABASE_URL", DEFAULT_SUPABASE_URI)

# Connection pooling for local SQL Server
pyodbc.pooling = True

app = FastAPI(
    title="Nigerian Hybrid School Management Cloud Gateway",
    description="Cleaned & Polished Sync Gateway Server",
    version="3.0.0",
)

# 🔒 Local SQL Server Database Connection String
DB_CONN_STR = (
    "DRIVER={ODBC Driver 17 for SQL Server};"
    "SERVER=localhost,1433;"
    "DATABASE=SchoolManagementDB;"
    "UID=sa;"
    "PWD=hysuk;"
    "Encrypt=no;"
)

# 📁 Physical Passport Directory Setup
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PASSPORT_DIR = os.path.join(BASE_DIR, "passports")
if not os.path.exists(PASSPORT_DIR):
    os.makedirs(PASSPORT_DIR)

app.mount(
    "/uploaded-passports",
    StaticFiles(directory=PASSPORT_DIR),
    name="passports",
)

TEMPLATES_DIR = os.path.join(BASE_DIR, "templates")
templates = Jinja2Templates(directory=TEMPLATES_DIR)


# ==========================================
#  📋 DATA SCHEMAS
# ==========================================


class LoginRequest(BaseModel):
    username: str
    password: str


class CardVerificationRequest(BaseModel):
    serial: str
    pin: str
    target_class: str
    model_config = ConfigDict(str_strip_whitespace=True)


class ProfileCompletionRequest(BaseModel):
    application_id: str
    first_name: str
    last_name: str
    gender: str
    date_of_birth: Optional[str] = None
    state_of_origin: str
    lga: str
    home_address: Optional[str] = None
    guardian_name: Optional[str] = None
    guardian_relationship: Optional[str] = None
    guardian_phone: str
    prev_primary_school: Optional[str] = None
    primary_from_year: Optional[int] = None
    primary_to_year: Optional[int] = None
    prev_junior_sec_school: Optional[str] = None
    junior_sec_from_year: Optional[int] = None
    junior_sec_to_year: Optional[int] = None
    passport_base64: Optional[str] = None


# 🎯 FLEXIBLE SCREENING SYNC SCHEMA (Matches Java HttpSyncClient)
class ScreeningUpdatePayload(BaseModel):
    model_config = ConfigDict(extra="allow")

    # Application ID variants
    application_id: Optional[str] = None
    applicationId: Optional[str] = None
    app_id: Optional[str] = None

    # Score variants
    screening_score: Optional[int] = None
    screeningScore: Optional[int] = None
    exam_score: Optional[int] = None
    examScore: Optional[int] = None
    score: Optional[int] = None

    # Cutoff mark variants
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


class AssignCredentialsPayload(BaseModel):
    pending_id: int
    application_id: str
    student_password: str


# ==========================================
#  🛠️ HELPER FUNCTIONS
# ==========================================


def save_base64_passport_to_disk(app_id: str, base64_str: str) -> str:
    try:
        if "," in base64_str:
            base64_str = base64_str.split(",")[1]
        image_bytes = base64.b64decode(base64_str)
        safe_filename = app_id.replace("/", "_") + ".jpg"
        disk_path = os.path.join(PASSPORT_DIR, safe_filename)
        with open(disk_path, "wb") as f:
            f.write(image_bytes)
        return os.path.abspath(disk_path)
    except Exception as e:
        raise HTTPException(
            status_code=400, detail=f"Passport save failure: {str(e)}"
        )


# ==========================================
#  🚪 AUTHENTICATION & CARD VERIFICATION
# ==========================================


@app.post("/api/v1/auth/login")
async def portal_user_authentication(payload: LoginRequest):
    try:
        with pyodbc.connect(DB_CONN_STR) as conn:
            with conn.cursor() as cursor:
                query = """
                SELECT ApplicationID, FirstName, LastName, CurrentClass, LocalPassportPath
                FROM [SchoolManagementDB].[dbo].[Students]
                WHERE UPPER(LTRIM(RTRIM(ApplicationID))) = UPPER(?)
                AND UPPER(LTRIM(RTRIM(StudentPassword))) = UPPER(?)
                """
                cursor.execute(
                    query, (payload.username.strip(), payload.password.strip())
                )
                row = cursor.fetchone()
                if not row:
                    raise HTTPException(
                        status_code=401, detail="Invalid credentials."
                    )
                app_id, first_name, last_name, current_class, _ = row
                return {
                    "success": True,
                    "application_id": str(app_id).strip(),
                    "first_name": str(first_name).strip(),
                    "last_name": str(last_name).strip(),
                    "target_class": str(current_class).strip(),
                    "is_profile_pending": (
                        str(first_name).strip().upper() == "PENDING"
                    ),
                }
    except pyodbc.Error as e:
        raise HTTPException(
            status_code=500, detail=f"Auth error: {str(e)}"
        )


@app.post("/api/v1/cards/verify", status_code=status.HTTP_201_CREATED)
async def verify_and_generate_credentials(payload: CardVerificationRequest):
    raw_class = payload.target_class.strip().upper().replace(" ", "")
    required_section = "JS" if raw_class.startswith("JS") else "SS"
    conn = None
    try:
        conn = pyodbc.connect(DB_CONN_STR)
        cursor = conn.cursor()
        conn.autocommit = False

        cursor.execute(
            """
            SELECT CardStatus FROM [SchoolManagementDB].[dbo].[ScratchCards]
            WHERE UPPER(LTRIM(RTRIM(SerialNumber))) = UPPER(?) AND UPPER(LTRIM(RTRIM(PinNumber))) = UPPER(?)
        """,
            (payload.serial.strip(), payload.pin.strip()),
        )
        card_row = cursor.fetchone()

        if not card_row:
            raise HTTPException(
                status_code=404, detail="Invalid card credentials."
            )
        if str(card_row[0]).strip().lower() == "used":
            raise HTTPException(
                status_code=400, detail="Scratch card already used."
            )

        gen_app_id = f"{required_section}/{datetime.now().year}/{str(uuid.uuid4())[:5].upper()}"
        gen_pwd = f"PWD-{str(uuid.uuid4())[:6].upper()}"

        cursor.execute(
            """
            INSERT INTO [SchoolManagementDB].[dbo].[Students]
            (ApplicationID, StudentPassword, CurrentClass, FirstName, LastName, Gender, StateOfOrigin, LGA, GuardianPhone, IsActive, EnrollmentDate)
            VALUES (?, ?, ?, 'PENDING', 'PENDING', 'PENDING', 'PENDING', 'PENDING', 'PENDING', 1, GETDATE())
        """,
            (gen_app_id, gen_pwd, raw_class),
        )

        cursor.execute(
            """
            UPDATE [SchoolManagementDB].[dbo].[ScratchCards]
            SET CardStatus = 'Used', UsedByApplicationID = ?
            WHERE UPPER(LTRIM(RTRIM(SerialNumber))) = UPPER(?) AND UPPER(LTRIM(RTRIM(PinNumber))) = UPPER(?)
        """,
            (gen_app_id, payload.serial.strip(), payload.pin.strip()),
        )

        conn.commit()
        return {
            "success": True,
            "application_id": gen_app_id,
            "portal_password": gen_pwd,
        }
    except pyodbc.Error as e:
        if conn:
            conn.rollback()
        raise HTTPException(
            status_code=500, detail=f"Database error: {str(e)}"
        )
    finally:
        if conn:
            conn.close()


# ==========================================
#  📝 REGISTRATION ENDPOINTS
# ==========================================


@app.post("/api/v1/register", status_code=status.HTTP_200_OK)
@app.post("/api/v1/mobile/register", status_code=status.HTTP_200_OK)
async def complete_student_profile(payload: ProfileCompletionRequest):
    conn = None
    try:
        clean_app_id = payload.application_id.strip()
        file_disk_path = "PENDING_UPLOAD"

        if payload.passport_base64 and payload.passport_base64.strip():
            file_disk_path = save_base64_passport_to_disk(
                clean_app_id, payload.passport_base64
            )

        parsed_dob = None
        if payload.date_of_birth and payload.date_of_birth.strip():
            try:
                parsed_dob = datetime.strptime(
                    payload.date_of_birth.strip(), "%Y-%m-%d"
                ).date()
            except ValueError:
                pass

        conn = pyodbc.connect(DB_CONN_STR)
        cursor = conn.cursor()

        cursor.execute(
            """
            UPDATE [SchoolManagementDB].[dbo].[Students]
            SET FirstName = ?, LastName = ?, Gender = ?, DateOfBirth = ?,
                StateOfOrigin = ?, LGA = ?, HomeAddress = ?, GuardianName = ?,
                GuardianRelationship = ?, GuardianPhone = ?, PrevPrimarySchool = ?,
                PrimaryFromYear = ?, PrimaryToYear = ?, PrevJuniorSecSchool = ?,
                JuniorSecFromYear = ?, JuniorSecToYear = ?, LocalPassportPath = ?,
                WebPassportBase64 = 'DISK_STORED'
            WHERE UPPER(LTRIM(RTRIM(ApplicationID))) = UPPER(?)
        """,
            (
                payload.first_name.strip()[:50],
                payload.last_name.strip()[:50],
                payload.gender.strip()[:10],
                parsed_dob,
                payload.state_of_origin.strip()[:50],
                payload.lga.strip()[:50],
                payload.home_address,
                payload.guardian_name,
                payload.guardian_relationship or "Parent",
                payload.guardian_phone.strip()[:15],
                payload.prev_primary_school,
                payload.primary_from_year,
                payload.primary_to_year,
                payload.prev_junior_sec_school,
                payload.junior_sec_from_year,
                payload.junior_sec_to_year,
                file_disk_path,
                clean_app_id,
            ),
        )

        if cursor.rowcount == 0:
            raise HTTPException(
                status_code=404, detail="Student profile not found."
            )

        conn.commit()
        return {"success": True, "message": "Profile saved successfully."}
    except pyodbc.Error as e:
        raise HTTPException(
            status_code=500, detail=f"Database update error: {str(e)}"
        )
    finally:
        if conn:
            conn.close()


# ==========================================
#  🌐 WEB VIEWS & DASHBOARD
# ==========================================


@app.get("/")
@app.get("/home")
async def serve_home_portal_view(request: Request):
    return templates.TemplateResponse(
        request=request, name="home.html", context={}
    )


@app.get("/dashboard", response_class=HTMLResponse)
async def serve_student_dashboard_view(
    request: Request, app_id: Optional[str] = None
):
    passport_url = "https://images.unsplash.com/photo-1534528741775-53994a69daeb?q=80&w=256&auto=format&fit=crop"
    first_name, last_name, current_class = "Student", "Profile", "N/A"
    screening_status, screening_score = "Awaiting Academic Screening", "N/A"

    if app_id:
        try:
            with pyodbc.connect(DB_CONN_STR) as conn:
                with conn.cursor() as cursor:
                    cursor.execute(
                        """
                        SELECT FirstName, LastName, CurrentClass, LocalPassportPath, ScreeningStatus, ScreeningScore
                        FROM [SchoolManagementDB].[dbo].[Students]
                        WHERE UPPER(LTRIM(RTRIM(ApplicationID))) = UPPER(?)
                    """,
                        (app_id.strip(),),
                    )
                    row = cursor.fetchone()
                    if row:
                        first_name = str(row[0]).strip()
                        last_name = str(row[1]).strip()
                        current_class = str(row[2]).strip()
                        screening_status = (
                            str(row[4]).strip()
                            if row[4]
                            else "Awaiting Academic Screening"
                        )
                        screening_score = (
                            str(row[5]) if row[5] is not None else "N/A"
                        )

                        safe_app_filename = (
                            app_id.strip().replace("/", "_") + ".jpg"
                        )
                        if os.path.exists(
                            os.path.join(PASSPORT_DIR, safe_app_filename)
                        ):
                            passport_url = (
                                f"/uploaded-passports/{safe_app_filename}"
                            )
                        elif row[3] and os.path.exists(
                            os.path.join(
                                PASSPORT_DIR, os.path.basename(str(row[3]).strip())
                            )
                        ):
                            passport_url = f"/uploaded-passports/{os.path.basename(str(row[3]).strip())}"
        except Exception as e:
            print(f"Dashboard lookup error: {str(e)}")

    return templates.TemplateResponse(
        request=request,
        name="dashboard.html",
        context={
            "application_id": app_id or "Not Provided",
            "first_name": first_name,
            "last_name": last_name,
            "current_class": current_class,
            "admission_status": screening_status,
            "screening_score": screening_score,
            "passport_url": passport_url,
        },
    )


# ==========================================
#  🔍 STUDENT LOOKUP ENDPOINT
# ==========================================


@app.get("/api/v1/student/{app_id:path}")
async def get_student_details(app_id: str):
    clean_app_id = app_id.strip()
    try:
        with pyodbc.connect(DB_CONN_STR) as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    """
                    SELECT ApplicationID, FirstName, LastName, CurrentClass, ScreeningStatus, ScreeningScore
                    FROM [SchoolManagementDB].[dbo].[Students]
                    WHERE UPPER(LTRIM(RTRIM(ApplicationID))) = UPPER(?)
                """,
                    (clean_app_id,),
                )
                row = cursor.fetchone()
                if not row:
                    raise HTTPException(
                        status_code=404, detail="Student not found."
                    )
                return {
                    "success": True,
                    "application_id": str(row[0]).strip(),
                    "first_name": str(row[1]).strip(),
                    "last_name": str(row[2]).strip(),
                    "current_class": str(row[3]).strip(),
                    "screening_status": (
                        str(row[4]).strip() if row[4] else "Pending"
                    ),
                    "screening_score": (
                        row[5] if row[5] is not None else 0
                    ),
                }
    except Exception as e:
        raise HTTPException(
            status_code=500, detail=f"Database fetch error: {str(e)}"
        )


# ==========================================
#  🔄 SCREENING EXERCISE SYNC ENDPOINT
# ==========================================


@app.post(
    "/api/v1/sync/screening-result", status_code=status.HTTP_200_OK
)
async def update_student_screening_result(
    payload: ScreeningUpdatePayload, request: Request
):
    # Flexible Header API Key validation
    api_key = request.headers.get("x-api-key") or request.headers.get("X-API-KEY")
    if api_key and api_key != "hysuk_sync_token_2026":
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

    print("\n==========================================")
    print(
        f"--> [SYNC RECEIVED] AppID: '{clean_app_id}' | Score: {score} | "
        f"Cutoff: {cutoff} | Status: {determined_status}"
    )

    local_ok = False
    cloud_ok = False

    # 1. Update Local SQL Server
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
                print("--> [LOCAL SQL SERVER] Sync SUCCESS")
    except Exception as e:
        print(f"--> [LOCAL SQL SERVER ERROR]: {str(e)}")

    # 2. Update Supabase Cloud DB
    try:
        conn_cloud = psycopg2.connect(SUPABASE_DB_URI)
        cursor_cloud = conn_cloud.cursor()
        cursor_cloud.execute(
            """
            UPDATE public.cloud_students_staging
            SET screening_score = %s, cutoff_mark = %s, screening_status = %s
            WHERE UPPER(LTRIM(RTRIM(application_id))) = UPPER(%s);
        """,
            (score, cutoff, determined_status, clean_app_id),
        )
        conn_cloud.commit()
        cursor_cloud.close()
        conn_cloud.close()
        cloud_ok = True
        print("--> [SUPABASE CLOUD] Sync SUCCESS")
    except Exception as e:
        print(f"--> [SUPABASE CLOUD ERROR]: {str(e)}")

    print("==========================================\n")

    if local_ok or cloud_ok:
        return {
            "status": "success",
            "message": f"Screening result updated to {determined_status}",
            "application_id": clean_app_id,
            "screening_status": determined_status,
            "cloud_synced": cloud_ok,
        }

    raise HTTPException(
        status_code=404,
        detail=f"Application ID '{clean_app_id}' not found.",
    )


# ==========================================
#  🚀 LAUNCHER
# ==========================================

if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=False)