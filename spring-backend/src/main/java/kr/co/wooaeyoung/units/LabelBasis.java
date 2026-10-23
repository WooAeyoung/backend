package kr.co.wooaeyoung.units;

/** 라벨 표기 형태(처리기준 v3 7.3(3)). 계수는 units/conversion-factors.json의 labelBases에 있다. */
public enum LabelBasis {
    /** mg/정·캡슐·스쿱·포 등 1회 제공량당. 기본값. */
    PER_SERVING,
    /** mg/100 g (분말·츄). */
    PER_100G,
    /** % (사료·영양간식), 단위는 "%". */
    PERCENT,
    /** mg/kg, IU/kg, ppm. */
    PER_KG,
    /** g·mg/1000 kcal. 사료 대사에너지(kcal/kg)가 필요하다. */
    PER_1000KCAL,
    /** 건물 기준 %, 단위는 "%". 수분(%)이 필요하다. */
    DRY_MATTER_PERCENT
}