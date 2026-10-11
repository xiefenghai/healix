"""食物库补充数据：调味品/饮品等补充食材、家常菜菜谱、家常单位、GI 映射。

数值为每 100g 可食部。补充食材取常见公开参考值，标记 SELF_CURATED / B 级，商用前需复核。
菜谱按「一份」的生料克数编写，营养由食材层计算。
"""

OIL = "192004"  # 豆油（烹调油）
SESAME_OIL = "192017"
SALT = "S_SALT"
SOY = "S_SOY_SAUCE"
SUGAR = "S_SUGAR"
VINEGAR = "S_VINEGAR"
STARCH = "022103"  # 玉米淀粉
WATER = "S_WATER"

EGG = "111101x"
TOMATO = "043119"
PORK = "081101x"
PORK_LEAN = "081110"
PORK_BELLY = "081107"
PORK_RIB = "081131"
CHICKEN = "091101x"
CHICKEN_BREAST = "091112"
CHICKEN_WING = "091114"
BEEF = "082101x"
BEEF_BRAISED = "082301"
POTATO = "021101"
GREEN_PEPPER = "043123"
EGGPLANT = "043101x"
TOFU = "031301x"
TOFU_FIRM = "031306"
TOFU_DRY = "031510x"
CABBAGE = "045101x"
BOK_CHOY = "045120"
RAPE = "045125"
SPINACH = "045301"
BROCCOLI = "045217"
CAULIFLOWER = "045216"
LETTUCE = "045333"
ROUND_CABBAGE = "045201"
SHRIMP = "122106"
GRASS_CARP = "121102"
HAIRTAIL = "121203"
PERCH = "121226"
CUCUMBER = "043208"
WOOD_EAR = "051014"
CELERY = "045331"
CARROT = "041204"
ONION = "044301"
GARLIC = "044101"
SCALLION = "044206"
LEEK = "044404"
GARLIC_SHOOT = "044107"
GREEN_BEAN = "042120"
MUNG_SPROUT = "042206"
WINTER_MELON = "043221"
LOTUS = "046010"
SHIITAKE = "051019"
ENOKI = "051008"
LAVER = "052008"
PEANUT = "072005"
RICE_COOKED = "012401x"
RICE_PORRIDGE = "012404"
FLOUR = "011201x"
NOODLE = "011305x"
RICE_NOODLE = "012410"
VERMICELLI = "022201"
FISH_BALL = "121305"
HAM_SAUSAGE = "081409"
PRESERVED_EGG = "112201"
DOUGH_STICK = "011409"
RADISH = "041101"
COLA = "S_COLA"

# ---------------------------------------------------------------- 补充食材
# code, name, aliases, category, kcal, protein, fat, carb, fiber, sodium_mg, tags
SUPPLEMENTS = [
    (SALT, "食盐", ["盐", "精盐"], "CONDIMENT", 0, 0, 0, 0, 0, 39311, ["HIGH_SALT"]),
    (SOY, "酱油", ["生抽", "老抽"], "CONDIMENT", 63, 5.6, 0.1, 10.1, 0.2, 5757, ["HIGH_SALT"]),
    (VINEGAR, "醋", ["米醋", "陈醋", "香醋"], "CONDIMENT", 31, 2.1, 0.3, 4.9, 0, 262, []),
    (SUGAR, "白砂糖", ["白糖", "砂糖", "绵白糖"], "CONDIMENT", 400, 0, 0, 99.9, 0, 0.4, ["ADDED_SUGAR"]),
    ("S_HONEY", "蜂蜜", [], "CONDIMENT", 321, 0.4, 1.9, 75.6, 0, 0.3, ["ADDED_SUGAR"]),
    (WATER, "水", ["白开水", "矿泉水"], "DRINK", 0, 0, 0, 0, 0, 0, []),
    (COLA, "可乐", ["碳酸饮料", "汽水"], "DRINK", 43, 0.1, 0, 10.8, 0, 4, ["SUGARY_DRINK"]),
    ("S_JUICE_DRINK", "果汁饮料", ["果味饮料"], "DRINK", 45, 0, 0, 11.2, 0, 10, ["SUGARY_DRINK"]),
    ("S_BEER", "啤酒", [], "DRINK", 32, 0.4, 0, 3.0, 0, 4, ["ALCOHOL"]),
    ("S_COFFEE_BLACK", "黑咖啡", ["美式咖啡"], "DRINK", 1, 0.1, 0, 0, 0, 2, []),
    ("S_TEA", "茶水", ["绿茶", "红茶", "茶"], "DRINK", 0, 0, 0, 0, 0, 1, []),
    ("S_BREAD", "面包", ["白面包", "吐司"], "STAPLE", 313, 8.3, 5.1, 58.6, 0.5, 230, []),
    ("S_BISCUIT", "饼干", ["苏打饼干"], "SNACK", 433, 9.0, 12.7, 71.7, 1.1, 204, []),
    ("S_INSTANT_NOODLE", "方便面", ["泡面"], "STAPLE", 473, 9.5, 21.1, 61.6, 0.7, 1144, ["HIGH_SALT", "FRIED"]),
]

