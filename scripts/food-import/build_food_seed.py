#!/usr/bin/env python3
"""生成饮食记录食物库种子：backend/health-app/src/main/resources/seed/food_items.json

数据源：
  - https://github.com/Sanotsu/china-food-composition-data
    《中国食物成分表标准版（第 6 版）》能量和一般营养成分（人工矫正版）+ GI 表
    仅限非商用；商用前需取得授权或替换数据源（见 docs/饮食记录-产品与技术方案.md §10）
  - food_data.py：补充食材、家常菜菜谱、家常单位、GI 映射

用法：
  git clone --depth 1 --filter=blob:none --sparse \
      https://github.com/Sanotsu/china-food-composition-data.git /tmp/cfcd
  (cd /tmp/cfcd && git sparse-checkout set \
      json_data_v3_20260825_qwen38max_kimi_k3_fixed_en json_gi_of_foods)
  python3 scripts/food-import/build_food_seed.py --src /tmp/cfcd
"""

import argparse
import glob
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import food_data as fd  # noqa: E402

DATA_DIR = "json_data_v3_20260825_qwen38max_kimi_k3_fixed_en"
GI_FILE = "json_gi_of_foods/glycemic_index_of_foods.json"
SOURCE_CFCT = "CFCT6_OPEN"  # 《中国食物成分表》第 6 版开源整理
SOURCE_SELF = "SELF_CURATED"

CATEGORY_MAP = {
    "谷类及其制品": "STAPLE",
    "薯类淀粉及其制品": "STAPLE",
    "干豆类及其制品": "SOY",
    "蔬菜类及其制品": "VEGETABLE",
    "菌藻类": "VEGETABLE",
    "水果类及其制品": "FRUIT",
    "坚果种子类": "NUT",
    "畜肉类及其制品": "MEAT",
    "禽肉类及其制品": "MEAT",
    "乳类及其制品": "DAIRY",
    "蛋类及其制品": "EGG",
    "鱼虾蟹贝类": "AQUATIC",
    "植物油": "OIL",
    "动物油脂类": "OIL",
}
SKIP_CATEGORIES = {"其他类"}  # 药材类（乌梢蛇、阿胶等），非日常饮食


def num(v):
    if v is None:
        return None
    s = str(v).strip()
    if s in ("", "—", "-", "…", "..."):
        return None
    if s.lower() == "tr":
        return 0.0
    s = re.sub(r"[^0-9.]", "", s)
    if not s:
        return None
    try:
        return float(s)
    except ValueError:
        return None


def r2(v):
    return None if v is None else round(v, 2)


def clean_name(raw):
    """「番茄［西红柿］」→ 名称 番茄 + 别名 西红柿；「鸡蛋（代表值）」→ 鸡蛋。"""
    aliases = []
    for m in re.findall(r"［([^］]*)］", raw):
        aliases += [a.strip() for a in re.split(r"[，、,]", m) if a.strip()]
    name = re.sub(r"［[^］]*］", "", raw).strip()

    def fix_paren(m):
        parts = [p.strip() for p in re.split(r"[，,]", m.group(1))]
        parts = [p for p in parts if p and p != "代表值" and not re.fullmatch(r"fat\d+g", p)]
        return "（" + "，".join(parts) + "）" if parts else ""

    name = re.sub(r"（([^）]*)）", fix_paren, name).strip()
    return name, aliases


def energy_mismatch(kcal, p, f, c, fiber):
    if None in (kcal, p, f, c) or kcal < 20:
        return False
    fiber = fiber or 0
    est = 4 * p + 9 * f + 4 * max(c - fiber, 0) + 2 * fiber
    return abs(est - kcal) / kcal > 0.15


def derive_tags(name, category, sodium):
    tags = []
    if sodium is not None and sodium >= 800:
        tags.append("HIGH_SALT")
    if any(k in name for k in ("腌", "腊", "咸", "榨菜", "酱菜", "泡菜", "酸菜")):
        tags.append("PICKLED")
    if "炸" in name or "油条" in name:
        tags.append("FRIED")
    if category == "MEAT" and any(k in name for k in ("香肠", "火腿", "培根", "午餐肉")):
        tags.append("PROCESSED_MEAT")
    return tags


def gi_level(gi):
    if gi is None:
        return None
    if gi <= 55:
        return "LOW"
    if gi < 70:
        return "MID"
    return "HIGH"


def load_cfct(src):
    foods = []
    for path in sorted(glob.glob(os.path.join(src, DATA_DIR, "merged_*.json"))):
        cat_full = os.path.basename(path)[len("merged_"):-len(".json")]
        main_cat = cat_full.split("-")[0]
        if main_cat in SKIP_CATEGORIES:
            continue
        category = CATEGORY_MAP.get(main_cat, "OTHER")
        for row in json.load(open(path, encoding="utf-8")):
            kcal = num(row.get("energyKCal"))
            if kcal is None:
                continue
            name, aliases = clean_name(row["foodName"])
            p, f, c = num(row.get("protein")), num(row.get("fat")), num(row.get("CHO"))
            fiber, na = num(row.get("dietaryFiber")), num(row.get("Na"))
            code = row["foodCode"].strip()
            foods.append({
                "code": code,
                "name": name,
                "aliases": aliases,
                "kind": "INGREDIENT",
                "category": category,
                "subCategory": cat_full,
                "energyKcal": r2(kcal),
                "proteinG": r2(p),
                "fatG": r2(f),
                "carbG": r2(c),
                "fiberG": r2(fiber),
                "sodiumMg": r2(na),
                "ediblePct": num(row.get("edible")),
                "isDefault": "代表值" in row["foodName"],
                "tags": derive_tags(name, category, na),
                "dataSource": SOURCE_CFCT,
                "precisionLevel": "A",
                "needsReview": energy_mismatch(kcal, p, f, c, fiber),
            })
    return foods


