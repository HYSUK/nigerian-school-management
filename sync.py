import os
import time
import psycopg2
import pyodbc
from dotenv import load_dotenv

load_dotenv()

SUPABASE_DB_URI = os.getenv("DATABASE_URL", "postgresql://postgres:YOUR_PASSWORD_HERE@aws-1-eu-central-1.pooler.supabase.com:5432/postgres")
MSSQL_CONN_STR = (
    "DRIVER={ODBC Driver 17 for SQL Server};"
    "SERVER=localhost,1433;"
    "DATABASE=SchoolManagementDB;"
    "UID=sa;"
    "PWD=hysuk;"
    "Encrypt=no;"
)

def get_pending_registrations():
    try:
        with psycopg2.connect(SUPABASE_DB_URI) as conn:
            with conn.cursor() as cursor:
                query = """
                SELECT 
                    application_id, student_password, first_name, last_name, gender, 
                    date_of_birth, state_of_origin, lga, home_address, target_class, 
                    guardian_name, guardian_relationship, guardian_phone, 
                    prev_primary_school, primary_from_year, primary_to_year, 
                    prev_junior_sec_school, junior_sec_from_year, junior_sec_to_year, 
                    passport_base64
                FROM public.cloud_students_staging
                WHERE sync_status = 'PENDING_IMPORT';
                """
                cursor.execute(query)
                columns = [col[0] for col in cursor.description]
                return [dict(zip(columns, row)) for row in cursor.fetchall()]
    except Exception as e:
        print(f"❌ Error pulling from Supabase: {e}")
        return []

def mark_as_synced_in_cloud(application_id):
    try:
        with psycopg2.connect(SUPABASE_DB_URI) as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    """
                    UPDATE public.cloud_students_staging
                    SET sync_status = 'IMPORTED'
                    WHERE application_id = %s;
                    """,
                    (application_id,)
                )
                conn.commit()
                return True
    except Exception as e:
        print(f"❌ Error updating status in Supabase for {application_id}: {e}")
        return False

def upsert_to_local_mssql(student):
    try:
        conn = pyodbc.connect(MSSQL_CONN_STR)
        cursor = conn.cursor()

        # SAFE VALUE CONVERSIONS: Explicitly cast integers to string varchar(4)
        def clean_year_to_str(val):
            return str(int(val)) if val is not None else None

        upsert_query = """
        MERGE dbo.Students AS target
        USING (SELECT ? AS ApplicationID) AS source
        ON (target.ApplicationID = source.ApplicationID)
        WHEN MATCHED THEN
            UPDATE SET 
                FirstName = ?, LastName = ?, Gender = ?, DateOfBirth = ?,
                StateOfOrigin = ?, LGA = ?, HomeAddress = ?, CurrentClass = ?, 
                GuardianName = ?, GuardianRelationship = ?, GuardianPhone = ?,
                StudentPassword = ?, PrevPrimarySchool = ?, PrimaryFromYear = ?, 
                PrimaryToYear = ?, PrevJuniorSecSchool = ?, JuniorSecFromYear = ?, 
                JuniorSecToYear = ?, WebPassportBase64 = ?, IsActive = 1
        WHEN NOT MATCHED THEN
            INSERT (
                ApplicationID, FirstName, LastName, Gender, DateOfBirth,
                StateOfOrigin, LGA, HomeAddress, CurrentClass, GuardianName,
                GuardianRelationship, GuardianPhone, StudentPassword,
                PrevPrimarySchool, PrimaryFromYear, PrimaryToYear,
                PrevJuniorSecSchool, JuniorSecFromYear, JuniorSecToYear,
                WebPassportBase64, ScreeningStatus, IsActive
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'Pending', 1
            );
        """

        params = (
            student['application_id'],
            
            # MATCHED: UPDATE
            student['first_name'], student['last_name'], student['gender'], student['date_of_birth'],
            student['state_of_origin'], student['lga'], student['home_address'], student['target_class'],
            student['guardian_name'], student['guardian_relationship'], student['guardian_phone'],
            student['student_password'], student['prev_primary_school'], clean_year_to_str(student['primary_from_year']),
            clean_year_to_str(student['primary_to_year']), student['prev_junior_sec_school'], clean_year_to_str(student['junior_sec_from_year']),
            clean_year_to_str(student['junior_sec_to_year']), student['passport_base64'],
            
            # NOT MATCHED: INSERT
            student['application_id'], student['first_name'], student['last_name'], student['gender'], student['date_of_birth'],
            student['state_of_origin'], student['lga'], student['home_address'], student['target_class'],
            student['guardian_name'], student['guardian_relationship'], student['guardian_phone'],
            student['student_password'], student['prev_primary_school'], clean_year_to_str(student['primary_from_year']),
            clean_year_to_str(student['primary_to_year']), student['prev_junior_sec_school'], clean_year_to_str(student['junior_sec_from_year']),
            clean_year_to_str(student['junior_sec_to_year']), student['passport_base64']
        )

        cursor.execute(upsert_query, params)
        conn.commit()
        conn.close()
        return True
    except Exception as e:
        print(f"❌ MSSQL Write Error for {student['application_id']}: {e}")
        return False

def start_sync_cycle():
    print("🔄 Local Hybrid Sync Agent is running...")
    while True:
        pending_students = get_pending_registrations()
        
        if pending_students:
            print(f"📥 Pulled {len(pending_students)} profile registration(s). Importing to Local Server...")
            for student in pending_students:
                app_id = student['application_id']
                if upsert_to_local_mssql(student):
                    print(f"✅ Local import successful for: {app_id}")
                    if mark_as_synced_in_cloud(app_id):
                        print(f"☁️ Supabase database updated: {app_id} status set to IMPORTED.")
                else:
                    print(f"⚠️ Failed to import {app_id} to MSSQL. Sync skipped.")
        
        time.sleep(10)

if __name__ == "__main__":
    start_sync_cycle()
