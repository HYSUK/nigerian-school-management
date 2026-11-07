"""
Create or reset an admin login.

    python create_admin.py <username> <role>        role: admin | exams | bursar

Reads DATABASE_URL from the environment or .env. The password is typed
at a hidden prompt and stored as a salted scrypt hash.
"""
import getpass
import os
import sys

import psycopg2
from dotenv import load_dotenv

from admin import hash_password

load_dotenv()
ROLES = ("admin", "exams", "bursar")

if len(sys.argv) != 3 or sys.argv[2] not in ROLES:
    sys.exit(f"Usage: python create_admin.py <username> <{'|'.join(ROLES)}>")
username, role = sys.argv[1].strip().lower(), sys.argv[2]
password = getpass.getpass("Password (12+ characters): ")
if len(password) < 12 or password != getpass.getpass("Repeat password: "):
    sys.exit("Passwords must match and be at least 12 characters.")

dsn = os.getenv("DATABASE_URL") or sys.exit("Set DATABASE_URL first.")
with psycopg2.connect(dsn) as conn, conn.cursor() as cur:
    cur.execute(
        """INSERT INTO public.cloud_admin_users (username, password_hash, role)
           VALUES (%s, %s, %s)
           ON CONFLICT (username) DO UPDATE
             SET password_hash = EXCLUDED.password_hash, role = EXCLUDED.role, is_active = true""",
        (username, hash_password(password), role),
    )
print(f"Saved admin '{username}' with role '{role}'.")
