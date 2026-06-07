from __future__ import annotations

import hashlib
import os
import secrets
import sqlite3
from datetime import datetime, timezone
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel


DB_PATH = os.environ.get("BIKE_TRAINER_DB", os.path.join(os.path.dirname(__file__), "bike_trainer.db"))
SESSION_TABLE_CANDIDATES = ("sessions", "workout_sessions", "workouts")
PASSWORD_ITERATIONS = 260_000

app = FastAPI(title="Bike Trainer API")
session_table = "sessions"


class SignupRequest(BaseModel):
    name: str
    email: str
    password: str
    age: int | None = None
    height_cm: float | None = None
    weight_kg: float | None = None
    gender: str | None = None
    max_heart_rate: int | None = None
    resting_heart_rate: int | None = None
    ftp_watts: int | None = None


class LoginRequest(BaseModel):
    email: str
    password: str


class UpdateUserRequest(BaseModel):
    name: str | None = None
    email: str | None = None
    age: int | None = None
    height_cm: float | None = None
    weight_kg: float | None = None
    gender: str | None = None
    max_heart_rate: int | None = None
    resting_heart_rate: int | None = None
    ftp_watts: int | None = None


class SessionRequest(BaseModel):
    session_id: str | None = None
    device_id: str | None = None
    workout_type: str | None = None
    status: str | None = None


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def connect() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def table_exists(conn: sqlite3.Connection, table: str) -> bool:
    row = conn.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
        (table,),
    ).fetchone()
    return row is not None


def table_columns(conn: sqlite3.Connection, table: str) -> set[str]:
    return {row["name"] for row in conn.execute(f"PRAGMA table_info({quote_identifier(table)})")}


def quote_identifier(identifier: str) -> str:
    if not identifier.replace("_", "").isalnum():
        raise ValueError(f"Unsafe SQLite identifier: {identifier}")
    return f'"{identifier}"'


def add_column_if_missing(conn: sqlite3.Connection, table: str, column: str, definition: str) -> None:
    if column not in table_columns(conn, table):
        conn.execute(
            f"ALTER TABLE {quote_identifier(table)} ADD COLUMN {quote_identifier(column)} {definition}"
        )


def initialize_database() -> None:
    global session_table

    with connect() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                email TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                age INTEGER,
                height_cm REAL,
                weight_kg REAL,
                gender TEXT,
                max_heart_rate INTEGER,
                resting_heart_rate INTEGER,
                ftp_watts INTEGER,
                created_at TEXT
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS auth_tokens (
                token TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL,
                created_at TEXT,
                FOREIGN KEY(user_id) REFERENCES users(id)
            )
            """
        )

        for candidate in SESSION_TABLE_CANDIDATES:
            if table_exists(conn, candidate):
                session_table = candidate
                break
        else:
            session_table = "sessions"
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER,
                    session_id TEXT,
                    device_id TEXT,
                    workout_type TEXT,
                    status TEXT,
                    started_at TEXT,
                    ended_at TEXT,
                    created_at TEXT,
                    FOREIGN KEY(user_id) REFERENCES users(id)
                )
                """
            )

        # Existing historical rows are intentionally left with NULL user_id.
        add_column_if_missing(conn, session_table, "user_id", "INTEGER REFERENCES users(id)")
        conn.commit()


@app.on_event("startup")
def startup() -> None:
    initialize_database()


def hash_password(password: str) -> str:
    salt = secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256",
        password.encode("utf-8"),
        bytes.fromhex(salt),
        PASSWORD_ITERATIONS,
    ).hex()
    return f"pbkdf2_sha256${PASSWORD_ITERATIONS}${salt}${digest}"


def verify_password(password: str, stored_hash: str) -> bool:
    try:
        algorithm, iterations_text, salt, expected_digest = stored_hash.split("$", 3)
        if algorithm != "pbkdf2_sha256":
            return False
        digest = hashlib.pbkdf2_hmac(
            "sha256",
            password.encode("utf-8"),
            bytes.fromhex(salt),
            int(iterations_text),
        ).hex()
        return secrets.compare_digest(digest, expected_digest)
    except Exception:
        return False


