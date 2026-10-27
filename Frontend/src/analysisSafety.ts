import type { NutrientResult } from './api'

/** Missing data or blocked standards cannot support a safety conclusion. */
export function hasUnjudgedNutrients(nutrients: NutrientResult[]): boolean {
  return nutrients.some(n => n.total == null || n.unknownContribution ||
    ['UNSUPPORTED', 'UNVERIFIED_STANDARD', 'INSUFFICIENT_DATA', 'UNIT_CONVERSION_UNSUPPORTED'].includes(n.status))
}

export function nutrientSourceLabel(n: NutrientResult): string {
  if (n.total == null) return '계산 불가'
  if (n.unknownContribution) return '일부 정보 없음'
  return n.source === 'ESTIMATED' ? '사료 추정 포함' : '성분표 실제값'
}
