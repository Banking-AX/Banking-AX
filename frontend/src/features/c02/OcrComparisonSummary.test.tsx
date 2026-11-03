import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { OcrComparisonSummary } from './OcrComparisonSummary'

describe('OcrComparisonSummary', () => {
  it('shows candidate comparison, confidence, reason, and manual source check warning', () => {
    const html = renderToStaticMarkup(<OcrComparisonSummary comparisons={[{
      fieldName: 'grantor_names', status: 'MISMATCH_CANDIDATE', extractedValue: 'Synthetic OCR Name',
      referenceValue: 'Synthetic Case Name', confidence: 0.72, manualVerificationRequired: true,
      reasonCode: 'TEXT_MISMATCH_CANDIDATE',
    }]} />)
    expect(html).toContain('MISMATCH_CANDIDATE')
    expect(html).toContain('Synthetic OCR Name')
    expect(html).toContain('Synthetic Case Name')
    expect(html).toContain('72.0%')
    expect(html).toContain('TEXT_MISMATCH_CANDIDATE')
    expect(html).toContain('manual source check required: yes')
    expect(html).toContain('not identity or legal delegation verification')
  })
})
