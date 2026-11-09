"""
Scratch-card management for the web admin, plus the redemption rules used by
/api/v1/cards/verify in main.py.

The Java desktop app stays the card generator. This module never creates cards
and never returns PINs; it records sales, voids cards and reports status.
"""
import time
from decimal import Decimal
from typing import List, Literal, Optional

import psycopg2.extras
from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import BaseModel, Field, model_validator

from admin import audit, db, require

router = APIRouter(prefix="/admin/api/cards")

DEFAULTS = {"require_sold_card": "true", "card_sales_mode": "printed", "card_price": "0"}


# ------------------------------------------------------------ redemption rules
_FAILS: dict = {}
_WINDOW = 15 * 60


def _client_ip(request: Request) -> str:
    forwarded = request.headers.get("x-forwarded-for", "")
    return forwarded.split(",")[0].strip() or (request.client.host if request.client else "unknown")


def _keys(request: Request, serial: str):
    return ((f"s:{serial.strip().upper()}", 5), (f"ip:{_client_ip(request)}", 20))


def guard_check(request: Request, serial: str) -> None:
    """Block a serial after 5 wrong PIN guesses, or an address after 20, for 15 minutes."""
    now = time.time()
    for key, limit in _keys(request, serial):
        _FAILS[key] = [t for t in _FAILS.get(key, []) if now - t < _WINDOW]
        if len(_FAILS[key]) >= limit:
            raise HTTPException(429, "Too many wrong attempts. Please try again in 15 minutes.")


def guard_fail(request: Request, serial: str) -> None:
    now = time.time()
    for key, _ in _keys(request, serial):
        _FAILS.setdefault(key, []).append(now)


def guard_clear(request: Request, serial: str) -> None:
    _FAILS.pop(f"s:{serial.strip().upper()}", None)


def card_problem(status: Optional[str], section: Optional[str], is_sold: bool,
                 required_section: str, require_sold: bool) -> Optional[str]:
    """Return a message if the card cannot be redeemed, otherwise None."""
    st = (status or "UNUSED").strip().upper()
    if st == "USED":
        return "This scratch card has already been used."
    if st != "UNUSED":
        return "This scratch card is no longer valid. Please contact the school office."
    sec = (section or "").strip().upper()
    if sec and sec != required_section:
        return f"This is a {sec} card and cannot be used for a {required_section} class."
    if require_sold and not is_sold:
        return "This card has not been activated yet. Please buy it from the school office."
    return None


# ------------------------------------------------------------ helpers
def get_settings(cur) -> dict:
    cur.execute("SELECT key, value FROM public.cloud_portal_settings")
    merged = dict(DEFAULTS)
    merged.update({r["key"]: r["value"] for r in cur.fetchall()})
    return merged


STATUS_SQL = """
    SELECT c.serial_number, c.school_section, c.batch_number, c.used_by_application_id, c.date_used,
           s.sold_to, s.sold_at, s.price,
           CASE WHEN UPPER(COALESCE(c.card_status, '')) = 'USED' THEN 'Used'
                WHEN UPPER(COALESCE(c.card_status, '')) IN ('VOIDED', 'EXPIRED') THEN 'Voided'
                WHEN s.sale_id IS NOT NULL THEN 'Sold'
                ELSE 'Unused' END AS status
    FROM public.cloud_scratch_cards c
    LEFT JOIN public.cloud_card_sales s ON s.serial_number = c.serial_number AND s.voided_at IS NULL
"""


# ------------------------------------------------------------ list + summary
@router.get("")
def list_cards(q: str = "", status: str = "", section: str = "", batch: str = "",
               limit: int = Query(50, ge=1, le=200), offset: int = Query(0, ge=0),
               admin: dict = Depends(require("cards"))):
    like = "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    with db() as cur:
        cur.execute(
            f"""SELECT t.*, COUNT(*) OVER() AS total FROM ({STATUS_SQL}) t
                WHERE (%(q)s = '' OR t.serial_number ILIKE %(like)s OR t.sold_to ILIKE %(like)s
                       OR t.used_by_application_id ILIKE %(like)s)
                  AND (%(st)s = '' OR t.status = %(st)s)
                  AND (%(sec)s = '' OR t.school_section = %(sec)s)
                  AND (%(batch)s = '' OR t.batch_number = %(batch)s)
                ORDER BY t.batch_number, t.serial_number LIMIT %(limit)s OFFSET %(offset)s""",
            {"q": q.strip(), "like": like, "st": status.strip(), "sec": section.strip().upper(),
             "batch": batch.strip(), "limit": limit, "offset": offset},
        )
        rows = cur.fetchall()
    total = rows[0]["total"] if rows else 0
    for r in rows:
        r.pop("total")
    return {"success": True, "cards": rows, "total": total}


@router.get("/summary")
def card_summary(admin: dict = Depends(require("cards"))):
    with db() as cur:
        cur.execute(f"SELECT status, COUNT(*) AS n FROM ({STATUS_SQL}) t GROUP BY status")
        counts = {"Unused": 0, "Sold": 0, "Used": 0, "Voided": 0}
        counts.update({r["status"]: r["n"] for r in cur.fetchall()})
        cur.execute("""SELECT COALESCE(SUM(price), 0) AS revenue FROM public.cloud_card_sales
                       WHERE voided_at IS NULL""")
        revenue = cur.fetchone()["revenue"]
        cur.execute("""SELECT DISTINCT batch_number FROM public.cloud_scratch_cards
                       WHERE batch_number IS NOT NULL ORDER BY batch_number""")
        batches = [r["batch_number"] for r in cur.fetchall()]
    return {"success": True, "counts": counts, "revenue": revenue, "batches": batches}


