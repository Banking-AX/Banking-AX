import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { c02Api, taskBffEnabled, type C02CustomerCaseSnapshot, type C02Document, type C02OcrSnapshot, type NotificationItem } from '../api/taskApi'

type ResumeData = {
  snapshot: C02CustomerCaseSnapshot
  ocr: C02OcrSnapshot | null
  notifications: NotificationItem[]
  ocrError: string | null
}

const requestId = (action: string, caseId: number) => `c02-resume-${action}-${caseId}-${crypto.randomUUID()}`
const slotKey = (type: string, personRef: string | null) => `${type}:${personRef ?? 'COMMON'}`
const EDITABLE_CASE_STATUSES = new Set(['DRAFT', 'SUBMITTED'])

/** Resumes a JWT-owned Case; document uploads and revision checks remain server-authoritative. */
export function C02CaseResumePage() {
  const { caseId } = useParams()
  const parsedCaseId = Number(caseId)
  const [data, setData] = useState<ResumeData | null>(null)
  const [files, setFiles] = useState<Record<string, File | undefined>>({})
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = async () => {
    if (!taskBffEnabled || !Number.isSafeInteger(parsedCaseId) || parsedCaseId <= 0) return
    setBusy(true)
    setError(null)
    try {
      const snapshotResponse = await c02Api.snapshot(parsedCaseId, requestId('snapshot', parsedCaseId))
      const snapshot = snapshotResponse.data
      if (!snapshot || snapshot.caseId !== parsedCaseId || !Number.isSafeInteger(snapshot.workItemId) || snapshot.workItemId <= 0) {
        throw new Error('The Case response is incomplete or does not match the requested Case.')
      }
      const [notificationResponse, ocrResult] = await Promise.all([
        c02Api.notifications(requestId('notifications', parsedCaseId)),
        c02Api.customerOcr(parsedCaseId, snapshot.revision, requestId('ocr', parsedCaseId))
          .then(response => ({ data: response.data, error: null as string | null }))
          .catch(cause => ({ data: null, error: cause instanceof Error ? cause.message : 'Could not load OCR status.' })),
      ])
      const latestSnapshot = await c02Api.snapshot(parsedCaseId, requestId('revision-check', parsedCaseId))
      if (latestSnapshot.data?.revision !== snapshot.revision) {
        throw new Error('This Case changed while it was loading. Refresh to view a consistent revision.')
      }
      const ocr = ocrResult.data
      const ocrMatchesCase = ocr && ocr.revision === snapshot.revision && ocr.documents.every(extraction =>
        extraction.caseRevision <= snapshot.revision && snapshot.documents.some(document =>
          document.documentFileId === extraction.documentFileId && document.caseRevision === extraction.caseRevision,
        ),
      )
      setData({
        snapshot,
        ocr: ocrMatchesCase ? ocr : null,
        notifications: (notificationResponse.data ?? []).filter(item => item.caseId === parsedCaseId),
        ocrError: ocrResult.error ?? (ocr && !ocrMatchesCase ? 'OCR data did not match the saved Case revision and uploaded files.' : null),
      })
    } catch (cause) {
      setData(null)
      setError(cause instanceof Error ? cause.message : 'Could not load this Case.')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => { void load() }, [parsedCaseId])

  const upload = async (documentTypeCode: string, personRef: string | null) => {
    if (!data || !editable || !taskBffEnabled) return
    const key = slotKey(documentTypeCode, personRef)
    const file = files[key]
    if (!file) { setError('Choose a file for this document slot.'); return }
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const response = await c02Api.uploadDocument(
        parsedCaseId, data.snapshot.revision, requestId('upload', parsedCaseId), key, file,
      )
      if (!response.data) throw new Error('The server did not return the saved document revision.')
      setFiles(current => ({ ...current, [key]: undefined }))
      setNotice(`Document saved. Case revision is now ${response.data.revision}. A submitted Case was rechecked against the new revision.`)
      await load()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Document upload failed.')
    } finally {
      setBusy(false)
    }
  }

  const editable = Boolean(data && EDITABLE_CASE_STATUSES.has(data.snapshot.caseStatus))
  const latestDocument = (key: string) => data?.snapshot.documents
    .filter(document => document.documentSlotKey === key)
    .reduce((latest, current) => !latest || current.version > latest.version ? current : latest, undefined as C02Document | undefined)

  return <section>
    <h1>C-02 Case details and continuation</h1>
    <p>Case ID: {Number.isSafeInteger(parsedCaseId) && parsedCaseId > 0 ? parsedCaseId : 'Invalid ID'}</p>
    {!taskBffEnabled && <p role="status">Customer task BFF is not enabled in this environment.</p>}
    {!Number.isSafeInteger(parsedCaseId) || parsedCaseId <= 0
      ? <p role="alert">A valid Case ID is required.</p>
      : <button type="button" disabled={!taskBffEnabled || busy} onClick={() => void load()}>{busy ? 'Loading…' : 'Refresh Case'}</button>}
    {error && <p role="alert">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    {data && <>
      <h2>WorkItem {data.snapshot.workItemId} · Case {data.snapshot.caseId}</h2>
      <p>Case status: {data.snapshot.caseStatus} · revision {data.snapshot.revision} · Agent review: {data.snapshot.reviewStatus ?? 'not run'}</p>
      {data.snapshot.precheck.reviewItems.length > 0 && <section>
        <h3>Latest precheck</h3>
        <p>{data.snapshot.precheck.agentRunStatus} · {data.snapshot.precheck.reviewStatus ?? 'not available'}</p>
        <ul>{data.snapshot.precheck.reviewItems.map((item, index) =>
          <li key={`${item.category ?? 'item'}-${index}`}>{item.category ?? 'Review'} · {item.message ?? item.source ?? 'Staff verification required'}</li>,
        )}</ul>
      </section>}

      <h3>Document slots</h3>
      <ul>{data.snapshot.requiredDocuments.map(slot => {
        const key = slotKey(slot.documentTypeCode, slot.personRef)
        const uploaded = latestDocument(key)
        return <li key={key}>
          <strong>{slot.documentTypeCode}</strong> · {slot.requirement} · {slot.note}
          {uploaded && <p>Latest: {uploaded.fileName} · v{uploaded.version} · revision {uploaded.caseRevision} · {uploaded.status}</p>}
          {editable && <>
            <label>Replacement or additional file <input type="file" accept=".pdf,.png,.jpg,.jpeg" disabled={busy || !taskBffEnabled} onChange={event => setFiles(current => ({ ...current, [key]: event.target.files?.[0] }))} /></label>
            <button type="button" disabled={busy || !taskBffEnabled || !files[key]} onClick={() => void upload(slot.documentTypeCode, slot.personRef)}>Upload to this Case</button>
          </>}
        </li>
      })}</ul>

      {!editable && data.snapshot.caseStatus.startsWith('REVIEW_REJECTED') && <p role="status">
        This is a final staff decision. The current policy requires a new Case for reapplication; this Case cannot be supplemented. <Link to="/chat">Start a new request in chat</Link>.
      </p>}
      {!editable && !data.snapshot.caseStatus.startsWith('REVIEW_REJECTED') && <p role="status">This Case is no longer editable. The server will reject changes after staff review has started.</p>}
      {editable && data.snapshot.caseStatus === 'SUBMITTED' && data.snapshot.reviewStatus === 'WAITING_CUSTOMER' && <p role="status">A precheck requested more information. Upload the requested documents here; the new revision is rechecked automatically. This does not replace a staff decision.</p>}

      <h3>OCR reference status</h3>
      {data.ocrError && <p role="alert">{data.ocrError}</p>}
      {data.ocr && <>
        <p>Snapshot revision {data.ocr.revision}. OCR is reference material and requires staff verification.</p>
        <ul>{data.ocr.documents.map(document =>
          <li key={document.documentFileId}>{document.documentTypeCode} · {document.method}/{document.status} · manual verification required</li>,
        )}</ul>
      </>}

      <h3>Staff notifications for this Case</h3>
      {data.notifications.length === 0 ? <p>No customer notification is available for this Case yet.</p> : <ul>{data.notifications.map(item =>
        <li key={item.id}><strong>{item.title}</strong><p>{item.content}</p><small>{item.createdAt}</small></li>,
      )}</ul>}
    </>}
    <p><Link to="/tasks/c02/notifications">Customer notifications</Link></p>
  </section>
}
