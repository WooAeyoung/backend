// 화면 표시용 숫자 반올림 (구현계획서 6.4). 백엔드 kr.wooaeyoung.format.DisplayNumber 와 같은 규칙.
// 공통 테스트 케이스: contract/display-number-cases.json
// ≥1,000 정수+쉼표 / 10~1,000 소수 1자리 / 1~10 소수 2자리 / 0~1 유효숫자 3자리 / 0 → "0" / null → "–"
// 반올림은 사사오입(십진 표기 기준). 반올림 결과가 다음 구간에 닿으면 다음 구간 규칙으로 표시한다.

/** 십진 자릿수 이동 (부동소수 오차 없이 지수 표기로 이동) */
function shift(x: number, n: number): number {
  const [m, e] = String(x).split('e');
  return Number(`${m}e${(Number(e) || 0) + n}`);
}

function roundHalfUp(abs: number, decimals: number): number {
  return shift(Math.round(shift(abs, decimals)), -decimals);
}

export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined) return '–';
  if (value === 0) return '0';
  const sign = value < 0 ? '-' : '';
  const a = Math.abs(value);
  const grouped = () => sign + roundHalfUp(a, 0).toLocaleString('en-US');
  const fixed = (d: number) => sign + roundHalfUp(a, d).toFixed(d);

  if (a >= 1000) return grouped();
  if (a >= 10) return roundHalfUp(a, 1) >= 1000 ? grouped() : fixed(1);
  if (a >= 1) return roundHalfUp(a, 2) >= 10 ? fixed(1) : fixed(2);
  const decimals = 2 - Math.floor(Math.log10(a));
  return roundHalfUp(a, decimals) >= 1 ? fixed(2) : fixed(decimals);
}
