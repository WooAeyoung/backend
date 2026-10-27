const REFERENCE_ANALYSIS_URL = 'https://wooaeyoung-pet-nutrition-pf7kxx9qk-yeoh89-9309.vercel.app/'

export default function ReferenceAnalysisPage() {
  return (
    <section className="reference-analysis-page" aria-label="영양제 분석">
      <iframe
        title="영양제 분석"
        src={REFERENCE_ANALYSIS_URL}
        className="reference-analysis-frame"
      />
    </section>
  )
}
