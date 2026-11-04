/**
 * 健康管理方案导出：按方案详情的卡片版式渲染打印文档，交由浏览器输出 PDF。
 *
 * 页边距为 0，避免浏览器把当前页面地址印在页脚。文件名用文档标题，并去掉路径非法字符。
 */

export interface PrintableCarePlanTask {
  title?: string
  category?: string
  frequency?: string
  timeSlot?: string
  enabled?: boolean
  targetMl?: number | null
  rationale?: string
}

export interface PrintableCarePlanCheckin {
  checkinDate?: string
  taskTitle?: string
  taskCategory?: string
  timeSlot?: string
  status?: string
  note?: string
}

export interface PrintableCarePlan {
  title?: string
  peopleName?: string
  goalSummary?: string
  summary?: string
  rationaleOverview?: string
  source?: string
  versionLabel?: string
  status?: string
  publishedAt?: string
  signStatus?: string
  exercise?: {
    goal?: string
    rationale?: string
    precautions?: string[]
    contraindications?: string[]
    reviewHint?: string
    weeklyPlan?: Array<{ day?: string; items?: unknown[] }>
  }
  diet?: {
    principles?: string[] | string
    rationale?: string
    calorieHint?: string
    energyTarget?: {
      energyKcal?: number
      carbG?: number
      proteinG?: number
      fatG?: number
      goalType?: string
      rateKgPerWeek?: number
    } | null
    notes?: string
    recommended?: Array<{ label?: string; code?: string } | string>
    limited?: Array<{ label?: string; code?: string } | string>
    allergensAvoid?: Array<{ label?: string; code?: string; highRisk?: boolean } | string>
    intolerances?: Array<{ label?: string; code?: string } | string>
    sampleDay?: Record<string, unknown>
    sampleWeek?: Record<string, unknown>
  }
  execution?: {
    horizonDays?: number
    rationale?: string
    tasks?: PrintableCarePlanTask[]
  }
  checkins?: PrintableCarePlanCheckin[]
  /** 患者页上的过敏提醒；有则优先于饮食块里的过敏标签 */
  allergyAlert?: {
    avoid?: Array<{ name?: string; highRisk?: boolean }>
    intolerances?: string[]
  }
  /** 患者导出不包含健管师审阅用的生成依据 */
  audience?: 'staff' | 'patient'
}

const FOOD_LABELS: Record<string, string> = {
  VEGETABLES: '蔬菜',
  WHOLE_GRAINS: '全谷物',
  LEAN_PROTEIN: '优质蛋白',
  LOW_FAT_DAIRY: '低脂奶制品',
  FRUITS_LOW_SUGAR: '低糖水果',
  OATS: '燕麦',
  LEAFY_GREENS: '绿叶菜',
  HIGH_PURINE: '高嘌呤食物',
  SALTED_FOOD: '腌制食品',
  SUGARY_DRINKS: '含糖饮料',
  SUGARY_DRINK: '含糖饮料',
  ALCOHOL: '酒精',
  HIGH_FAT: '高脂肪食物',
  PICKLED: '高盐腌制品',
}

const FREQUENCY_LABELS: Record<string, string> = {
  QD: '每日1次',
  BID: '每日2次',
  TID: '每日3次',
  TIW: '每周3次',
  QOD: '隔日1次',
  QW: '每周1次',
  PRN: '必要时',
}

const TIME_SLOT_LABELS: Record<string, string> = {
  MORNING: '早晨',
  AFTERNOON: '下午',
  EVENING: '晚上',
  AFTER_DINNER: '餐后',
  BEDTIME: '睡前',
  OTHER: '其他',
  ALL: '全天',
}

const CATEGORY_LABELS: Record<string, string> = {
  EXERCISE: '运动',
  DIET: '饮食',
  WATER: '饮水',
  SLEEP: '睡眠',
  MOOD: '心情',
  OTHER: '其他',
}