def supplement_foods():
    out = []
    for code, name, aliases, cat, kcal, p, f, c, fiber, na, tags in fd.SUPPLEMENTS:
        out.append({
            "code": code,
            "name": name,
            "aliases": aliases,
            "kind": "INGREDIENT",
            "category": cat,
            "subCategory": None,
            "energyKcal": kcal,
            "proteinG": p,
            "fatG": f,
            "carbG": c,
            "fiberG": fiber,
            "sodiumMg": na,
            "ediblePct": 100,
            "isDefault": True,
            "tags": tags,
            "dataSource": SOURCE_SELF,
            "precisionLevel": "B",
            "needsReview": False,
        })
    return out


def dish_foods(by_code):
    out = []
    fields = ("energyKcal", "proteinG", "fatG", "carbG", "fiberG", "sodiumMg")
    for code, name, aliases, recipe, units in fd.DISHES:
        total_g = 0.0
        totals = {k: 0.0 for k in fields}
        tags = set()
        for ing_code, grams, _ in recipe:
            ing = by_code.get(ing_code)
            if ing is None:
                raise SystemExit(f"菜谱 {name} 引用了不存在的食材 {ing_code}")
            total_g += grams
            for k in fields:
                totals[k] += (ing.get(k) or 0) * grams / 100
            tags.update(t for t in ing["tags"] if t in ("SUGARY_DRINK", "PROCESSED_MEAT"))
        per100 = {k: round(v * 100 / total_g, 2) for k, v in totals.items()}
        if per100["sodiumMg"] >= 800:
            tags.add("HIGH_SALT")
        if per100["fatG"] >= 15:
            tags.add("HIGH_FAT")
        serving = round(total_g)
        unit_list = [{"unit": u, "grams": g if g is not None else serving} for u, g in units]
        if not any(u["unit"] == "份" for u in unit_list):
            unit_list.append({"unit": "份", "grams": serving})
        out.append({
            "code": code,
            "name": name,
            "aliases": aliases,
            "kind": "DISH",
            "category": "DISH",
            "subCategory": None,
            **per100,
            "ediblePct": 100,
            "isDefault": True,
            "tags": sorted(tags),
            "dataSource": SOURCE_SELF,
            "precisionLevel": "B",
            "needsReview": False,
            "servingGrams": serving,
            "units": unit_list,
            "recipe": [
                {"code": c, "grams": g, "oilAdjustable": bool(oil or c in (fd.OIL, fd.SESAME_OIL))}
                for c, g, oil in recipe
            ],
        })
    return out


def apply_gi(foods, src):
    by_code = {f["code"]: f for f in foods}
    gi_by_name = {}
    for group in json.load(open(os.path.join(src, GI_FILE), encoding="utf-8")):
        for item in group["list"]:
            gi_by_name[item["foodName"].strip()] = item["GI"]
    missing = []
    for gi_name, codes in fd.GI_MAP.items():
        gi = gi_by_name.get(gi_name)
        if gi is None:
            missing.append(gi_name)
            continue
        for code in codes:
            if code in by_code:
                by_code[code]["gi"] = gi
                by_code[code]["giLevel"] = gi_level(gi)
    if missing:
        print("GI 表未找到：", missing, file=sys.stderr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="china-food-composition-data 本地路径")
    ap.add_argument(
        "--out",
        default=os.path.join(
            os.path.dirname(__file__), "../../backend/health-app/src/main/resources/seed/food_items.json"),
    )
    args = ap.parse_args()

    foods = load_cfct(args.src) + supplement_foods()
    by_code = {f["code"]: f for f in foods}
    if len(by_code) != len(foods):
        raise SystemExit("foodCode 重复")
    foods += dish_foods(by_code)
    by_code = {f["code"]: f for f in foods}

    for code, extra in fd.ALIASES.items():
        f = by_code[code]
        f["aliases"] = list(dict.fromkeys(f["aliases"] + [a for a in extra if a != f["name"]]))
    for code, units in fd.UNITS.items():
        f = by_code[code]
        f.setdefault("units", [])
        f["units"] += [{"unit": u, "grams": g} for u, g in units]
    apply_gi(foods, args.src)

    for f in foods:
        f.setdefault("units", [])
        f.setdefault("gi", None)
        f.setdefault("giLevel", None)
        f.setdefault("servingGrams", None)
        f.setdefault("recipe", [])

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fp:
        json.dump({"version": "2026-10-10.2", "foods": foods}, fp, ensure_ascii=False, separators=(",", ":"))

    kinds = {}
    for f in foods:
        kinds[f["kind"]] = kinds.get(f["kind"], 0) + 1
    print(f"写入 {len(foods)} 条：{kinds}；待复核 {sum(f['needsReview'] for f in foods)} 条；"
          f"有单位 {sum(1 for f in foods if f['units'])} 条；有 GI {sum(1 for f in foods if f['gi'])} 条")
    print("输出：", os.path.abspath(args.out))


if __name__ == "__main__":
    main()
