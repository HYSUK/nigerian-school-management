"""
Subjects catalogue, field offerings (places and minimum score) and subject limits for the admin.

Mount in main.py:
    from admin_subjects import router as subjects_router
    app.include_router(subjects_router)
"""
from typing import List, Literal, Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field, model_validator

from admin import SESSION_RE, audit, db, require

router = APIRouter(prefix="/admin/api/subjects")
AREA = "screening"  # the admin and exams roles
RULE_DEFAULTS = {"js_min_subjects": 12, "js_max_subjects": 14, "ss_min_subjects": 8, "ss_max_subjects": 9}


@router.get("")
def list_subjects(level: str = "", admin: dict = Depends(require(AREA))):
    with db() as cur:
        cur.execute(
            """SELECT subject_id, name, level, category, field, pick_group, is_active
               FROM public.cloud_subjects WHERE (%s = '' OR level = %s)
               ORDER BY level, category, field NULLS FIRST, pick_group NULLS FIRST, name""",
            (level.strip().upper(), level.strip().upper()),
        )
        return {"success": True, "subjects": cur.fetchall()}


class SubjectIn(BaseModel):
    name: str = Field(min_length=2, max_length=80)
    level: Literal["JS", "SS"]
    category: Literal["core", "elective"]
    field: Optional[str] = Field(default=None, max_length=40)
    pick_group: Optional[str] = Field(default=None, max_length=40)


@router.post("")
def add_subject(payload: SubjectIn, admin: dict = Depends(require(AREA))):
    field = (payload.field or "").strip() or None
    if field and payload.level != "SS":
        raise HTTPException(422, "Fields of study apply to SS subjects only.")
    with db() as cur:
        cur.execute(
            """INSERT INTO public.cloud_subjects (name, level, category, field, pick_group)
               VALUES (%s, %s, %s, %s, %s)""",
            (payload.name.strip(), payload.level, payload.category, field, (payload.pick_group or "").strip() or None),
        )
        audit(cur, admin, "subject.add", f"{payload.level} {payload.name}", {})
    return {"success": True}


class ActiveIn(BaseModel):
    is_active: bool


@router.post("/{subject_id}/active")
def set_active(subject_id: int, payload: ActiveIn, admin: dict = Depends(require(AREA))):
    with db() as cur:
        cur.execute("UPDATE public.cloud_subjects SET is_active = %s WHERE subject_id = %s RETURNING name, level",
                    (payload.is_active, subject_id))
        row = cur.fetchone()
        if not row:
            raise HTTPException(404, "Subject not found.")
        audit(cur, admin, "subject.on" if payload.is_active else "subject.off", f"{row['level']} {row['name']}", {})
    return {"success": True}


# ---------------------------------------------------------------- fields offered per session
@router.get("/offerings")
def list_offerings(session: str = Query(pattern=SESSION_RE), admin: dict = Depends(require(AREA))):
    with db() as cur:
        cur.execute(
            """SELECT f.field, o.capacity, o.min_score, COALESCE(o.is_open, TRUE) AS is_open
               FROM (SELECT field FROM public.cloud_subjects WHERE level = 'SS' AND field IS NOT NULL AND is_active
                     UNION SELECT field FROM public.cloud_field_offerings WHERE session = %s) f
               LEFT JOIN public.cloud_field_offerings o ON o.field = f.field AND o.session = %s
               ORDER BY f.field""",
            (session, session),
        )
        return {"success": True, "offerings": cur.fetchall()}


class OfferingIn(BaseModel):
    session: str = Field(pattern=SESSION_RE)
    field: str = Field(min_length=2, max_length=40)
    capacity: Optional[int] = Field(default=None, ge=0, le=5000)
    min_score: Optional[int] = Field(default=None, ge=0, le=100)
    is_open: bool = True


@router.post("/offerings")
def save_offering(payload: OfferingIn, admin: dict = Depends(require(AREA))):
    with db() as cur:
        cur.execute(
            """INSERT INTO public.cloud_field_offerings (session, field, capacity, min_score, is_open)
               VALUES (%s, %s, %s, %s, %s)
               ON CONFLICT (session, field) DO UPDATE
               SET capacity = EXCLUDED.capacity, min_score = EXCLUDED.min_score, is_open = EXCLUDED.is_open""",
            (payload.session, payload.field.strip(), payload.capacity, payload.min_score, payload.is_open),
        )
        audit(cur, admin, "field.save", f"{payload.session} {payload.field}",
              {"capacity": payload.capacity, "min_score": payload.min_score, "open": payload.is_open})
    return {"success": True}


# ---------------------------------------------------------------- subject limits
class RulesIn(BaseModel):
    js_min_subjects: int = Field(ge=1, le=30)
    js_max_subjects: int = Field(ge=1, le=30)
    ss_min_subjects: int = Field(ge=1, le=30)
    ss_max_subjects: int = Field(ge=1, le=30)

    @model_validator(mode="after")
    def minimum_not_above_maximum(self):
        if self.js_min_subjects > self.js_max_subjects or self.ss_min_subjects > self.ss_max_subjects:
            raise ValueError("a minimum cannot be higher than its maximum")
        return self


@router.get("/rules")
def read_rules(admin: dict = Depends(require(AREA))):
    with db() as cur:
        cur.execute("SELECT key, value FROM public.cloud_portal_settings WHERE key = ANY(%s)", (list(RULE_DEFAULTS),))
        stored = {r["key"]: r["value"] for r in cur.fetchall()}
    return {"success": True, **{k: int(stored.get(k, d)) for k, d in RULE_DEFAULTS.items()}}


@router.post("/rules")
def save_rules(payload: RulesIn, admin: dict = Depends(require(AREA))):
    if admin["role"] != "admin":
        raise HTTPException(403, "Only an admin can change the subject limits.")
    with db() as cur:
        for key, value in payload.model_dump().items():
            cur.execute("""INSERT INTO public.cloud_portal_settings (key, value) VALUES (%s, %s)
                           ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value""", (key, str(value)))
        audit(cur, admin, "subject.limits", "portal", payload.model_dump())
    return {"success": True}
