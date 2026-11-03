import type { C02OcrFieldComparison } from '../../api/taskApi'

/** Staff-only, non-authoritative comparison view. */
export function OcrComparisonSummary({ comparisons }: { comparisons: C02OcrFieldComparison[] }) {
  if (comparisons.length === 0) return null
  return <section aria-label="Spring OCR comparison">
    <h4>Spring structured comparison candidates (not identity or legal delegation verification)</h4>
    <ul>{comparisons.map(comparison => <li key={comparison.fieldName}>
      <strong>{comparison.fieldName}: {comparison.status}</strong>
      <span> · extracted: {comparison.extractedValue ?? 'missing'}</span>
      <span> · Case reference: {comparison.referenceValue ?? 'not available'}</span>
      <span> · confidence: {comparison.confidence === null ? 'unavailable' : `${(comparison.confidence * 100).toFixed(1)}%`}</span>
      <span> · reason: {comparison.reasonCode}</span>
      <span> · manual source check required: {comparison.manualVerificationRequired ? 'yes' : 'no'}</span>
    </li>)}</ul>
  </section>
}