const EXERCISE_TYPE_LABELS: Record<string, string> = {
  WALKING: '步行',
  BRISK_WALKING: '快走',
  NORDIC_WALKING: '健走',
  JOGGING: '慢跑',
  RUNNING: '跑步',
  STAIRS: '爬楼梯',
  HIKING: '徒步',
  CYCLING: '骑行',
  STATIONARY_BIKE: '健身车',
  ELLIPTICAL: '椭圆机',
  ROWING: '划船机',
  SWIMMING: '游泳',
  WATER_AEROBICS: '水中健身',
  AEROBIC: '有氧',
  HOME_AEROBIC: '居家有氧',
  AEROBICS: '健身操',
  SQUARE_DANCE: '广场舞',
  DANCING: '跳舞',
  TAICHI: '太极',
  TAI_CHI: '太极',
  BADUANJIN: '八段锦',
  WUQINXI: '五禽戏',
  YOGA: '瑜伽',
  PILATES: '普拉提',
  STRETCHING: '拉伸/柔韧',
  STRETCH: '拉伸',
  STRENGTH: '力量训练',
  SEATED_STRENGTH: '坐姿力量',
  RESISTANCE: '抗阻训练',
  BODYWEIGHT: '徒手力量',
  MACHINE_STRENGTH: '器械力量',
  ELASTIC_BAND: '弹力带',
  PLANK: '平板支撑',
  BALANCE: '平衡训练',
  BALANCE_TRAINING: '平衡训练',
  BADMINTON: '羽毛球',
  TABLE_TENNIS: '乒乓球',
  TENNIS: '网球',
  BASKETBALL: '篮球',
  FOOTBALL: '足球',
  VOLLEYBALL: '气排球',
  GATEBALL: '门球',
  GOLF: '高尔夫',
  ROPE_SKIPPING: '跳绳',
  SHUTTLECOCK: '踢毽子',
  BED_EXERCISE: '床旁活动',
  SEATED_ACTIVITY: '坐位活动',
  BED_MOBILITY: '床上活动',
  ANKLE_PUMP: '踝泵',
  BEDSIDE_ANKLE_PUMP: '床旁踝泵',
  UPPER_LIMB_ROM: '上肢活动',
  BEDSIDE_UPPER_LIMB: '床旁上肢活动',
  PASSIVE_ROM: '被动活动',
  POSITION_CHANGE: '体位变换',
  BREATHING: '呼吸训练',
  BREATHING_TRAINING: '呼吸训练',
  HOUSEWORK: '家务',
  GARDENING: '园艺',
  REST: '休息',
  OTHER: '其他',
}

const INTENSITY_LABELS: Record<string, string> = {
  LIGHT: '低',
  MODERATE: '中',
  VIGOROUS: '高',
}

const DAY_LABELS: Record<string, string> = {
  MON: '周一',
  TUE: '周二',
  WED: '周三',
  THU: '周四',
  FRI: '周五',
  SAT: '周六',
  SUN: '周日',
}

const MEAL_LABELS: Array<{ key: string; label: string }> = [
  { key: 'breakfast', label: '早餐' },
  { key: 'lunch', label: '午餐' },
  { key: 'dinner', label: '晚餐' },
  { key: 'snacks', label: '加餐' },
]

function lookup(map: Record<string, string>, code?: string | null): string {
  if (!code) return ''
  return map[code] || map[code.toUpperCase()] || code
}

