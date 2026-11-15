#!/usr/bin/env python3
"""本地 mock 全流程患者阶梯，方便驾驶舱 / 案头验证。

默认登录 opsadmin，切到「综合健康管理」，在「减重一组」造一批带前缀「全流程·」的患者：

  01待认领   — 仅建档，公共池
  02待首诊   — 已入组，首诊待办未逾期
  03首诊逾期 — 已入组，首诊 due 提前 7 天
  04待制定方案 — 已入组并提交一份首诊答卷（可直接去生成方案）

可重复执行：会先软删同名前缀旧档案再重建。

用法：
  python3 scripts/seed-flow-patients.py
  python3 scripts/seed-flow-patients.py --base-url http://127.0.0.1:8080
  HEALIX_B_USER=opsadmin HEALIX_B_PASS='OpsAdmin123!' python3 scripts/seed-flow-patients.py
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta
from typing import Any

MYSQL = [
    "/usr/local/mysql/bin/mysql",
    "-uhealix",
    "-phealix",
    "healix",
    "--default-character-set=utf8mb4",
]

DEFAULT_TENANT = "SVKISVIUB8BSDZXTE7IDFQW30FESIB58"  # 健康管理
DEFAULT_ORG = "QUKISXD2F89KPOLILLIOH7GOC6UFTCNF"  # 综合健康管理
DEFAULT_TEAM = "27M6YX2J00838LL07NYBML0CHZJ82U78"  # 减重一组
NAME_PREFIX = "全流程·"
GMT_LIVE = "9999-12-31 23:59:59"


class Api:
    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.token: str | None = None

    def _req(self, method: str, path: str, body: Any | None = None) -> dict:
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        req = urllib.request.Request(
            self.base + path, data=data, headers=headers, method=method
        )
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                raw = resp.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8")
            raise SystemExit(f"HTTP {e.code} {path}: {raw[:800]}") from e
        except urllib.error.URLError as e:
            raise SystemExit(f"无法连接后端 {self.base}: {e}") from e
        try:
            payload = json.loads(raw) if raw else {}
        except json.JSONDecodeError as e:
            raise SystemExit(f"非 JSON 响应 {path}: {raw[:400]}") from e
        if payload.get("code") not in (0, "0", None):
            raise SystemExit(f"接口失败 {path}: {payload.get('message') or payload}")
        return payload

    def login(self, username: str, password: str, tenant_id: str) -> None:
        res = self._req(
            "POST",
            "/api/b/v1/auth/login",
            {"username": username, "password": password, "tenantId": tenant_id},
        )
        token = (res.get("data") or {}).get("accessToken")
        if not token:
            raise SystemExit(f"登录未拿到 token: {res}")
        self.token = token

    def switch_org(self, org_id: str) -> None:
        res = self._req("PUT", "/api/b/v1/session/org", {"orgId": org_id})
        token = (res.get("data") or {}).get("accessToken")
        if not token:
            raise SystemExit(f"切机构失败: {res}")
        self.token = token


def mysql_exec(sql: str) -> None:
    p = subprocess.run(MYSQL, input=sql.encode("utf-8"), capture_output=True)
    if p.returncode != 0:
        err = (p.stderr or p.stdout or b"").decode()
        raise SystemExit(err[-3000:] or "mysql exec failed")


def mysql_query(sql: str) -> str:
    p = subprocess.run(
        MYSQL + ["-N", "-B", "-e", sql],
        capture_output=True,
        text=True,
    )
    if p.returncode != 0:
        raise SystemExit((p.stderr or p.stdout or "mysql query failed")[-3000:])
    return p.stdout


def soft_delete_prior_mocks() -> None:
    """软删此前种子（按姓名前缀），避免重复刷屏。"""
    ids = [
        line.strip()
        for line in mysql_query(
            f"SELECT id FROM people_profile "
            f"WHERE display_name LIKE '{NAME_PREFIX}%' AND is_deleted=0"
        ).splitlines()
        if line.strip()
    ]
    if not ids:
        print("无旧 mock 需清理")
        return
    in_list = ",".join(f"'{i}'" for i in ids)
    mysql_exec(
        f"""
