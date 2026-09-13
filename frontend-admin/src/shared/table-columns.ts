/**
 * Admin 表格列宽约定（用于 el-table-column v-bind）
 *
 * - bizId / bizCode：32 位雪花 ID 或机构/健管组编码，单行省略 + hover 看全文
 * - datetime / status / actions*：固定宽列，避免每页重复 magic number
 */
export const TABLE_COL = {
  bizId: {
    minWidth: 260,
    showOverflowTooltip: true,
    className: 'col-biz-id',
  },
  bizCode: {
    minWidth: 260,
    showOverflowTooltip: true,
    className: 'col-biz-id',
  },
  datetime: {
    width: 168,
    className: 'col-datetime',
    showOverflowTooltip: true,
  },
  status: {
    width: 90,
  },
  actionsSm: {
    width: 100,
    align: 'right' as const,
    fixed: 'right' as const,
  },
  actionsMd: {
    width: 160,
    align: 'right' as const,
    fixed: 'right' as const,
  },
  actionsLg: {
    width: 220,
    align: 'right' as const,
    fixed: 'right' as const,
  },
} as const
