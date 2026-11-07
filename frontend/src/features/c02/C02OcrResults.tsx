import type { ReactNode } from 'react'
import type { C02CaseIntake, C02OcrSnapshot, C02ReviewItem } from '../../api/taskApi'
import { OCR_ROW_LABELS, OCR_TYPE_LABELS, buildOcrView, type OcrRow, type OcrRowStatus } from './c02OcrView'

const ROW_TONE: Record<OcrRowStatus, string> = { AUTO_MATCHED: 'success', MISMATCH: 'danger', STAFF_REQUIRED: 'caution', NOT_COMPARED: 'neutral', NOT_EXTRACTED: 'neutral' }

const NEEDS_STAFF: OcrRowStatus[] = ['MISMATCH', 'STAFF_REQUIRED', 'NOT_EXTRACTED']

function Rows({ rows }: { rows: OcrRow[] }) {
  return <>{rows.map(row => <tr key={row.label} className={row.status === 'MISMATCH' ? 'c02-staff-row-danger' : undefined}>
    <td><strong>{row.label}</strong></td><td>{row.caseValue}</td><td>{row.ocrValue}</td>
    <td><span className={`c02-staff-badge c02-staff-badge--${ROW_TONE[row.status]}`}>{OCR_ROW_LABELS[row.status]}</span>{row.reason && <span className="c02-staff-muted">{row.reason}</span>}</td>
  </tr>)}</>
}

function RowTable({ head, children }: { head: boolean; children: ReactNode }) {
  return <div className="c02-staff-table-wrap"><table className="c02-staff-ocr-table">
    <thead className={head ? undefined : 'c02-staff-sr'}><tr><th>항목</th><th>신청정보</th><th>OCR 추출값</th><th>결과</th></tr></thead>
    <tbody>{children}</tbody>
  </table></div>
}

/** Facts from the stored OCR run. A match compares text only and never confirms a seal, a signature or the original. */
export function C02OcrResults({ ocr, intake, items }: { ocr: C02OcrSnapshot | null; intake: C02CaseIntake | null; items: C02ReviewItem[] }) {
  if (!ocr) return <p className="c02-staff-empty">OCR 상태를 확인하지 못했습니다. 원본을 직접 확인해 주세요.</p>
  if (ocr.documents.length === 0) return <p className="c02-staff-empty">OCR 미실행입니다. 파일 제출 여부와 문서 내용 검증은 다르며, 원본 확인이 필요합니다.</p>
  const targets = ocr.documents.filter(document => document.status !== 'NOT_APPLICABLE')
  const excluded = ocr.documents.length - targets.length
  return <>
    {targets.length === 0 && <p className="c02-staff-empty">이 Case에는 OCR 대상 문서가 없습니다.</p>}
    {targets.map(document => {
      const view = buildOcrView(document, intake, items)
      return <div className="c02-staff-ocr" key={document.documentFileId}>
        <p className="c02-staff-meta">문서 #{document.documentFileId} · v{document.documentVersion}</p>
        {view?.typeResult && <p><strong>{OCR_TYPE_LABELS[view.typeResult] ?? view.typeResult}</strong></p>}
        {document.status !== 'SUCCEEDED' && <p className="c02-staff-empty">자동 인식에 실패했습니다. 불일치나 일치로 처리하지 않았습니다. 원본을 직접 확인해 주세요.{document.failureReason ? ` 사유 코드: ${document.failureReason}` : ''}</p>}
        {document.status === 'SUCCEEDED' && !view && <p className="c02-staff-empty">저장된 추출값을 읽지 못했습니다. 원본을 직접 확인해 주세요.</p>}
        {view && (() => {
          const attention = view.rows.filter(row => NEEDS_STAFF.includes(row.status))
          const matched = view.rows.filter(row => row.status === 'AUTO_MATCHED')
          const reference = view.rows.filter(row => row.status === 'NOT_COMPARED')
          return <>
            <p className="c02-staff-tally"><span className="c02-staff-badge c02-staff-badge--success">일치 {matched.length}</span><span className="c02-staff-badge c02-staff-badge--caution">직원 확인 {attention.length}</span><span className="c02-staff-badge c02-staff-badge--neutral">참고 {reference.length}</span></p>
            {attention.length > 0 && <><h3 className="c02-staff-subhead">직원이 확인할 항목</h3><RowTable head><Rows rows={attention} /></RowTable></>}
            {matched.length > 0 && <><h3 className="c02-staff-subhead">글자가 일치한 항목</h3><RowTable head={attention.length === 0}><Rows rows={matched} /></RowTable></>}
            {reference.length > 0 && <details className="c02-staff-fold"><summary>자동 비교하지 않은 참고값 {reference.length}건 보기</summary><RowTable head={false}><Rows rows={reference} /></RowTable></details>}
          </>
        })()}
      </div>
    })}
    {excluded > 0 && <p className="c02-staff-meta">일반 증빙서류 {excluded}건은 자동 판독 대상이 아닙니다. 제출된 원본을 확인해 주세요.</p>}
    <p className="c02-staff-meta">일치·불일치는 시스템이 글자를 비교한 결과이며 서류의 적정성을 뜻하지 않습니다. 서명·인감, 위임 의사, 상속관계는 직원이 원본으로 확인합니다.</p>
  </>
}
