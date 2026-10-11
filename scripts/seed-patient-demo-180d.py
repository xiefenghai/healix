#!/usr/bin/env python3
"""Seed ~180 days of metrics / med intakes / care-plan checkins for a demo patient.

Usage:
  python3 scripts/seed-patient-demo-180d.py [people_id]

Re-runnable: soft-deletes prior SEED_DEMO_180D rows (and overlapping checkins/intakes)
before inserting a fresh batch.
"""
from __future__ import annotations

import os
import random
import secrets
import string
import subprocess
import sys
from datetime import date, datetime, timedelta, time

MYSQL = [
    "/usr/local/mysql/bin/mysql",
    "-uhealix",
    "-phealix",
    "healix",
    "--default-character-set=utf8mb4",
]
ALPH = string.digits + string.ascii_uppercase
NOTE = "SEED_DEMO_180D"
GMT_LIVE = "9999-12-31 23:59:59"
DAYS = 180
# Align with current product demo timeline in local DB
TODAY = date(2026, 10, 10)
START = TODAY - timedelta(days=DAYS - 1)

DEFAULT_PEOPLE = "U4KIT1D99F3HK1XMLF0N8ZKKDSK4L544"


def sid() -> str:
    return "".join(secrets.choice(ALPH) for _ in range(32))


def esc(s) -> str:
    if s is None:
        return "NULL"
    return "'" + str(s).replace("\\", "\\\\").replace("'", "''") + "'"


def mysql_query(sql: str) -> str:
    p = subprocess.run(
        MYSQL + ["-N", "-B", "-e", sql],
        capture_output=True,
        text=True,
    )
    if p.returncode != 0:
        raise SystemExit(p.stderr or p.stdout or "mysql query failed")
    return p.stdout


def mysql_exec(sql: str) -> None:
    p = subprocess.run(MYSQL, input=sql.encode("utf-8"), capture_output=True)
    if p.returncode != 0:
        err = (p.stderr or p.stdout or b"").decode()
        raise SystemExit(err[-4000:] or "mysql exec failed")


def jitter(base: float, amp: float, day_i: int) -> float:
    return base + amp * (0.3 * (day_i / DAYS) - 0.1) + random.uniform(-amp, amp)


