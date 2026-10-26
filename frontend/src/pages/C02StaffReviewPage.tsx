import { useParams } from 'react-router-dom'

/**
 * Deliberately read-only: an authenticated server-side BFF must call the
 * STAFF-only API.  The browser must never receive the internal gateway token.
 */
export function C02StaffReviewPage() {
  const { caseId } = useParams()
  return <section>
    <h1>C-02 staff precheck</h1>
    <p>Case: {caseId ?? 'not selected'}</p>
    <p>This screen presents the Agent precheck only. It does not approve an inheritance payment, create a reservation, or record a staff final decision.</p>
    <div className="placeholder">
      <p>Staff review data is available from the server-side STAFF API.</p>
      <p>Browser retrieval is intentionally disabled until the authenticated BFF/RBAC boundary is merged; the internal gateway token is not exposed to React.</p>
    </div>
  </section>
}