function esc(value: unknown): string {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function foodLabel(item?: { label?: string; code?: string } | string | null): string {
  if (item == null) return ''
  if (typeof item === 'string') return FOOD_LABELS[item] || item
  const code = (item.code || '').trim()
  const label = (item.label || '').trim()
  if (label && label !== code && !/^[A-Z][A-Z0-9_]*$/.test(label)) return label
  return FOOD_LABELS[code] || label || code
}

function splitSentences(list: string[] | string | undefined, sep: RegExp): string[] {
  const arr = Array.isArray(list) ? list : list ? [list] : []
  return arr
    .flatMap((s) => String(s).split(sep))
    .map((s) => s.trim())
    .filter(Boolean)
}

function parseMeal(raw: unknown): { text: string; swapHint: string } {
  if (raw == null) return { text: '', swapHint: '' }
  if (typeof raw === 'string') return { text: raw.trim(), swapHint: '' }
  if (typeof raw === 'object') {
    const o = raw as { text?: string; swapHint?: string | null }
    return {
      text: String(o.text || '').trim(),
      swapHint: o.swapHint ? String(o.swapHint).trim() : '',
    }
  }
  return { text: '', swapHint: '' }
}

function mealsFromSample(raw: unknown) {
  if (!raw || typeof raw !== 'object') return [] as Array<{ label: string; text: string; swapHint: string }>
  const o = raw as Record<string, unknown>
  return MEAL_LABELS.map(({ key, label }) => {
    let meal = parseMeal(o[key])
    if (!meal.text && key === 'snacks') meal = parseMeal(o.snack)
    return meal.text ? { label, text: meal.text, swapHint: meal.swapHint } : null
  }).filter((x): x is { label: string; text: string; swapHint: string } => !!x)
}

function statusLabel(status?: string) {
  if (status === 'DRAFT') return '草稿'
  if (status === 'ACTIVE') return '已生效'
  if (status === 'ARCHIVED') return '历史版本'
  return status || ''
}

function isAiSource(source?: string) {
  return source === 'LLM' || source === 'LLM_THEN_EDIT'
}

function sourceLabel(source?: string) {
  if (source === 'TEMPLATE' || source === 'TEMPLATE_THEN_EDIT') return '模板生成'
  if (source === 'MANUAL') return '人工编制'
  return source || ''
}

/** 角标强调审核责任；人工智能起草写在方案说明里，避免只剩「AI 生成」。 */
function reviewLabel(plan: PrintableCarePlan) {
  if (!isAiSource(plan.source)) return sourceLabel(plan.source)
  if (plan.status === 'DRAFT') return '待健管师确认'
  if (plan.signStatus === 'SIGNED') return '医生已签署'
  return '健管师已审核'
}

function aiNote(plan: PrintableCarePlan) {
  if (!isAiSource(plan.source)) return ''
  if (plan.status === 'DRAFT') {
    return `<section class="ai"><strong>方案说明</strong><p>本方案由人工智能辅助起草，尚未发布，需健康管理师确认后才会提供给患者。仅供健康管理参考，不构成医学诊断或处方。</p></section>`
  }
  const reviewed =
    plan.signStatus === 'SIGNED'
      ? '本方案由人工智能辅助起草，经健康管理师审核，并由医生签署后发布'
      : '本方案由人工智能辅助起草，并经健康管理师审核后发布'
  return `<section class="ai"><strong>方案说明</strong><p>${reviewed}，供健康管理参考，不构成医学诊断或处方。如有不适请及时就医或联系健管师。</p></section>`
}

function checkinStatusLabel(status: string | undefined, patient: boolean) {
  if (status === 'DONE') return '已完成'
  if (status === 'SKIPPED') return '已跳过'
  if (status === 'MISSED') return patient ? '未完成' : '未打卡'
  return status || '—'
}

function formatDateTime(iso?: string) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function printFileName(plan: PrintableCarePlan): string {
  const raw = (plan.title || '').trim() || '健康管理方案'
  const safe = raw
    .replace(/(\d{1,2})\/(\d{1,2})/g, '$1-$2')
    .replace(/[–—]/g, '至')
    .replace(/[\\/:*?"<>|]/g, '-')
    .replace(/\s+/g, ' ')
    .trim()
  const name = (plan.peopleName || '').trim()
  const titled = name && !safe.includes(name) ? `${name}-${safe}` : safe
  return titled || '健康管理方案'
}

function listHtml(items: string[], ordered = false): string {
  if (!items.length) return ''
  const tag = ordered ? 'ol' : 'ul'
  return `<${tag}>${items.map((item) => `<li>${esc(item)}</li>`).join('')}</${tag}>`
}

function tagsHtml(label: string, tone: string, items: Array<{ label?: string; code?: string } | string> | undefined): string {
  const labels = (items || []).map((item) => foodLabel(item)).filter(Boolean)
  if (!labels.length) return ''
  return `<div class="tags"><span class="tags-label">${esc(label)}</span>${labels
    .map((text) => `<span class="tag tag--${tone}">${esc(text)}</span>`)
    .join('')}</div>`
}

function mealLines(rows: Array<{ label: string; text: string; swapHint: string }>): string {
  return rows
    .map(
      (row) =>
        `<li><strong>${esc(row.label)}</strong>：${esc(row.text)}${
          row.swapHint ? `<span class="swap">可替换：${esc(row.swapHint)}</span>` : ''
        }</li>`,
    )
    .join('')
}

function rationaleHtml(text: string | undefined, patient: boolean, label = '依据'): string {
  const value = (text || '').trim()
  if (patient || !value) return ''
  return `<p class="rationale"><span>${esc(label)}</span>${esc(value)}</p>`
}

function buildExercise(plan: PrintableCarePlan, patient: boolean): string {
  const ex = plan.exercise
  if (!ex) return ''
  const contra = (ex.contraindications || []).map((s) => s.trim()).filter(Boolean)
  const precautions = (ex.precautions || []).map((s) => s.trim()).filter(Boolean)
  const days = (ex.weeklyPlan || [])
    .map((day) => {
      const items = Array.isArray(day.items) ? day.items : []
      const lines = items
        .map((item) => {
          if (!item || typeof item !== 'object') return ''
          const o = item as Record<string, unknown>
          const type =
            lookup(EXERCISE_TYPE_LABELS, o.type != null ? String(o.type) : '') ||
            (o.title != null ? String(o.title) : '') ||
            '运动'
          const intensity = lookup(INTENSITY_LABELS, o.intensity != null ? String(o.intensity) : '')
          const duration = o.durationMin != null && o.durationMin !== '' ? `${o.durationMin} 分钟` : ''
          const note = o.note != null ? String(o.note).trim() : ''
          const meta = [intensity && `强度${intensity}`, duration].filter(Boolean).join(' · ')
          return `<li><strong>${esc(type)}</strong>${meta ? ` ${esc(meta)}` : ''}${
            note ? `<span class="swap">${esc(note)}</span>` : ''
          }</li>`
        })
        .filter(Boolean)
      if (!lines.length) return ''
      const dayLabel = DAY_LABELS[String(day.day || '').toUpperCase()] || String(day.day || '')
      return `<div class="week-day"><div class="week-name">${esc(dayLabel)}</div><ul>${lines.join('')}</ul></div>`
    })
    .filter(Boolean)
  const goal = (ex.goal || '').trim()
  if (!goal && !contra.length && !precautions.length && !ex.reviewHint && !days.length && !(ex.rationale || '').trim()) {
    return ''
  }
  return card(
    '运动方案',
    'exercise',
    `${goal ? `<p class="lead">${esc(goal)}</p>` : ''}${rationaleHtml(ex.rationale, patient)}${
      contra.length ? `<div class="block block--warn"><div class="block-title">禁忌</div>${listHtml(contra)}</div>` : ''
    }${
      precautions.length
        ? `<div class="block"><div class="block-title">注意事项</div>${listHtml(precautions)}</div>`
        : ''
    }${ex.reviewHint ? `<p class="hint">${esc(ex.reviewHint)}</p>` : ''}${
      days.length ? `<div class="block"><div class="block-title">一周安排</div>${days.join('')}</div>` : ''
    }`,
  )
}

function energyHtml(plan: PrintableCarePlan): { card: string; tips: string } {
  const diet = plan.diet
  const t = diet?.energyTarget
  const kcal = t && typeof t.energyKcal === 'number' && t.energyKcal > 0 ? t : null
  let tips = (diet?.calorieHint || '').trim()
  if (kcal) {
    tips = tips.replace(/^每日约?\s*\d{3,4}\s*(千卡|大卡|kcal)\s*([（(][^）)]*[）)])?\s*[，,；;]?\s*/i, '')
    tips = tips.replace(/^(以维持体重为目标|按每周[减增]重\s*[\d.]+\s*kg)\s*[，,；;]?\s*/i, '')
    tips = tips.replace(/^[；;，,。]+/, '').trim()
  }
  if (!kcal) return { card: '', tips }
  const pct = (grams: number, factor: number) =>
    kcal.energyKcal ? Math.round(((grams * factor) / kcal.energyKcal) * 100) : 0
  const macros = [
    { key: 'carb', label: '碳水', grams: kcal.carbG, pct: pct(kcal.carbG ?? 0, 4) },
    { key: 'protein', label: '蛋白质', grams: kcal.proteinG, pct: pct(kcal.proteinG ?? 0, 4) },
    { key: 'fat', label: '脂肪', grams: kcal.fatG, pct: pct(kcal.fatG ?? 0, 9) },
  ]
  let goal = '维持体重'
  if (kcal.goalType === 'LOSE' && kcal.rateKgPerWeek) goal = `每周减重 ${kcal.rateKgPerWeek} kg`
  if (kcal.goalType === 'GAIN' && kcal.rateKgPerWeek) goal = `每周增重 ${kcal.rateKgPerWeek} kg`
  const cardHtml = `<div class="energy">
    <div class="energy-main"><strong>${esc(kcal.energyKcal)}</strong><span>千卡 / 日</span><em>${esc(goal)}</em></div>
    <div class="macros">${macros
      .map(
        (m) => `<div class="macro macro--${m.key}">
          <div class="macro-head"><span>${m.label}</span><em>${m.pct}%</em></div>
          <div class="macro-grams">${m.grams ?? '—'}<small>g</small></div>
          <div class="macro-bar"><i style="width:${Math.min(m.pct, 100)}%"></i></div>
        </div>`,
      )
      .join('')}</div>
    ${tips ? `<p class="energy-tips">${esc(tips)}</p>` : ''}
  </div>`
  return { card: cardHtml, tips: '' }
}

function buildDiet(plan: PrintableCarePlan, patient: boolean): string {
  const diet = plan.diet
  const principles = splitSentences(diet?.principles, /[；;]/)
  const notes = splitSentences(diet?.notes, /(?<=[。；;])/)
  const energy = energyHtml(plan)
  const sampleDay = mealsFromSample(diet?.sampleDay)
  const sampleWeek = Object.entries(DAY_LABELS)
    .map(([code, label]) => {
      const meals = mealsFromSample(diet?.sampleWeek?.[code])
      return meals.length ? { label, meals } : null
    })
    .filter((x): x is { label: string; meals: Array<{ label: string; text: string; swapHint: string }> } => !!x)
  const alert = plan.allergyAlert
  const avoid = (alert?.avoid || []).map((a) => (a.name || '').trim()).filter(Boolean)
  const alertHtml = alert
    ? `${
        avoid.length || (alert.intolerances || []).length
          ? `<div class="block block--warn"><div class="block-title">过敏提醒</div>${
              avoid.length
                ? `<p>请避免食用：${alert.avoid!
                    .filter((a) => (a.name || '').trim())
                    .map((a) => `${esc(a.name)}${a.highRisk ? '（高危）' : ''}`)
                    .join('、')}。外出就餐、购买加工食品时注意查看配料。</p>`
                : ''
            }${
              (alert.intolerances || []).length
                ? `<p>不耐受：${esc(alert.intolerances!.join('、'))}，可少量尝试，注意食用量。</p>`
                : ''
            }${
              alert.avoid?.some((a) => a.highRisk)
                ? '<p>如误食后出现呼吸困难、喉头发紧、全身风团或头晕，请立即就医或拨打 120。</p>'
                : ''
            }</div>`
          : ''
      }`
    : ''
  const body = [
    rationaleHtml(diet?.rationale, patient),
    energy.card,
    !energy.card && energy.tips ? `<p class="hint">${esc(energy.tips)}</p>` : '',
    principles.length ? `<div class="block block--diet"><div class="block-title">饮食原则</div>${listHtml(principles, true)}</div>` : '',
    notes.length ? `<div class="block block--warn"><div class="block-title">用药与就餐提醒</div>${listHtml(notes)}</div>` : '',
    tagsHtml('推荐', 'ok', diet?.recommended),
    tagsHtml('限制', 'warn', diet?.limited),
    alert ? '' : tagsHtml('过敏回避', 'danger', diet?.allergensAvoid),
    alert ? '' : tagsHtml('不耐受', 'warn', diet?.intolerances),
    alertHtml,
    sampleDay.length
      ? `<div class="block block--diet"><div class="block-title">示例日</div><ul>${mealLines(sampleDay)}</ul></div>`
      : '',
    sampleWeek.length
      ? `<div class="block block--diet"><div class="block-title">一周饮食示例</div><table><thead><tr><th>星期</th><th>早餐</th><th>午餐</th><th>晚餐</th><th>加餐</th></tr></thead><tbody>${sampleWeek
          .map((day) => {
            const cell = (name: string) => {
              const meal = day.meals.find((item) => item.label === name)
              if (!meal) return '—'
              return `${esc(meal.text)}${meal.swapHint ? `<span class="swap">可替换：${esc(meal.swapHint)}</span>` : ''}`
            }
            return `<tr><td>${esc(day.label)}</td><td>${cell('早餐')}</td><td>${cell('午餐')}</td><td>${cell('晚餐')}</td><td>${cell('加餐')}</td></tr>`
          })
          .join('')}</tbody></table></div>`
      : '',
  ]
    .filter(Boolean)
    .join('')
  if (!body) return ''
  return card('饮食方案', 'diet', body)
}

function buildExecution(plan: PrintableCarePlan, patient: boolean): string {
  const execution = plan.execution
  const tasks = (execution?.tasks || []).filter((task) => (task.title || '').trim())
  const rationale = rationaleHtml(execution?.rationale, patient)
  if (!tasks.length && !rationale) return ''
  const rows = tasks
    .map((task) => {
      const meta = [
        lookup(CATEGORY_LABELS, task.category),
        lookup(FREQUENCY_LABELS, task.frequency),
        lookup(TIME_SLOT_LABELS, task.timeSlot),
        task.targetMl ? `${task.targetMl} ml` : '',
        task.enabled === false ? '未启用' : '',
      ]
        .filter(Boolean)
        .join(' · ')
      const why = !patient && (task.rationale || '').trim() ? `<div class="why">${esc(task.rationale)}</div>` : ''
      return `<tr><td><strong>${esc(task.title)}</strong>${why}</td><td>${esc(meta || '—')}</td></tr>`
    })
    .join('')
  const badge = execution?.horizonDays ? `<span class="badge">${execution.horizonDays} 天</span>` : ''
  return card(
    `执行计划${badge}`,
    'execution',
    `${rationale}${
      rows
        ? `<table><thead><tr><th>任务</th><th>安排</th></tr></thead><tbody>${rows}</tbody></table>`
        : '<p class="muted">暂无任务</p>'
    }`,
  )
}

function buildCheckins(plan: PrintableCarePlan, patient: boolean): string {
  if (!plan.checkins) return ''
  if (!plan.checkins.length) {
    return card('打卡记录', 'checkins', '<p class="muted">该版本暂无打卡记录</p>')
  }
  const rows = plan.checkins
    .map(
      (row) =>
        `<tr><td>${esc(row.checkinDate || '—')}</td><td>${esc(row.taskTitle || '任务')}</td><td>${esc(
          lookup(CATEGORY_LABELS, row.taskCategory) || '—',
        )}</td><td>${esc(lookup(TIME_SLOT_LABELS, row.timeSlot) || '—')}</td><td>${esc(
          checkinStatusLabel(row.status, patient),
        )}</td><td>${esc(row.note || '')}</td></tr>`,
    )
    .join('')
  return card(
    `打卡记录<span class="badge">${plan.checkins.length} 条</span>`,
    'checkins',
    `<table><thead><tr><th>日期</th><th>任务</th><th>类别</th><th>时段</th><th>状态</th><th>备注</th></tr></thead><tbody>${rows}</tbody></table>`,
  )
}

function card(title: string, tone: string, body: string): string {
  return `<section class="card card--${tone}"><h2>${title}</h2>${body}</section>`
}

const PRINT_CSS = `
  @page { size: A4; margin: 0; }
  * { box-sizing: border-box; -webkit-print-color-adjust: exact; print-color-adjust: exact; }
  body {
    margin: 0;
    padding: 0;
    font-family: "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
    font-size: 12px;
    line-height: 1.55;
    color: #13222e;
    background: #f7fbf9;
  }
  .sheet { padding: 10mm 12mm 12mm; }
  .hero, .card, .energy, .ai {
    background: #fff;
    border: 1px solid #e6eef2;
    border-radius: 14px;
  }
  .hero { padding: 16px 18px; margin-bottom: 12px; }
  .pills { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
  .pill {
    display: inline-block;
    padding: 2px 10px;
    border-radius: 999px;
    font-size: 11px;
    font-weight: 700;
    background: #e8eef2;
    color: #3d4b58;
  }
  .pill--ink { background: #13222e; color: #fff; }
  .pill--review { background: #e7f6f2; color: #0f766e; }
  h1 { margin: 0; font-size: 22px; font-weight: 900; line-height: 1.35; }
  .mark { background: linear-gradient(transparent 62%, #ffd84d 62%); padding: 0 2px; }
  .meta { margin: 6px 0 0; font-size: 12px; font-weight: 600; color: #5b6a77; }
  .goal { margin: 10px 0 0; padding-top: 10px; border-top: 1px dashed #e6eef2; white-space: pre-wrap; }
  .rationale {
    margin: 8px 0 0;
    padding: 8px 10px;
    border-radius: 8px;
    background: #f8fafc;
    border: 1px solid #e8edf4;
    color: #64748b;
    font-size: 12px;
  }
  .rationale span, .block-title {
    display: inline-block;
    margin-right: 6px;
    padding: 0 6px;
    border-radius: 4px;
    background: #e2e8f0;
    color: #475569;
    font-size: 11px;
    font-weight: 700;
  }
  .ai { margin-bottom: 12px; padding: 12px 14px; border-color: #bfdbfe; background: #f3f8ff; }
  .ai strong { display: block; margin-bottom: 4px; color: #1e63d9; }
  .ai p { margin: 0; }
  .card { padding: 14px 16px; margin-bottom: 12px; }
  .hero, .ai, .energy, .block, tr { break-inside: avoid; page-break-inside: avoid; }
  h2 { break-after: avoid; page-break-after: avoid; }
  h2 { margin: 0 0 8px; font-size: 15px; font-weight: 800; }
  .card--exercise h2 { color: #0369a1; }
  .card--diet h2 { color: #15803d; }
  .card--execution h2, .card--summary h2 { color: #6d28d9; }
  .card--checkins h2 { color: #0f766e; }
  .badge {
    margin-left: 8px;
    padding: 1px 8px;
    border-radius: 999px;
    background: #ede9fe;
    color: #6d28d9;
    font-size: 11px;
    font-weight: 700;
  }
  .lead { margin: 0; white-space: pre-wrap; }
  .muted { margin: 0; color: #8b98a5; }
  .hint { margin: 10px 0 0; padding: 8px 10px; border-radius: 8px; background: #f0f9ff; color: #0369a1; }
  .block { margin-top: 10px; padding: 8px 10px; border-radius: 10px; background: #f8fafc; }
  .block--warn { background: #fff7ed; }
  .block--diet { background: #f0fdf4; }
  .block-title { margin-bottom: 6px; }
  ul, ol { margin: 6px 0 0; padding-left: 18px; }
  li { margin: 3px 0; }
  .swap { display: block; color: #5b6a77; font-size: 11px; }
  .week-name { margin-top: 8px; font-weight: 800; }
  .energy { margin-top: 8px; padding: 12px; }
  .energy-main strong { font-size: 26px; }
  .energy-main span, .energy-main em { margin-left: 6px; color: #5b6a77; font-style: normal; }
  .macros { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; margin-top: 10px; }
  .macro-head { display: flex; justify-content: space-between; font-size: 11px; color: #5b6a77; }
  .macro-grams { font-size: 16px; font-weight: 800; }
  .macro-grams small { margin-left: 2px; font-size: 11px; font-weight: 600; }
  .macro-bar { height: 6px; border-radius: 999px; background: #e8eef2; overflow: hidden; }
  .macro-bar i { display: block; height: 100%; border-radius: inherit; background: #12a389; }
  .macro--protein .macro-bar i { background: #5b8def; }
  .macro--fat .macro-bar i { background: #e5a100; }
  .energy-tips { margin: 8px 0 0; color: #5b6a77; }
  .tags { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; margin-top: 10px; }
  .tags-label { font-size: 11px; font-weight: 700; color: #5b6a77; }
  .tag { padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 700; }
  .tag--ok { background: #e3f6ef; color: #0b7a66; }
  .tag--warn { background: #fff3da; color: #8a5c00; }
  .tag--danger { background: #fde7ea; color: #a8243a; }
  table { width: 100%; border-collapse: collapse; margin-top: 8px; }
  th, td { padding: 6px 8px; border-bottom: 1px solid #e6eef2; text-align: left; vertical-align: top; }
  th { font-size: 11px; color: #5b6a77; }
  .why { margin-top: 2px; color: #64748b; font-size: 11px; font-weight: 500; }
  footer { margin-top: 8px; font-size: 11px; color: #8b98a5; }
`

export function buildCarePlanPrintHtml(plan: PrintableCarePlan): string {
  const patient = plan.audience === 'patient'
  const fileName = printFileName(plan)
  const title = (plan.title || '').trim() || '健康管理方案'
  const reviewed = reviewLabel(plan)
  const pills = [
    statusLabel(plan.status) ? `<span class="pill pill--ink">${esc(statusLabel(plan.status))}</span>` : '',
    reviewed ? `<span class="pill${isAiSource(plan.source) ? ' pill--review' : ''}">${esc(reviewed)}</span>` : '',
  ]
    .filter(Boolean)
    .join('')
  const meta = [plan.peopleName, plan.versionLabel, plan.publishedAt ? `${formatDateTime(plan.publishedAt)} 发布` : '']
    .filter(Boolean)
    .join(' · ')
  const ai = aiNote(plan)
  const summary = (plan.summary || '').trim()
  const summaryCard = summary ? card('方案总结', 'summary', `<p class="lead">${esc(summary)}</p>`) : ''
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <title>${esc(fileName)}</title>
  <style>${PRINT_CSS}</style>
</head>
<body>
  <div class="sheet">
  <header class="hero">
    ${pills ? `<div class="pills">${pills}</div>` : ''}
    <h1><span class="mark">${esc(title)}</span></h1>
    ${meta ? `<p class="meta">${esc(meta)}</p>` : ''}
    ${plan.goalSummary ? `<p class="goal">${esc(plan.goalSummary)}</p>` : ''}
    ${rationaleHtml(plan.rationaleOverview, patient, '生成依据')}
  </header>
  ${ai}
  ${summaryCard}
  ${buildExercise(plan, patient)}
  ${buildDiet(plan, patient)}
  ${buildExecution(plan, patient)}
  ${buildCheckins(plan, patient)}
  <footer>本方案仅用于健康管理参考，不作为诊断依据；如有不适请及时就医。</footer>
  </div>
</body>
</html>`
}

/** 在隐藏 iframe 中打印，避免弹窗拦截与当前页面样式污染。 */
export function printCarePlan(plan: PrintableCarePlan): Promise<boolean> {
  return new Promise((resolve) => {
    const iframe = document.createElement('iframe')
    iframe.setAttribute('aria-hidden', 'true')
    iframe.style.position = 'fixed'
    iframe.style.left = '-10000px'
    iframe.style.top = '0'
    iframe.style.width = '210mm'
    iframe.style.height = '297mm'
    iframe.style.border = '0'
    document.body.appendChild(iframe)

    const cleanup = () => {
      window.setTimeout(() => iframe.remove(), 1000)
    }
    const fileName = printFileName(plan)
    const previousTitle = document.title
    const restoreTitle = () => {
      document.title = previousTitle
    }

    iframe.onload = () => {
      try {
        const win = iframe.contentWindow
        if (!win) {
          cleanup()
          resolve(false)
          return
        }
        document.title = fileName
        const finish = () => {
          restoreTitle()
          cleanup()
        }
        win.addEventListener('afterprint', finish, { once: true })
        window.addEventListener('afterprint', finish, { once: true })
        win.focus()
        win.print()
        resolve(true)
      } catch {
        restoreTitle()
        cleanup()
        resolve(false)
      }
    }

    const doc = iframe.contentDocument
    if (!doc) {
      cleanup()
      resolve(false)
      return
    }
    doc.open()
    doc.write(buildCarePlanPrintHtml(plan))
    doc.close()
  })
}