# ---------------------------------------------------------------- 家常菜
# code, name, aliases, [(ingredient_code, grams, oil_adjustable)], units(额外, 份 自动生成)
def _o(g):
    return (OIL, g, True)


def _s(g):
    return (SALT, g, False)


DISHES = [
    ("D_TOMATO_EGG", "番茄炒蛋", ["西红柿炒鸡蛋", "西红柿炒蛋"], [(TOMATO, 200, False), (EGG, 100, False), _o(12), _s(2), (SUGAR, 5, False)], [("盘", None)]),
    ("D_POTATO_SHRED", "酸辣土豆丝", ["土豆丝", "炒土豆丝"], [(POTATO, 250, False), (GREEN_PEPPER, 30, False), _o(12), _s(2.5), (VINEGAR, 10, False)], [("盘", None)]),
    ("D_PEPPER_PORK", "青椒肉丝", ["青椒炒肉"], [(GREEN_PEPPER, 150, False), (PORK_LEAN, 100, False), _o(12), _s(2), (SOY, 8, False), (STARCH, 5, False)], [("盘", None)]),
    ("D_YUXIANG_PORK", "鱼香肉丝", [], [(PORK_LEAN, 150, False), (WOOD_EAR, 50, False), (CARROT, 50, False), (GREEN_PEPPER, 50, False), _o(18), _s(2), (SOY, 10, False), (SUGAR, 10, False), (VINEGAR, 10, False), (STARCH, 6, False)], [("盘", None)]),
    ("D_KUNGPAO_CHICKEN", "宫保鸡丁", ["宫爆鸡丁"], [(CHICKEN_BREAST, 200, False), (PEANUT, 30, False), (CUCUMBER, 50, False), _o(18), _s(2), (SOY, 10, False), (SUGAR, 8, False), (VINEGAR, 8, False), (STARCH, 6, False)], [("盘", None)]),
    ("D_MAPO_TOFU", "麻婆豆腐", [], [(TOFU, 300, False), (PORK, 50, False), _o(15), _s(4), (SOY, 8, False), (STARCH, 5, False)], [("盘", None)]),
    ("D_HOME_TOFU", "家常豆腐", ["红烧豆腐"], [(TOFU_FIRM, 300, False), (PORK, 50, False), (GREEN_PEPPER, 50, False), (WOOD_EAR, 30, False), _o(20), (SOY, 10, False), _s(1.5), (STARCH, 5, False)], [("盘", None)]),
    ("D_BRAISED_PORK", "红烧肉", [], [(PORK_BELLY, 250, False), _o(8), (SOY, 15, False), (SUGAR, 15, False), _s(1)], [("盘", None)]),
    ("D_TWICE_COOKED_PORK", "回锅肉", [], [(PORK_BELLY, 200, False), (GREEN_PEPPER, 100, False), _o(10), (SOY, 8, False), _s(2), (SUGAR, 3, False)], [("盘", None)]),
    ("D_BRAISED_EGGPLANT", "红烧茄子", ["烧茄子", "鱼香茄子"], [(EGGPLANT, 300, False), _o(25), (SOY, 10, False), (SUGAR, 8, False), _s(2), (STARCH, 5, False)], [("盘", None)]),
    ("D_GARLIC_BROCCOLI", "蒜蓉西兰花", ["清炒西兰花", "炒西兰花"], [(BROCCOLI, 300, False), (GARLIC, 10, False), _o(10), _s(2)], [("盘", None)]),
    ("D_VINEGAR_CABBAGE", "醋溜白菜", ["炒白菜", "炒大白菜"], [(CABBAGE, 300, False), _o(10), _s(2), (VINEGAR, 10, False), (SUGAR, 3, False)], [("盘", None)]),
    ("D_STIR_GREENS", "清炒小白菜", ["炒青菜", "清炒青菜"], [(BOK_CHOY, 300, False), (GARLIC, 5, False), _o(10), _s(2)], [("盘", None)]),
    ("D_STIR_SPINACH", "清炒菠菜", ["炒菠菜"], [(SPINACH, 300, False), (GARLIC, 5, False), _o(10), _s(2)], [("盘", None)]),
    ("D_OYSTER_LETTUCE", "蚝油生菜", ["炒生菜"], [(LETTUCE, 300, False), _o(8), (SOY, 8, False), _s(1)], [("盘", None)]),
    ("D_DRY_POT_CAULIFLOWER", "干锅花菜", ["炒花菜", "手撕花菜"], [(CAULIFLOWER, 300, False), (PORK_BELLY, 30, False), _o(15), _s(2.5), (SOY, 5, False)], [("盘", None)]),
    ("D_MUSHROOM_RAPE", "香菇油菜", ["香菇青菜"], [(RAPE, 250, False), (SHIITAKE, 80, False), _o(10), _s(2), (SOY, 5, False), (STARCH, 3, False)], [("盘", None)]),
    ("D_STIR_LOTUS", "清炒藕片", ["炒藕片"], [(LOTUS, 300, False), _o(10), _s(2), (VINEGAR, 5, False)], [("盘", None)]),
    ("D_HAND_TORN_CABBAGE", "手撕包菜", ["炒包菜", "炒卷心菜"], [(ROUND_CABBAGE, 300, False), _o(15), (SOY, 8, False), (VINEGAR, 8, False), _s(2)], [("盘", None)]),
    ("D_STIR_SPROUT", "清炒豆芽", ["炒豆芽", "炒绿豆芽"], [(MUNG_SPROUT, 300, False), _o(10), _s(2), (VINEGAR, 5, False)], [("盘", None)]),
    ("D_CELERY_TOFU_DRY", "芹菜炒香干", ["芹菜香干"], [(CELERY, 200, False), (TOFU_DRY, 100, False), _o(12), _s(2), (SOY, 5, False)], [("盘", None)]),
    ("D_LEEK_EGG", "韭菜炒鸡蛋", ["韭菜炒蛋"], [(LEEK, 200, False), (EGG, 100, False), _o(15), _s(2)], [("盘", None)]),
    ("D_CUCUMBER_EGG", "黄瓜炒鸡蛋", ["黄瓜炒蛋"], [(CUCUMBER, 200, False), (EGG, 100, False), _o(12), _s(2)], [("盘", None)]),
    ("D_GARLIC_SHOOT_PORK", "蒜薹炒肉", ["蒜苔炒肉"], [(GARLIC_SHOOT, 200, False), (PORK_LEAN, 100, False), _o(12), _s(2), (SOY, 8, False)], [("盘", None)]),
    ("D_DRY_FRIED_BEANS", "干煸四季豆", ["干煸豆角", "炒四季豆"], [(GREEN_BEAN, 300, False), (PORK, 30, False), _o(25), _s(2.5), (SOY, 5, False)], [("盘", None)]),
    ("D_SHRIMP_EGG", "虾仁炒蛋", ["滑蛋虾仁"], [(SHRIMP, 150, False), (EGG, 150, False), _o(12), _s(2), (STARCH, 3, False)], [("盘", None)]),
    ("D_ONION_BEEF", "洋葱炒牛肉", ["洋葱牛肉"], [(BEEF, 150, False), (ONION, 200, False), _o(12), (SOY, 10, False), _s(1), (STARCH, 5, False)], [("盘", None)]),
    ("D_BEEF_POTATO", "土豆烧牛肉", ["土豆炖牛肉"], [(BEEF, 200, False), (POTATO, 200, False), _o(15), (SOY, 15, False), _s(2), (SUGAR, 5, False)], [("盘", None)]),
    ("D_BOILED_PORK", "水煮肉片", [], [(PORK_LEAN, 200, False), (CABBAGE, 150, False), (MUNG_SPROUT, 100, False), _o(40), _s(3), (SOY, 10, False), (STARCH, 8, False)], [("盘", None)]),
    ("D_BOILED_FISH", "水煮鱼", [], [(GRASS_CARP, 300, False), (MUNG_SPROUT, 150, False), _o(50), _s(4), (STARCH, 8, False)], [("盘", None)]),
    ("D_SOUR_FISH", "酸菜鱼", [], [(GRASS_CARP, 300, False), (CABBAGE, 100, False), (WATER, 400, False), _o(25), _s(6), (STARCH, 8, False)], [("盆", None), ("碗", 300)]),
    ("D_STEAMED_PERCH", "清蒸鲈鱼", ["清蒸鱼"], [(PERCH, 200, False), (SOY, 15, False), _o(8), (SCALLION, 10, False)], [("盘", None)]),
    ("D_BRAISED_HAIRTAIL", "红烧带鱼", ["炸带鱼"], [(HAIRTAIL, 250, False), _o(20), (SOY, 15, False), (SUGAR, 8, False), _s(1), (STARCH, 10, False)], [("盘", None)]),
    ("D_BOILED_SHRIMP", "白灼虾", ["水煮虾"], [(SHRIMP, 250, False), (SOY, 10, False)], [("盘", None)]),
    ("D_COLA_WINGS", "可乐鸡翅", [], [(CHICKEN_WING, 250, False), (COLA, 150, False), (SOY, 15, False), _o(8)], [("盘", None), ("个", 40)]),
    ("D_BRAISED_RIBS", "红烧排骨", [], [(PORK_RIB, 250, False), _o(10), (SOY, 15, False), (SUGAR, 15, False), (VINEGAR, 10, False)], [("盘", None)]),
    ("D_SWEET_SOUR_RIBS", "糖醋排骨", [], [(PORK_RIB, 250, False), _o(15), (SOY, 10, False), (SUGAR, 30, False), (VINEGAR, 25, False), (STARCH, 10, False)], [("盘", None)]),
    ("D_BRAISED_CHICKEN", "黄焖鸡", [], [(CHICKEN, 250, False), (SHIITAKE, 50, False), (GREEN_PEPPER, 50, False), (POTATO, 50, False), _o(15), (SOY, 20, False), (SUGAR, 5, False), _s(1)], [("份", None)]),
    ("D_CHICKEN_MUSHROOM", "小鸡炖蘑菇", ["鸡肉炖蘑菇"], [(CHICKEN, 250, False), (SHIITAKE, 100, False), (WATER, 300, False), _o(10), (SOY, 10, False), _s(2)], [("盆", None), ("碗", 300)]),
    ("D_CUCUMBER_SALAD", "拍黄瓜", ["凉拌黄瓜"], [(CUCUMBER, 300, False), (GARLIC, 10, False), (VINEGAR, 15, False), (SESAME_OIL, 5, True), _s(2), (SUGAR, 3, False)], [("盘", None)]),
    ("D_COLD_TOFU", "小葱拌豆腐", ["凉拌豆腐"], [(TOFU, 300, False), (SCALLION, 10, False), (SESAME_OIL, 5, True), _s(2), (SOY, 5, False)], [("盘", None)]),
    ("D_WOODEAR_SALAD", "凉拌木耳", [], [(WOOD_EAR, 200, False), (CARROT, 30, False), (VINEGAR, 15, False), (SESAME_OIL, 5, True), (SOY, 8, False), _s(1), (GARLIC, 10, False)], [("盘", None)]),
    ("D_TOMATO_EGG_SOUP", "西红柿鸡蛋汤", ["番茄蛋花汤", "番茄鸡蛋汤"], [(TOMATO, 150, False), (EGG, 50, False), (WATER, 300, False), _o(5), _s(2), (STARCH, 5, False)], [("碗", 250)]),
    ("D_LAVER_EGG_SOUP", "紫菜蛋花汤", ["紫菜汤"], [(LAVER, 5, False), (EGG, 50, False), (WATER, 400, False), (SESAME_OIL, 3, True), _s(2)], [("碗", 250)]),
    ("D_WINTERMELON_RIB_SOUP", "冬瓜排骨汤", [], [(WINTER_MELON, 300, False), (PORK_RIB, 150, False), (WATER, 500, False), _s(3)], [("碗", 300)]),
    ("D_STEAMED_EGG", "鸡蛋羹", ["蒸蛋", "蒸鸡蛋"], [(EGG, 100, False), (WATER, 150, False), (SESAME_OIL, 3, True), (SOY, 5, False)], [("碗", None)]),
    ("D_FRIED_EGG", "煎蛋", ["荷包蛋", "煎鸡蛋"], [(EGG, 50, False), _o(5), _s(0.3)], [("个", None)]),
    ("D_PORK_DUMPLING", "猪肉白菜饺子", ["饺子", "水饺", "猪肉饺子"], [(FLOUR, 100, False), (WATER, 50, False), (PORK, 80, False), (CABBAGE, 80, False), _o(5), _s(2), (SOY, 5, False), (SESAME_OIL, 3, True)], [("个", 25), ("盘", None)]),
    ("D_LEEK_EGG_DUMPLING", "韭菜鸡蛋饺子", ["素饺子"], [(FLOUR, 100, False), (WATER, 50, False), (LEEK, 100, False), (EGG, 60, False), _o(10), _s(2)], [("个", 22), ("盘", None)]),
    ("D_WONTON", "馄饨", ["云吞", "抄手"], [(FLOUR, 60, False), (WATER, 330, False), (PORK, 60, False), (LAVER, 2, False), _s(2), (SESAME_OIL, 3, True)], [("碗", None)]),
    ("D_PORK_BUN", "肉包子", ["包子", "猪肉包", "鲜肉包"], [(FLOUR, 100, False), (WATER, 50, False), (PORK, 60, False), (SCALLION, 10, False), _o(3), (SOY, 5, False), _s(1)], [("个", 90)]),
    ("D_VEG_BUN", "素包子", ["菜包", "青菜包"], [(FLOUR, 100, False), (WATER, 50, False), (BOK_CHOY, 80, False), (SHIITAKE, 20, False), _o(8), _s(1.5)], [("个", 90)]),
    ("D_ROUJIAMO", "肉夹馍", [], [(FLOUR, 100, False), (WATER, 45, False), (PORK, 80, False), (SOY, 10, False), _o(5)], [("个", None)]),
    ("D_SCALLION_PANCAKE", "葱油饼", ["葱花饼"], [(FLOUR, 100, False), (WATER, 60, False), _o(15), (SCALLION, 15, False), _s(2)], [("张", None), ("块", 60)]),
    ("D_JIANBING", "煎饼果子", [], [(FLOUR, 60, False), (WATER, 60, False), (EGG, 50, False), (DOUGH_STICK, 30, False), _o(5), (SOY, 10, False), (SCALLION, 5, False)], [("个", None)]),
    ("D_EGG_FRIED_RICE", "蛋炒饭", ["鸡蛋炒饭"], [(RICE_COOKED, 300, False), (EGG, 100, False), (SCALLION, 10, False), _o(15), _s(2)], [("份", None), ("碗", 250)]),
    ("D_YANGZHOU_RICE", "扬州炒饭", ["炒饭"], [(RICE_COOKED, 300, False), (EGG, 50, False), (SHRIMP, 30, False), (HAM_SAUSAGE, 30, False), (CARROT, 20, False), _o(15), _s(2)], [("份", None), ("碗", 250)]),
    ("D_BEEF_NOODLE", "牛肉面", ["兰州拉面", "牛肉拉面"], [(NOODLE, 150, False), (BEEF_BRAISED, 50, False), (RADISH, 30, False), (WATER, 400, False), _o(5), _s(3), (SCALLION, 5, False)], [("碗", None)]),
    ("D_TOMATO_EGG_NOODLE", "西红柿鸡蛋面", ["番茄鸡蛋面"], [(NOODLE, 150, False), (TOMATO, 150, False), (EGG, 50, False), (WATER, 300, False), _o(10), _s(2.5)], [("碗", None)]),
    ("D_ZHAJIANG_NOODLE", "炸酱面", [], [(NOODLE, 200, False), (PORK, 50, False), (CUCUMBER, 50, False), (WATER, 100, False), _o(10), (SOY, 15, False), _s(2)], [("碗", None)]),
    ("D_FRIED_NOODLE", "炒面", [], [(NOODLE, 150, False), (CABBAGE, 80, False), (PORK_LEAN, 30, False), (WATER, 80, False), _o(15), (SOY, 10, False), _s(1)], [("份", None)]),
    ("D_RICE_NOODLE", "米粉", ["米线", "桂林米粉"], [(RICE_NOODLE, 100, False), (WATER, 250, False), (PORK, 30, False), (MUNG_SPROUT, 30, False), _o(8), (SOY, 10, False), _s(2)], [("碗", None)]),
    ("D_MALATANG", "麻辣烫", [], [(VERMICELLI, 30, False), (TOFU, 50, False), (BOK_CHOY, 100, False), (FISH_BALL, 50, False), (POTATO, 50, False), (ENOKI, 50, False), (WATER, 300, False), _o(15), _s(4)], [("碗", None)]),
    ("D_PIDAN_PORRIDGE", "皮蛋瘦肉粥", [], [(RICE_PORRIDGE, 400, False), (PRESERVED_EGG, 30, False), (PORK_LEAN, 30, False), _s(1.5)], [("碗", 300)]),
]