def main() -> None:
    people_id = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PEOPLE
    random.seed(42)

    profile = mysql_query(
        f"SELECT tenant_id FROM people_profile "
        f"WHERE id={esc(people_id)} AND is_deleted=0 LIMIT 1"
    ).strip()
    if not profile:
        raise SystemExit(f"people not found: {people_id}")
    tenant_id = profile

    org_id = mysql_query(
        f"SELECT org_id FROM people_org_membership "
        f"WHERE people_id={esc(people_id)} AND is_deleted=0 "
        f"ORDER BY gmt_modified DESC LIMIT 1"
    ).strip()
    if not org_id:
        org_id = mysql_query(
            f"SELECT org_id FROM care_plan "
            f"WHERE people_id={esc(people_id)} AND is_deleted=0 "
            f"ORDER BY gmt_modified DESC LIMIT 1"
        ).strip() or None

    plan = mysql_query(
        f"SELECT p.id, p.current_version_id FROM care_plan p "
        f"WHERE p.people_id={esc(people_id)} AND p.status='ACTIVE' AND p.is_deleted=0 "
        f"ORDER BY p.gmt_modified DESC LIMIT 1"
    ).strip()
    if not plan:
        raise SystemExit(f"no ACTIVE care plan for {people_id}")
    plan_id, plan_version_id = plan.split("\t")

    tasks = []
    for line in mysql_query(
        f"SELECT id, task_code, time_slot, frequency FROM care_plan_task "
        f"WHERE plan_version_id={esc(plan_version_id)} AND is_deleted=0"
    ).strip().splitlines():
        tid, code, slot, freq = line.split("\t")
        tasks.append((tid, code, slot, freq))
    if not tasks:
        raise SystemExit(f"no tasks on plan version {plan_version_id}")

    meds = []
    for line in mysql_query(
        f"SELECT id, drug_name, frequency FROM people_medication "
        f"WHERE people_id={esc(people_id)} AND status='ACTIVE' AND is_deleted=0"
    ).strip().splitlines():
        mid, drug, freq = line.split("\t")
        meds.append((mid, drug, freq if freq != "NULL" else "QD"))

    print(f"people={people_id} tenant={tenant_id} org={org_id}")
    print(f"plan={plan_id} version={plan_version_id} tasks={len(tasks)} meds={len(meds)}")
    print(f"window={START} .. {TODAY} ({DAYS}d)")

    sql: list[str] = ["SET NAMES utf8mb4;", "START TRANSACTION;"]

    # Soft-delete prior seed + overlapping unique keys (gmt_deleted must change)
    sql.append(
        f"UPDATE vital_record SET is_deleted=1, gmt_deleted=NOW(3) "
        f"WHERE people_id={esc(people_id)} AND note={esc(NOTE)} AND is_deleted=0;"
    )
    sql.append(
        f"UPDATE care_plan_task_checkin SET is_deleted=1, gmt_deleted=NOW(3) "
        f"WHERE people_id={esc(people_id)} AND note={esc(NOTE)} AND is_deleted=0;"
    )
    sql.append(
        f"UPDATE people_medication_intake SET is_deleted=1, gmt_deleted=NOW(3) "
        f"WHERE people_id={esc(people_id)} AND note={esc(NOTE)} AND is_deleted=0;"
    )

    task_ids = ",".join(esc(t[0]) for t in tasks)
    sql.append(
        f"UPDATE care_plan_task_checkin SET is_deleted=1, gmt_deleted=NOW(3) "
        f"WHERE people_id={esc(people_id)} AND task_id IN ({task_ids}) "
        f"AND checkin_date BETWEEN {esc(START.isoformat())} AND {esc(TODAY.isoformat())} "
        f"AND is_deleted=0;"
    )
    if meds:
        med_ids = ",".join(esc(m[0]) for m in meds)
        sql.append(
            f"UPDATE people_medication_intake SET is_deleted=1, gmt_deleted=NOW(3) "
            f"WHERE people_id={esc(people_id)} AND medication_id IN ({med_ids}) "
            f"AND intake_date BETWEEN {esc(START.isoformat())} AND {esc(TODAY.isoformat())} "
            f"AND is_deleted=0;"
        )
        sql.append(
            f"UPDATE people_medication SET start_date={esc(START.isoformat())} "
            f"WHERE people_id={esc(people_id)} AND status='ACTIVE' AND is_deleted=0;"
        )

    vital_vals: list[str] = []
    checkin_vals: list[str] = []
    intake_vals: list[str] = []

    for i in range(DAYS):
        d = START + timedelta(days=i)

        if random.random() < 0.88:
            sys_v = max(105.0, min(165.0, round(jitter(132, 12, i), 1)))
            dia_v = max(60.0, min(105.0, round(jitter(82, 8, i), 1)))
            ts = datetime.combine(d, time(8, random.randint(0, 40))).strftime("%Y-%m-%d %H:%M:%S")
            gid = sid()
            extra = '{"bpContext":"HOME"}'
            vital_vals.append(
                f"({esc(sid())},{esc(tenant_id)},{esc(people_id)},{esc(org_id)},"
                f"'BLOOD_PRESSURE_SYS',{sys_v},'mmHg',{esc(ts)},'HOME',{esc(gid)},"
                f"{esc(NOTE)},{esc(extra)},0,NOW(3),NOW(3),{esc(GMT_LIVE)})"
            )
            vital_vals.append(
                f"({esc(sid())},{esc(tenant_id)},{esc(people_id)},{esc(org_id)},"
                f"'BLOOD_PRESSURE_DIA',{dia_v},'mmHg',{esc(ts)},'HOME',{esc(gid)},"
                f"{esc(NOTE)},{esc(extra)},0,NOW(3),NOW(3),{esc(GMT_LIVE)})"
            )

        if random.random() < 0.72:
            ctx = random.choices(
                ["FASTING", "POSTPRANDIAL", "RANDOM"], weights=[0.45, 0.4, 0.15]
            )[0]
            if ctx == "FASTING":
                g = round(jitter(6.8, 1.2, i), 1)
                hh, mm = 7, random.randint(10, 50)
            elif ctx == "POSTPRANDIAL":
                g = round(jitter(9.2, 2.0, i), 1)
                hh, mm = 14, random.randint(0, 40)
            else:
                g = round(jitter(8.0, 1.8, i), 1)
                hh, mm = 19, random.randint(0, 50)
            g = max(4.5, min(16.0, g))
            ts = datetime.combine(d, time(hh, mm)).strftime("%Y-%m-%d %H:%M:%S")
            extra = f'{{"mealContext":"{ctx}"}}'
            vital_vals.append(
                f"({esc(sid())},{esc(tenant_id)},{esc(people_id)},{esc(org_id)},"
                f"'BLOOD_GLUCOSE',{g},'mmol/L',{esc(ts)},'HOME',NULL,"
                f"{esc(NOTE)},{esc(extra)},0,NOW(3),NOW(3),{esc(GMT_LIVE)})"
            )

        if random.random() < 0.4:
            hr = max(52, min(110, int(round(jitter(74, 10, i)))))
            ts = datetime.combine(d, time(12, random.randint(0, 30))).strftime(
                "%Y-%m-%d %H:%M:%S"
            )
            vital_vals.append(
                f"({esc(sid())},{esc(tenant_id)},{esc(people_id)},{esc(org_id)},"
                f"'HEART_RATE',{hr},'bpm',{esc(ts)},'HOME',NULL,"
                f"{esc(NOTE)},NULL,0,NOW(3),NOW(3),{esc(GMT_LIVE)})"
            )

        if d.weekday() in (0, 3) or i == 0:
            w = max(68.0, min(78.0, round(jitter(72.5, 1.5, i), 1)))
            ts = datetime.combine(d, time(7, 5)).strftime("%Y-%m-%d %H:%M:%S")
            vital_vals.append(
                f"({esc(sid())},{esc(tenant_id)},{esc(people_id)},{esc(org_id)},"
                f"'WEIGHT',{w},'kg',{esc(ts)},'HOME',NULL,"
                f"{esc(NOTE)},NULL,0,NOW(3),NOW(3),{esc(GMT_LIVE)})"
            )

        for tid, _code, slot, _freq in tasks:
            r = random.random()
            if r < 0.08:
                continue
            status = "DONE" if r < 0.90 else "SKIPPED"
            checkin_vals.append(
                f"({esc(sid())},{esc(tenant_id)},{esc(people_id)},{esc(plan_id)},"
                f"{esc(plan_version_id)},{esc(tid)},{esc(d.isoformat())},{esc(slot)},"
                f"{esc(status)},{esc(NOTE)},NULL,NULL,0,NOW(3),NOW(3),{esc(GMT_LIVE)})"
            )

        for mid, _drug, freq in meds:
            slots = ["MORNING", "NOON", "EVENING"] if freq == "TID" else ["MORNING"]
            for sl in slots:
                r = random.random()
                if r < 0.07:
                    st = "MISSED"
                elif r < 0.12:
                    st = "SKIPPED"
                else:
                    st = "TAKEN"
                intake_vals.append(
                    f"({esc(sid())},{esc(tenant_id)},{esc(people_id)},{esc(mid)},"
                    f"{esc(d.isoformat())},{esc(sl)},{esc(st)},{esc(NOTE)},NULL,"
                    f"0,NOW(3),NOW(3),{esc(GMT_LIVE)})"
                )

    def flush(table: str, cols: str, rows: list[str], batch: int = 200) -> None:
        for i in range(0, len(rows), batch):
            chunk = rows[i : i + batch]
            sql.append(f"INSERT INTO {table} ({cols}) VALUES\n" + ",\n".join(chunk) + ";")

    flush(
        "vital_record",
        "id,tenant_id,people_id,org_id,metric_type,value,unit,recorded_at,source,"
        "group_id,note,extra_json,is_deleted,gmt_created,gmt_modified,gmt_deleted",
        vital_vals,
    )
    flush(
        "care_plan_task_checkin",
        "id,tenant_id,people_id,plan_id,plan_version_id,task_id,checkin_date,time_slot,"
        "status,note,recorded_by_people_id,recorded_by_staff_id,is_deleted,"
        "gmt_created,gmt_modified,gmt_deleted",
        checkin_vals,
    )
    flush(
        "people_medication_intake",
        "id,tenant_id,people_id,medication_id,intake_date,time_slot,status,note,"
        "recorded_by_staff_id,is_deleted,gmt_created,gmt_modified,gmt_deleted",
        intake_vals,
    )

    sql.append("COMMIT;")

    out_sql = os.path.join(os.path.dirname(__file__), "seed-patient-demo-180d.sql")
    body = "\n".join(sql)
    with open(out_sql, "w", encoding="utf-8") as f:
        f.write(body)

    print(
        f"generated vitals={len(vital_vals)} checkins={len(checkin_vals)} "
        f"intakes={len(intake_vals)} -> {out_sql}"
    )
    mysql_exec(body)
    print("loaded ok")

    print(
        mysql_query(
            f"""
SELECT 'vital_seed' t, COUNT(*) c FROM vital_record
 WHERE people_id={esc(people_id)} AND note={esc(NOTE)} AND is_deleted=0
UNION ALL SELECT 'checkin_seed', COUNT(*) FROM care_plan_task_checkin
 WHERE people_id={esc(people_id)} AND note={esc(NOTE)} AND is_deleted=0
UNION ALL SELECT 'intake_seed', COUNT(*) FROM people_medication_intake
 WHERE people_id={esc(people_id)} AND note={esc(NOTE)} AND is_deleted=0;
SELECT metric_type, COUNT(*), MIN(DATE(recorded_at)), MAX(DATE(recorded_at))
  FROM vital_record WHERE people_id={esc(people_id)} AND is_deleted=0
 GROUP BY metric_type ORDER BY metric_type;
"""
        )
    )


if __name__ == "__main__":
    main()