# ------------------------------------------------------------ sales and voids
class SellIn(BaseModel):
    serials: Optional[List[str]] = Field(default=None, max_length=500)
    batch: Optional[str] = Field(default=None, max_length=50)
    sold_to: str = Field(min_length=1, max_length=80)
    price: Decimal = Field(ge=0, max_digits=10, decimal_places=2)
    note: Optional[str] = Field(default=None, max_length=200)

    @model_validator(mode="after")
    def exactly_one_target(self):
        if bool(self.serials) == bool(self.batch):
            raise ValueError("Give either a list of serial numbers or a batch name.")
        return self


@router.post("/sell")
def sell_cards(payload: SellIn, admin: dict = Depends(require("cards"))):
    sold, skipped = [], []
    with db() as cur:
        if payload.batch:
            cur.execute(
                """INSERT INTO public.cloud_card_sales (serial_number, price, sold_to, sold_by, note)
                   SELECT c.serial_number, %s, %s, %s, %s FROM public.cloud_scratch_cards c
                   WHERE c.batch_number = %s AND UPPER(COALESCE(c.card_status, '')) = 'UNUSED'
                     AND NOT EXISTS (SELECT 1 FROM public.cloud_card_sales s WHERE s.serial_number = c.serial_number)
                   RETURNING serial_number""",
                (payload.price, payload.sold_to.strip(), admin["username"], payload.note, payload.batch.strip()),
            )
            sold = [r["serial_number"] for r in cur.fetchall()]
        else:
            wanted = list(dict.fromkeys(s.strip() for s in payload.serials if s.strip()))
            cur.execute("""SELECT c.serial_number, c.card_status,
                                  EXISTS (SELECT 1 FROM public.cloud_card_sales s
                                          WHERE s.serial_number = c.serial_number) AS has_sale
                           FROM public.cloud_scratch_cards c WHERE c.serial_number = ANY(%s)""", (wanted,))
            found = {r["serial_number"]: r for r in cur.fetchall()}
            for serial in wanted:
                row = found.get(serial)
                if not row:
                    skipped.append({"serial": serial, "reason": "not found"})
                elif (row["card_status"] or "Unused").strip().upper() != "UNUSED":
                    skipped.append({"serial": serial, "reason": "already used or voided"})
                elif row["has_sale"]:
                    skipped.append({"serial": serial, "reason": "already sold"})
                else:
                    cur.execute(
                        """INSERT INTO public.cloud_card_sales (serial_number, price, sold_to, sold_by, note)
                           VALUES (%s, %s, %s, %s, %s)""",
                        (serial, payload.price, payload.sold_to.strip(), admin["username"], payload.note),
                    )
                    sold.append(serial)
        audit(cur, admin, "cards.sell", payload.batch or "serial list",
              {"count": len(sold), "sold_to": payload.sold_to, "price": float(payload.price)})
    return {"success": True, "sold": len(sold), "skipped": skipped}


class VoidIn(BaseModel):
    serial: str = Field(min_length=3, max_length=30)
    reason: str = Field(min_length=3, max_length=200)


@router.post("/void")
def void_card(payload: VoidIn, admin: dict = Depends(require("cards"))):
    with db() as cur:
        cur.execute(
            """UPDATE public.cloud_scratch_cards
               SET card_status = 'Voided', sync_status = 'PENDING_SYNC', updated_at = now()
               WHERE serial_number = %s AND UPPER(COALESCE(card_status, '')) NOT IN ('USED', 'VOIDED')
               RETURNING serial_number""",
            (payload.serial.strip(),),
        )
        if not cur.fetchone():
            raise HTTPException(404, "Card not found, or it is already used or voided.")
        audit(cur, admin, "cards.void", payload.serial.strip(), {"reason": payload.reason})
    return {"success": True}


# ------------------------------------------------------------ settings
class SettingsIn(BaseModel):
    require_sold_card: bool
    card_sales_mode: Literal["printed", "online", "both"]
    card_price: Decimal = Field(ge=0, max_digits=10, decimal_places=2)


@router.get("/settings")
def read_settings(admin: dict = Depends(require("cards"))):
    with db() as cur:
        s = get_settings(cur)
    return {"success": True, "require_sold_card": s["require_sold_card"].lower() != "false",
            "card_sales_mode": s["card_sales_mode"], "card_price": Decimal(s["card_price"] or "0")}


@router.post("/settings")
def save_settings(payload: SettingsIn, admin: dict = Depends(require("cards"))):
    if admin["role"] != "admin":
        raise HTTPException(403, "Only an admin can change card settings.")
    values = {"require_sold_card": "true" if payload.require_sold_card else "false",
              "card_sales_mode": payload.card_sales_mode, "card_price": str(payload.card_price)}
    with db() as cur:
        for key, value in values.items():
            cur.execute("""INSERT INTO public.cloud_portal_settings (key, value) VALUES (%s, %s)
                           ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value""", (key, value))
        audit(cur, admin, "cards.settings", "portal", values)
    return {"success": True}