# ---------------------------------------------------------------- 家常单位（可食部克数）
UNITS = {
    EGG: [("个", 50)],
    RICE_COOKED: [("碗", 150), ("小碗", 100)],
    "012402": [("碗", 150)],
    "012408": [("碗", 150)],
    "011404x": [("个", 100)],
    "011405": [("个", 100)],
    "011411": [("个", 100)],
    "011401": [("个", 80)],
    RICE_PORRIDGE: [("碗", 250)],
    "012409": [("碗", 250)],
    "015103": [("碗", 250)],
    "101101x": [("盒", 250), ("杯", 250)],
    "101187x": [("盒", 250), ("杯", 250)],
    "101152x": [("盒", 250), ("杯", 250)],
    "101161x": [("盒", 250), ("杯", 250)],
    "103001x": [("杯", 150), ("盒", 100)],
    "031405": [("杯", 250), ("碗", 300)],
    "061101x": [("个", 200)],
    "061201x": [("个", 200)],
    "065033": [("根", 120)],
    "064101": [("个", 150)],
    "062101x": [("个", 150)],
    "063909": [("个", 80)],
    "066201x": [("块", 200)],
    "063101x": [("串", 150), ("颗", 5)],
    "063910": [("颗", 15)],
    DOUGH_STICK: [("根", 50)],
    "013101": [("根", 120)],
    "021205": [("个", 200)],
    POTATO: [("个", 150)],
    TOMATO: [("个", 150)],
    CUCUMBER: [("根", 200)],
    TOFU: [("块", 100)],
    OIL: [("勺", 10)],
    "192007": [("勺", 10)],
    "192001": [("勺", 10)],
    "192014": [("勺", 10)],
    SESAME_OIL: [("勺", 5)],
    "192019": [("勺", 10)],
    SALT: [("小勺", 5)],
    SOY: [("勺", 10)],
    SUGAR: [("勺", 10)],
    VINEGAR: [("勺", 10)],
    "S_HONEY": [("勺", 15)],
    "S_BREAD": [("片", 35)],
    "S_BISCUIT": [("块", 10)],
    "S_INSTANT_NOODLE": [("包", 100)],
    COLA: [("罐", 330), ("瓶", 500)],
    "S_JUICE_DRINK": [("瓶", 500), ("杯", 250)],
    "S_BEER": [("罐", 330), ("瓶", 500)],
    "S_COFFEE_BLACK": [("杯", 250)],
    "S_TEA": [("杯", 250)],
    WATER: [("杯", 250)],
    "019012": [("勺", 15)],
    "072005": [("把", 15)],
    "071004": [("个", 5)],
    "114101": [("个", 10)],
    "112203": [("个", 60)],
    PRESERVED_EGG: [("个", 55)],
    HAM_SAUSAGE: [("根", 50)],
}