SET NAMES utf8mb4;
UPDATE people_profile SET is_deleted=1, gmt_deleted=NOW(3)
 WHERE id IN ({in_list}) AND is_deleted=0;
UPDATE people_org_membership SET is_deleted=1, gmt_deleted=NOW(3)
 WHERE people_id IN ({in_list}) AND is_deleted=0;
UPDATE care_team_member SET is_deleted=1, gmt_deleted=NOW(3)
 WHERE people_id IN ({in_list}) AND is_deleted=0;
UPDATE workspace_task SET is_deleted=1, gmt_deleted=NOW(3)
 WHERE people_id IN ({in_list}) AND is_deleted=0;
UPDATE followup_record SET is_deleted=1, gmt_deleted=NOW(3)
 WHERE people_id IN ({in_list}) AND is_deleted=0;
UPDATE questionnaire_response SET is_deleted=1, gmt_deleted=NOW(3)
 WHERE people_id IN ({in_list}) AND is_deleted=0;
"""
    )
    print(f"已软删旧 mock {len(ids)} 人")


def create_archive(api: Api, name: str, mobile_tail: int, gender: str, birthday: str) -> str:
    mobile = f"1398{mobile_tail:07d}"
    res = api._req(
        "POST",
        "/api/b/v1/patients/archives",
        {
            "name": name,
            "gender": gender,
            "birthday": birthday,
            "noMobile": True,
            "mobile": mobile,
            "mobileOwner": "CHILD",
        },
    )
    pid = (res.get("data") or {}).get("peopleId")
    if not pid:
        raise SystemExit(f"建档失败 {name}: {res}")
    return pid


def assign_team(api: Api, people_id: str, team_id: str) -> None:
    api._req(
        "PUT",
        f"/api/b/v1/patients/{people_id}/care-team",
        {"careTeamId": team_id},
    )


def bump_intake_overdue(people_id: str, days: int = 7) -> None:
    due = (datetime.now() - timedelta(days=days)).strftime("%Y-%m-%d 23:59:59")
    mysql_exec(
        f"""
UPDATE workspace_task
   SET due_at='{due}', gmt_modified=NOW(3)
 WHERE people_id='{people_id}' AND is_deleted=0
   AND status='OPEN' AND task_type='FOLLOW_UP'
   AND title LIKE '%首诊%';
UPDATE followup_record
   SET due_at='{due}', gmt_modified=NOW(3)
 WHERE people_id='{people_id}' AND is_deleted=0 AND status='OPEN'
   AND JSON_UNQUOTE(JSON_EXTRACT(content_json, '$.followupType'))='ONBOARDING';
"""
    )


def latest_intake_answers_template() -> dict:
    raw = mysql_query(
        """
SELECT answers_json FROM questionnaire_response
 WHERE is_deleted=0 AND status='SUBMITTED' AND purpose='INTAKE'
   AND answers_json IS NOT NULL
 ORDER BY gmt_created DESC LIMIT 1
