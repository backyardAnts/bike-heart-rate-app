# Bike Trainer Raspberry Pi API

Small FastAPI service for account login/profile data and user-scoped session lookup.

Run on the Raspberry Pi:

```bash
cd backend
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

By default the API uses `bike_trainer.db` in this folder. To point it at an existing
SQLite database, set:

```bash
export BIKE_TRAINER_DB=/path/to/existing/database.db
```

The migration logic uses `CREATE TABLE IF NOT EXISTS` and only adds `user_id` to an
existing session table when missing. Existing rows are left intact and may keep
`user_id = NULL`.