# 附加搜索别名
ALIASES = {
    EGG: ["鸡蛋", "水煮蛋", "煮鸡蛋", "白煮蛋", "茶叶蛋", "卤蛋"],
    RICE_COOKED: ["米饭", "白米饭", "大米饭"],
    RICE_PORRIDGE: ["白粥", "大米粥", "稀饭"],
    "011404x": ["馒头"],
    "101101x": ["牛奶", "纯牛奶"],
    "103001x": ["酸奶"],
    "061101x": ["苹果"],
    "061201x": ["梨"],
    "065033": ["香蕉"],
    "066201x": ["西瓜"],
    "063101x": ["葡萄"],
    "082101x": ["牛肉"],
    "081101x": ["猪肉"],
    "091101x": ["鸡肉"],
    "083101x": ["羊肉"],
    "031301x": ["豆腐"],
    "011305x": ["面条", "生面条"],
    "021101": ["土豆"],
    "043119": ["西红柿"],
}

# ---------------------------------------------------------------- GI（名称取自 GI 表，值由脚本读取）
GI_MAP = {
    "大米饭（粳米，精米）": [RICE_COOKED, "012402", "012408", "D_EGG_FRIED_RICE", "D_YANGZHOU_RICE"],
    "大米粥": [RICE_PORRIDGE, "012409", "D_PIDAN_PORRIDGE"],
    "馒头（富强粉）": ["011404x", "011405", "011411", "011401"],
    "烙饼": ["011403", "D_SCALLION_PANCAKE"],
    "油条": [DOUGH_STICK],
    "* 面条（白细，煮）": [NOODLE, "011301x", "D_TOMATO_EGG_NOODLE", "D_FRIED_NOODLE", "D_ZHAJIANG_NOODLE"],
    "* 米粉": [RICE_NOODLE, "D_RICE_NOODLE"],
    "马铃薯": [POTATO],
    "马铃薯（蒸）": ["021106"],
    "甘薯（红，煮）": ["021205"],
    "藕粉": ["022105"],
    "豆腐（炖）": [TOFU, TOFU_FIRM, "031307"],
    "豆腐干": [TOFU_DRY],
    "绿豆": ["032101"],
    "胡萝卜（金笋）": [CARROT, "041201"],
    "南瓜（倭瓜、番瓜）": ["043213"],
    "山药（薯蓣）": ["047104"],
    "芋头（蒸芋艿 / 毛芋）": ["047203"],
    "苹果": ["061101x"],
    "梨": ["061201x"],
    "桃": ["062101x"],
    "李子": ["062201"],
    "樱桃": ["062902"],
    "葡萄": ["063101x"],
    "猕猴桃": ["063909"],
    "柑（橘子）": ["064203"],
    "柚": ["064301"],
    "菠萝": ["065002"],
    "芒果": ["065011"],
    "香蕉": ["065033"],
    "西瓜": ["066201x"],
    "哈密瓜": ["066103"],
    "枣": ["062301"],
    "花生": [PEANUT, "072004"],
    "腰果": ["071036"],
    "牛奶": ["101101x", "101187x"],
    "脱脂牛奶": ["101161x"],
    "酸奶（加糖）": ["103001x"],
    "白面包": ["S_BREAD"],
    "可乐饮料": [COLA],
    "啤酒（澳大利亚产）": ["S_BEER"],
    "蜂蜜": ["S_HONEY"],
    "蔗糖": [SUGAR],
    "饺子 / 三鲜": ["D_PORK_DUMPLING", "D_LEEK_EGG_DUMPLING"],
    "包子 / 芹菜猪肉": ["D_PORK_BUN", "D_VEG_BUN"],
    "硬质小麦粉肉馅馄饨": ["D_WONTON"],
    "牛肉面": ["D_BEEF_NOODLE"],
}