def create_token(conn: sqlite3.Connection, user_id: int) -> str:
    token = secrets.token_urlsafe(32)
    conn.execute(
        "INSERT INTO auth_tokens (token, user_id, created_at) VALUES (?, ?, ?)",
        (token, user_id, utc_now()),
    )
    return token


def user_response(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "name": row["name"],
        "email": row["email"],
        "age": row["age"],
        "height_cm": row["height_cm"],
        "weight_kg": row["weight_kg"],
        "gender": row["gender"],
        "max_heart_rate": row["max_heart_rate"],
        "resting_heart_rate": row["resting_heart_rate"],
        "ftp_watts": row["ftp_watts"],
        "created_at": row["created_at"],
    }


def get_current_user(authorization: str | None = Header(default=None)) -> dict[str, Any]:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing auth token")

    token = authorization.removeprefix("Bearer ").strip()
    with connect() as conn:
        row = conn.execute(
            """
            SELECT users.*
            FROM users
            JOIN auth_tokens ON auth_tokens.user_id = users.id
            WHERE auth_tokens.token = ?
            """,
            (token,),
        ).fetchone()

    if row is None:
        raise HTTPException(status_code=401, detail="Invalid auth token")

    return user_response(row)


def validate_email(email: str) -> str:
    normalized = email.strip().lower()
    if not normalized or "@" not in normalized:
        raise HTTPException(status_code=400, detail="Valid email is required")
    return normalized