"""
    ).strip()
    if not raw:
        raise SystemExit("库中没有已提交的首诊答卷可作模板，请先手工填一份")
    return json.loads(raw)


def submit_intake_from_template(api: Api, people_id: str, display_name: str) -> None:
    answers = latest_intake_answers_template()
    # 覆盖档案相关字段，避免串到模板患者姓名
    answers["profile.displayName"] = display_name
    answers["profile.mobile"] = answers.get("profile.mobile") or "13980009999"
    opened = api._req("POST", f"/api/b/v1/patients/{people_id}/intake/open", {})
    rid = (opened.get("data") or {}).get("id")
    if not rid:
        raise SystemExit(f"打开首诊失败 {people_id}: {opened}")
    api._req(
        "PUT",
        f"/api/b/v1/questionnaire-responses/{rid}/draft",
        {"answers": answers, "channel": "STAFF"},
    )
    api._req(
        "POST",
        f"/api/b/v1/questionnaire-responses/{rid}/submit",
        {"forceIncomplete": True},
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Mock 全流程患者阶梯")
    parser.add_argument("--base-url", default=os.environ.get("HEALIX_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--username", default=os.environ.get("HEALIX_B_USER", "opsadmin"))
    parser.add_argument("--password", default=os.environ.get("HEALIX_B_PASS", "OpsAdmin123!"))
    parser.add_argument("--tenant-id", default=os.environ.get("HEALIX_TENANT_ID", DEFAULT_TENANT))
    parser.add_argument("--org-id", default=os.environ.get("HEALIX_ORG_ID", DEFAULT_ORG))
    parser.add_argument("--team-id", default=os.environ.get("HEALIX_TEAM_ID", DEFAULT_TEAM))
    parser.add_argument("--keep-old", action="store_true", help="不软删旧的「全流程·」患者")
    args = parser.parse_args()

    if not args.keep_old:
        soft_delete_prior_mocks()

    api = Api(args.base_url)
    print(f"登录 {args.username} @ {args.base_url}")
    api.login(args.username, args.password, args.tenant_id)
    api.switch_org(args.org_id)
    print(f"机构 {args.org_id} / 健管组 {args.team_id}")

    stamp = date.today().strftime("%m%d")
    # mobile_tail 用日期区分，降低撞号
    base_tail = int(f"8{stamp}")  # e.g. 81115

    cohort: list[tuple[str, str, str]] = []

    # 01 公共池（建档后若误开首诊，清掉，保持「只待认领」）
    name = f"{NAME_PREFIX}01待认领"
    pid = create_archive(api, name, base_tail + 1, "FEMALE", "1992-04-18")
    mysql_exec(
        f"""
UPDATE workspace_task SET is_deleted=1, gmt_deleted=NOW(3), status='CANCELLED'
 WHERE people_id='{pid}' AND is_deleted=0 AND title LIKE '%首诊%';
UPDATE followup_record SET is_deleted=1, gmt_deleted=NOW(3), status='CANCELLED'
 WHERE people_id='{pid}' AND is_deleted=0
   AND JSON_UNQUOTE(JSON_EXTRACT(content_json, '$.followupType'))='ONBOARDING';
"""
    )
    cohort.append(("公共池待认领", name, pid))
    print(f"✓ {name}  {pid}")

    # 02 待首诊
    name = f"{NAME_PREFIX}02待首诊"
    pid = create_archive(api, name, base_tail + 2, "MALE", "1985-09-03")
    assign_team(api, pid, args.team_id)
    cohort.append(("已入组·待首诊", name, pid))
    print(f"✓ {name}  {pid}")

    # 03 首诊逾期
    name = f"{NAME_PREFIX}03首诊逾期"
    pid = create_archive(api, name, base_tail + 3, "FEMALE", "1978-12-21")
    assign_team(api, pid, args.team_id)
    bump_intake_overdue(pid, days=7)
    cohort.append(("已入组·首诊逾期7天", name, pid))
    print(f"✓ {name}  {pid}")

    # 04 待制定方案
    name = f"{NAME_PREFIX}04待制定方案"
    pid = create_archive(api, name, base_tail + 4, "MALE", "1988-07-09")
    assign_team(api, pid, args.team_id)
    try:
        submit_intake_from_template(api, pid, name)
        cohort.append(("首诊已交·可制定方案", name, pid))
        print(f"✓ {name}  {pid}  （已提交首诊）")
    except SystemExit as e:
        cohort.append(("已入组·首诊提交失败", name, pid))
        print(f"✗ {name} 首诊提交失败：{e}", file=sys.stderr)

    print()
    print("======== 验证入口 ========")
    print("管理端驾驶舱 → 我的患者 / 立即处理，搜索「全流程」")
    for stage, name, pid in cohort:
        print(f"  · {stage:18s}  {name}  peopleId={pid}")
    print()
    print("建议点验顺序：")
    print("  1) 01待认领 → 公共池横幅 / 未分组 → 分配到减重一组")
    print("  2) 02待首诊 → 打开首诊代填")
    print("  3) 03首诊逾期 → 应出现逾期引导气泡")
    print("  4) 04待制定方案 → 审阅方案 / 去生成方案")


if __name__ == "__main__":
    main()
