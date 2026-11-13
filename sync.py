import os
import time
import psycopg2
import pyodbc
from dotenv import load_dotenv

# Load env variables
load_dotenv()

# Configs
SUPABASE_DB_URI = os.getenv("DATABASE_URL", "postgresql://postgres:YOUR_PASSWORD_HERE@aws-1-eu-central-1.pooler.supabase.com:5432/postgres")
MSSQL_CONN_STR = (
    "DRIVER={ODBC Driver 17 for SQL Server};"
    f"SERVER={os.getenv('SQL_SERVER_HOST', 'localhost,1433')};"
    f"DATABASE={os.getenv('SQL_SERVER_DB', 'SchoolManagementDB')};"
    f"UID={os.getenv('SQL_SERVER_USER', 'sa')};"
    f"PWD={os.getenv('SQL_SERVER_PASSWORD', '')};"
    "Encrypt=no;"
)
)

# =====================================================================
# 📋 WORKFLOW 1: STUDENT BALANCING ROUTINES
# =====================================================================

def sync_students_outbound(cloud_cursor, local_cursor):
    """Finds students registered locally on the desktop app and uploads them to Supabase"""
    try:
        local_cursor.execute("""
            SELECT
                ApplicationID, StudentPassword, FirstName, LastName, Gender,
                DateOfBirth, StateOfOrigin, LGA, HomeAddress, CurrentClass,
                GuardianName, GuardianRelationship, GuardianPhone, WebPassportBase64
            FROM dbo.Students
            WHERE SyncToCloudStatus = 'PENDING';
        """)

        columns = [col[0] for col in local_cursor.description]
        pending_locals = [dict(zip(columns, row)) for row in local_cursor.fetchall()]

        if not pending_locals:
            return

        print(f"📤 Found {len(pending_locals)} local desktop registration(s). Syncing up to Supabase...")

        for student in pending_locals:
            cloud_cursor.execute("""
                INSERT INTO public.cloud_students_staging (
                    application_id, student_password, first_name, last_name, gender,
                    date_of_birth, state_of_origin, lga, home_address, target_class,
                    guardian_name, guardian_relationship, guardian_phone, passport_base64,
                    sync_status
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 'IMPORTED')
                ON CONFLICT (application_id) DO NOTHING;
            """, (
                student['ApplicationID'], student['StudentPassword'], student['FirstName'],
                student['LastName'], student['Gender'], str(student['DateOfBirth']),
                student['StateOfOrigin'], student['LGA'], student['HomeAddress'],
                student['CurrentClass'], student['GuardianName'], student['GuardianRelationship'],
                student['GuardianPhone'], student['WebPassportBase64']
            ))

        for student in pending_locals:
            local_cursor.execute("""
                UPDATE dbo.Students
                SET SyncToCloudStatus = 'SYNCED'
                WHERE ApplicationID = ?;
            """, (student['ApplicationID'],))
        print("✅ Local desktop registrations successfully synced to the cloud portal!")

    except Exception as e:
        print(f"❌ Error during outbound cloud student sync: {e}")