@app.post("/api/auth/signup")
def signup(request: SignupRequest) -> dict[str, Any]:
    name = request.name.strip()
    email = validate_email(request.email)

    if not name:
        raise HTTPException(status_code=400, detail="Name is required")
    if not request.password:
        raise HTTPException(status_code=400, detail="Password is required")

    with connect() as conn:
        try:
            cursor = conn.execute(
                """
                INSERT INTO users (
                    name, email, password_hash, age, height_cm, weight_kg, gender,
                    max_heart_rate, resting_heart_rate, ftp_watts, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    name,
                    email,
                    hash_password(request.password),
                    request.age,
                    request.height_cm,
                    request.weight_kg,
                    (request.gender or "").strip() or None,
                    request.max_heart_rate,
                    request.resting_heart_rate,
                    request.ftp_watts,
                    utc_now(),
                ),
            )
        except sqlite3.IntegrityError:
            raise HTTPException(status_code=409, detail="Email already exists") from None

        token = create_token(conn, cursor.lastrowid)
        user = conn.execute("SELECT * FROM users WHERE id = ?", (cursor.lastrowid,)).fetchone()
        conn.commit()

    return {"token": token, "user": user_response(user)}


@app.post("/api/auth/login")
def login(request: LoginRequest) -> dict[str, Any]:
    email = validate_email(request.email)

    with connect() as conn:
        user = conn.execute("SELECT * FROM users WHERE email = ?", (email,)).fetchone()
        if user is None or not verify_password(request.password, user["password_hash"]):
            raise HTTPException(status_code=401, detail="Wrong email or password")

        token = create_token(conn, user["id"])
        conn.commit()

    return {"token": token, "user": user_response(user)}


@app.get("/api/users/me")
def me(user: dict[str, Any] = Depends(get_current_user)) -> dict[str, Any]:
    return user


@app.put("/api/users/me")
def update_me(
    request: UpdateUserRequest,
    user: dict[str, Any] = Depends(get_current_user),
) -> dict[str, Any]:
    next_email = validate_email(request.email) if request.email is not None else user["email"]
    next_name = request.name.strip() if request.name is not None else user["name"]

    if not next_name:
        raise HTTPException(status_code=400, detail="Name is required")

    with connect() as conn:
        try:
            conn.execute(
                """
                UPDATE users
                SET name = ?, email = ?, age = ?, height_cm = ?, weight_kg = ?, gender = ?,
                    max_heart_rate = ?, resting_heart_rate = ?, ftp_watts = ?
                WHERE id = ?
                """,
                (
                    next_name,
                    next_email,
                    request.age,
                    request.height_cm,
                    request.weight_kg,
                    (request.gender or "").strip() or None,
                    request.max_heart_rate,
                    request.resting_heart_rate,
                    request.ftp_watts,
                    user["id"],
                ),
            )
        except sqlite3.IntegrityError:
            raise HTTPException(status_code=409, detail="Email already exists") from None

        updated = conn.execute("SELECT * FROM users WHERE id = ?", (user["id"],)).fetchone()
        conn.commit()

    return user_response(updated)


@app.get("/api/users/me/sessions")
def my_sessions(user: dict[str, Any] = Depends(get_current_user)) -> list[dict[str, Any]]:
    return load_sessions_for_user(user["id"])


@app.get("/api/users/me/email-summary-data")
def email_summary_data(user: dict[str, Any] = Depends(get_current_user)) -> dict[str, Any]:
    # Existing email summary code should use this same user_id filter, not a global
    # sessions query, so one athlete is never compared against another athlete.
    return {
        "user": user,
        "previous_sessions": load_sessions_for_user(user["id"], limit=20),
    }


@app.post("/api/sessions")
def create_session(
    request: SessionRequest,
    user: dict[str, Any] = Depends(get_current_user),
) -> dict[str, Any]:
    # This endpoint is optional for the Android app today because workout commands still
    # flow over MQTT. It mirrors the same user linkage expected in the MQTT backend.
    with connect() as conn:
        columns = table_columns(conn, session_table)
        values: dict[str, Any] = {
            "user_id": user["id"],
            "session_id": request.session_id,
            "device_id": request.device_id,
            "workout_type": request.workout_type,
            "status": request.status or "started",
            "started_at": utc_now(),
            "created_at": utc_now(),
        }
        insert_values = {key: value for key, value in values.items() if key in columns}
        names = list(insert_values.keys())
        placeholders = ", ".join("?" for _ in names)
        quoted_names = ", ".join(quote_identifier(name) for name in names)
        cursor = conn.execute(
            f"INSERT INTO {quote_identifier(session_table)} ({quoted_names}) VALUES ({placeholders})",
            tuple(insert_values[name] for name in names),
        )
        conn.commit()
        row = conn.execute(
            f"SELECT * FROM {quote_identifier(session_table)} WHERE rowid = ?",
            (cursor.lastrowid,),
        ).fetchone()

    return dict(row)


def load_sessions_for_user(user_id: int, limit: int | None = None) -> list[dict[str, Any]]:
    with connect() as conn:
        order_column = first_existing_column(
            conn,
            session_table,
            ("started_at", "created_at", "timestamp", "id"),
        )
        limit_sql = "" if limit is None else " LIMIT ?"
        params: tuple[Any, ...] = (user_id,) if limit is None else (user_id, limit)
        rows = conn.execute(
            f"""
            SELECT *
            FROM {quote_identifier(session_table)}
            WHERE user_id = ?
            ORDER BY {quote_identifier(order_column)} DESC
            {limit_sql}
            """,
            params,
        ).fetchall()

    return [dict(row) for row in rows]


def first_existing_column(
    conn: sqlite3.Connection,
    table: str,
    candidates: tuple[str, ...],
) -> str:
    columns = table_columns(conn, table)
    for candidate in candidates:
        if candidate in columns:
            return candidate
    return "rowid"


def resolve_user_id_from_command_payload(payload: dict[str, Any]) -> int | None:
    """Use this helper from the existing MQTT command handler.

    New Android start/stop commands include both user_id and token. Prefer the token
    because it proves which athlete is logged in; keep user_id as a fallback for old
    prototype scripts that may not validate tokens yet.
    """

    token = str(payload.get("token") or "").strip()
    if token:
        with connect() as conn:
            row = conn.execute(
                "SELECT user_id FROM auth_tokens WHERE token = ?",
                (token,),
            ).fetchone()
        if row is not None:
            return int(row["user_id"])

    user_id = payload.get("user_id")
    try:
        return int(user_id) if user_id is not None else None
    except (TypeError, ValueError):
        return None
