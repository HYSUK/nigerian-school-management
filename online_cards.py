import csv
import io
import random
from decimal import Decimal
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from admin import audit, db, require
from admin_cards import get_settings

# Public router for online card purchases
public_router = APIRouter(prefix="/api/v1/cards", tags=["Online Cards"])

# Admin router for generating & downloading batches
admin_gen_router = APIRouter(prefix="/admin/api/cards", tags=["Admin Card Generation"])


# =====================================================================
# 💳 PUBLIC ENDPOINT: ONLINE SCRATCH CARD PURCHASE
# =====================================================================
class OnlinePurchaseIn(BaseModel):
    buyer_name: str = Field(min_length=2, max_length=80)
    buyer_phone: str = Field(min_length=7, max_length=25)
    school_section: Literal["JS", "SS"]


@public_router.post("/buy-online")
def buy_card_online(payload: OnlinePurchaseIn):
    """Vends an unsold scratch card online and records the sale in cloud_card_sales."""
    section = payload.school_section.strip().upper()
    buyer_label = f"Online: {payload.buyer_name.strip()} ({payload.buyer_phone.strip()})"

    with db() as cur:
        # 1. Verify online sales are allowed in admin settings
        settings = get_settings(cur)
        mode = settings.get("card_sales_mode", "printed").strip().lower()
        if mode == "printed":
            raise HTTPException(403, "Online card sales are currently disabled by the school.")

        price = Decimal(settings.get("card_price") or "0")

        # 2. Pick and lock an available Unused card that has NOT been sold yet
        cur.execute(
            """
            SELECT c.card_id, c.serial_number, c.pin_number
            FROM public.cloud_scratch_cards c
            WHERE UPPER(TRIM(c.school_section)) = %s
              AND UPPER(COALESCE(c.card_status, 'UNUSED')) = 'UNUSED'
              AND NOT EXISTS (
                  SELECT 1 FROM public.cloud_card_sales s
                  WHERE s.serial_number = c.serial_number AND s.voided_at IS NULL
              )
            ORDER BY c.card_id ASC
            LIMIT 1
            FOR UPDATE OF c SKIP LOCKED;
            """,
            (section,),
        )
        card = cur.fetchone()

        if not card:
            raise HTTPException(
                404,
                f"No {section} scratch cards are available online right now. Please check with the school office.",
            )

        serial_no = card["serial_number"].strip()
        pin_no = card["pin_number"].strip()

        # 3. Record the sale in cloud_card_sales (matches admin_cards.py & main.py verification)
        cur.execute(
            """
            INSERT INTO public.cloud_card_sales (serial_number, price, sold_to, sold_by, note)
            VALUES (%s, %s, %s, 'ONLINE_PORTAL', 'Automated web purchase')
            """,
            (serial_no, price, buyer_label),
        )

    return {
        "success": True,
        "message": "Scratch card purchased successfully!",
        "serial_number": serial_no,
        "pin_number": pin_no,
        "school_section": section,
        "price_paid": float(price),
    }


# =====================================================================
# 🎫 ADMIN ENDPOINTS: GENERATE BATCH & DOWNLOAD CSV
# =====================================================================
class GenerateBatchIn(BaseModel):
    batch_number: str = Field(min_length=2, max_length=50)
    school_section: Literal["JS", "SS"]
    quantity: int = Field(ge=1, le=5000)


@admin_gen_router.post("/generate")
def generate_card_batch(payload: GenerateBatchIn, admin: dict = Depends(require("cards"))):
    """Generates a batch of scratch cards with 12-digit serials and 10-digit zero-padded PINs."""
    batch = payload.batch_number.strip().upper()
    section = payload.school_section.strip().upper()
    created = 0

    with db() as cur:
        for _ in range(payload.quantity):
            serial_number = str(random.randint(100000000000, 999999999999))
            pin_number = f"{random.randint(0, 9999999999):010d}"

            cur.execute(
                """
                INSERT INTO public.cloud_scratch_cards (
                    serial_number, pin_number, school_section, card_status, batch_number, sync_status
                ) VALUES (%s, %s, %s, 'Unused', %s, 'PENDING_SYNC')
                ON CONFLICT (serial_number) DO NOTHING
                RETURNING serial_number;
                """,
                (serial_number, pin_number, section, batch),
            )
            if cur.fetchone():
                created += 1

        audit(
            cur,
            admin,
            "cards.generate",
            batch,
            {"section": section, "requested": payload.quantity, "created": created},
        )

    return {
        "success": True,
        "batch_number": batch,
        "created": created,
        "download_url": f"/admin/api/cards/batch/{batch}/csv",
    }


@admin_gen_router.get("/batch/{batch_number}/csv")
def download_batch_csv(batch_number: str, admin: dict = Depends(require("cards"))):
    """Downloads a clean, Excel-safe CSV of a scratch card batch for printing."""
    batch = batch_number.strip()
    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow(["serial_number", "pin_number", "school_section", "batch_number", "status"])

    with db() as cur:
        cur.execute(
            """
            SELECT serial_number, pin_number, school_section, batch_number, card_status
            FROM public.cloud_scratch_cards
            WHERE batch_number = %s
            ORDER BY card_id ASC;
            """,
            (batch,),
        )
        rows = cur.fetchall()

    if not rows:
        raise HTTPException(404, f"No cards found for batch '{batch}'.")

    for r in rows:
        writer.writerow([
            f'="{r["serial_number"].strip()}"',
            f'="{str(r["pin_number"]).strip().zfill(10)}"',
            r["school_section"],
            r["batch_number"],
            r["card_status"],
        ])

    output.seek(0)
    # utf-8-sig ensures Excel reads formatting cleanly
    csv_bytes = ("\ufeff" + output.getvalue()).encode("utf-8")
    return StreamingResponse(
        io.BytesIO(csv_bytes),
        media_type="text/csv",
        headers={"Content-Disposition": f"attachment; filename=cards_{batch}.csv"},
    )