def sync_students_inbound(cloud_cursor, local_cursor):
    """Fetch registered students from cloud and upsert to local MSSQL"""
    try:
        cloud_cursor.execute("""
            SELECT
                application_id, student_password, first_name, last_name, gender,
                date_of_birth, state_of_origin, lga, home_address, target_class,
                guardian_name, guardian_relationship, guardian_phone,
                prev_primary_school, primary_from_year, primary_to_year,
                prev_junior_sec_school, junior_sec_from_year, junior_sec_to_year,
                passport_base64
            FROM public.cloud_students_staging
            WHERE sync_status = 'PENDING_IMPORT';
        """)
        columns = [col[0] for col in cloud_cursor.description]
        pending_students = [dict(zip(columns, row)) for row in cloud_cursor.fetchall()]

        if not pending_students:
            return

        print(f"📥 Found {len(pending_students)} new registration(s) on Supabase. Processing...")

        def clean_year(val):
            return int(val) if val is not None else None

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
                WebPassportBase64, ScreeningStatus, IsActive, SyncToCloudStatus
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'Pending', 1, 'SYNCED'
            );
        """

        for student in pending_students:
            app_id = student['application_id']
            params = (
                app_id,
                student['first_name'], student['last_name'], student['gender'], student['date_of_birth'],
                student['state_of_origin'], student['lga'], student['home_address'], student['target_class'],
                student['guardian_name'], student['guardian_relationship'], student['guardian_phone'],
                student['student_password'], student['prev_primary_school'], clean_year(student['primary_from_year']),
                clean_year(student['primary_to_year']), student['prev_junior_sec_school'], clean_year(student['junior_sec_from_year']),
                clean_year(student['junior_sec_to_year']), student['passport_base64'],
                app_id, student['first_name'], student['last_name'], student['gender'], student['date_of_birth'],
                student['state_of_origin'], student['lga'], student['home_address'], student['target_class'],
                student['guardian_name'], student['guardian_relationship'], student['guardian_phone'],
                student['student_password'], student['prev_primary_school'], clean_year(student['primary_from_year']),
                clean_year(student['primary_to_year']), student['prev_junior_sec_school'], clean_year(student['junior_sec_from_year']),
                clean_year(student['junior_sec_to_year']), student['passport_base64']
            )

            try:
                local_cursor.execute(upsert_query, params)
                cloud_cursor.execute(
                    "UPDATE public.cloud_students_staging SET sync_status = 'IMPORTED' WHERE application_id = %s;",
                    (app_id,)
                )
                print(f"✅ Successful bidirectional balance for student: {app_id}")
            except Exception as inner_e:
                print(f"❌ Failed processing single row entry {app_id}: {inner_e}")

    except Exception as e:
        print(f"❌ Error during inbound student sync: {e}")


# =====================================================================
# 💳 WORKFLOW 2: SCRATCH CARD TRACKING AND SYNCING
# =====================================================================

def pull_and_sync_cloud_used_cards(cloud_cursor, local_cursor):
    """📥 PULL: Finds cards consumed online via the Web Portal and marks them USED locally"""
    try:
        # Fetch online used cards from Supabase (using standard lowercase column labels)
        cloud_cursor.execute("""
        SELECT serial_number, pin_number, used_by_application_id, date_used, card_status
        FROM public.cloud_scratch_cards
        WHERE sync_status IN ('PENDING', 'PENDING_SYNC')
        AND card_status IN ('Used', 'Voided');
        """)

        rows = cloud_cursor.fetchall()
        if not rows:
            return

        print(f"📥 Found {len(rows)} online card consumption(s). Syncing down to SSMS...")

        for row in rows:
            s_num, p_num, app_id, dt_used, c_status = row

            # Force local SSMS to match this state and mark it as already SYNCED locally
            local_cursor.execute("""
            UPDATE dbo.ScratchCards
            SET CardStatus = ?, UsedByApplicationID = ?, DateUsed = ?, SyncToCloudStatus = 'SYNCED'
            WHERE SerialNumber = ? AND PINNumber = ?;
            """, (c_status, app_id, dt_used, s_num, p_num))

        # Mark processed rows as SYNCED in the cloud database
        for row in rows:
            s_num = row[0]
            cloud_cursor.execute("""
                UPDATE public.cloud_scratch_cards
                SET sync_status = 'SYNCED'
                WHERE serial_number = %s;
            """, (s_num,))

        print("✅ Online usages successfully mirrored to local SSMS database!")

    except Exception as e:
        print(f"❌ Error during inbound scratch card sync: {e}")


def push_local_generated_cards_to_cloud(cloud_cursor, local_cursor):
    """📤 PUSH: Pushes local status changes (New batches OR local consumption) up to Supabase"""
    try:
        # Fetch anything that changed locally (New Unused cards OR locally Used cards)
        local_cursor.execute("""
            SELECT SerialNumber, PINNumber, SchoolSection, CardStatus, BatchNumber, UsedByApplicationID, DateUsed
            FROM dbo.ScratchCards
            WHERE SyncToCloudStatus = 'PENDING';
        """)

        columns = [col[0] for col in local_cursor.description]
        local_changes = [dict(zip(columns, row)) for row in local_cursor.fetchall()]

        if not local_changes:
            return

        print(f"📤 Found {len(local_changes)} local card change(s). Uploading to Supabase...")

        for card in local_changes:
            cloud_cursor.execute("""
                INSERT INTO public.cloud_scratch_cards (
                    serial_number, pin_number, school_section, card_status, batch_number, used_by_application_id, date_used, sync_status
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, 'SYNCED')
                ON CONFLICT (serial_number)
                DO UPDATE SET
                    card_status = EXCLUDED.card_status,
                    used_by_application_id = EXCLUDED.used_by_application_id,
                    date_used = EXCLUDED.date_used,
                    sync_status = 'SYNCED';
            """, (
                card['SerialNumber'], card['PINNumber'], card['SchoolSection'],
                card['CardStatus'], card['BatchNumber'], card['UsedByApplicationID'], card['DateUsed']
            ))

        # Flip local flag to SYNCED
        for card in local_changes:
            local_cursor.execute("""
                UPDATE dbo.ScratchCards
                SET SyncToCloudStatus = 'SYNCED'
                WHERE SerialNumber = ?;
            """, (card['SerialNumber'],))
        print("✅ Local database changes successfully mirrored to the cloud.")

    except Exception as e:
        print(f"❌ Error during outbound scratch card sync: {e}")


# =====================================================================
# 🏁 ENGINE MAIN POLL CONTROL (PERSISTENT HIGH-VELOCITY HOOK)
# =====================================================================

def start_sync_cycle():
    print("🔄 Local Hybrid Sync Engine Core 4.0.0 Running...")

    try:
        print("🔌 Connecting permanent channels...")
        cloud_conn = psycopg2.connect(SUPABASE_DB_URI)
        local_conn = pyodbc.connect(MSSQL_CONN_STR)

        # Enable immediate pipeline autocommit to avoid isolation transaction blocks
        cloud_conn.autocommit = True
        local_conn.autocommit = True

        cloud_cursor = cloud_conn.cursor()
        local_cursor = local_conn.cursor()
        print("🚀 Channels successfully bound and streaming data.")

        while True:
            # 1. Check Student pipelines
            sync_students_inbound(cloud_cursor, local_cursor)
            sync_students_outbound(cloud_cursor, local_cursor)

            # 2. Check Scratch Card pipelines
            pull_and_sync_cloud_used_cards(cloud_cursor, local_cursor)
            push_local_generated_cards_to_cloud(cloud_cursor, local_cursor)

            # Poll interval check rate
            time.sleep(10)

    except KeyboardInterrupt:
        print("\n🛑 Shutting down sync engine channels cleanly...")
    except Exception as e:
        print(f"💥 Fatal core engine socket crash: {e}")
    finally:
        try: cloud_cursor.close(); cloud_conn.close()
        except: pass
        try: local_cursor.close(); local_conn.close()
        except: pass
        print("💤 Sync Engine completely halted.")


# =====================================================================
# 🏁 ENGINE MAIN POLL CONTROL (RESILIENT AUTO-RECONNECT LOOP)
# =====================================================================

def start_sync_cycle():
    print("🔄 Local Hybrid Sync Engine Core 4.0.0 Running...")

    while True:
        cloud_conn = None
        local_conn = None
        cloud_cursor = None
        local_cursor = None
        
        try:
            print("🔌 Connecting permanent channels...")
            cloud_conn = psycopg2.connect(SUPABASE_DB_URI)
            local_conn = pyodbc.connect(MSSQL_CONN_STR)

            # Enable immediate pipeline autocommit to avoid isolation transaction blocks
            cloud_conn.autocommit = True
            local_conn.autocommit = True

            cloud_cursor = cloud_conn.cursor()
            local_cursor = local_conn.cursor()
            print("🚀 Channels successfully bound and streaming data.")

            while True:
                try:
                    # 1. Check Student pipelines
                    sync_students_inbound(cloud_cursor, local_cursor)
                    sync_students_outbound(cloud_cursor, local_cursor)

                    # 2. Check Scratch Card pipelines
                    pull_and_sync_cloud_used_cards(cloud_cursor, local_cursor)
                    push_local_generated_cards_to_cloud(cloud_cursor, local_cursor)

                except (psycopg2.OperationalError, psycopg2.InterfaceError) as db_drop:
                    print(f"⚠️ Cloud connection dropped: {db_drop}. Forcing reconnect...")
                    raise db_drop # Breaks inner loop to trigger fresh connection

                # Poll interval check rate
                time.sleep(10)

        except KeyboardInterrupt:
            print("\n🛑 Shutting down sync engine channels cleanly...")
            break
        except Exception as e:
            print(f"💥 Channel interruption detected: {e}. Reconnecting in 10 seconds...")
            time.sleep(10)
        finally:
            # Clean up stale sockets before retrying
            try: 
                if cloud_cursor: cloud_cursor.close()
                if cloud_conn: cloud_conn.close()
            except: pass
            try: 
                if local_cursor: local_cursor.close()
                if local_conn: local_conn.close()
            except: pass

    print("💤 Sync Engine completely halted.")


if __name__ == "__main__":
    start_sync_cycle()
