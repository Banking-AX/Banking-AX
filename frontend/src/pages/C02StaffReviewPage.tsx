import { Link, useParams } from 'react-router-dom'

/**
 * Deliberately read-only: an authenticated server-side BFF must call the
 * STAFF-only API.  The browser must never receive the internal gateway token.
 */
export function C02StaffReviewPage() {
  const { caseId } = useParams()
  return <section>
    <h1>C-02 staff review</h1>
    <p>Case: {caseId ?? 'not selected'}</p>
    <p>The Agent precheck is reference material only. A staff member, not the Agent, records the final mock intake outcome.</p>
    <div className="placeholder">
      <h2>Final-review flow</h2>
      <ol><li>Review the current Case, document metadata, and precheck items.</li><li>Record Proceed, Requirement failed, or Document insufficient.</li><li>The server creates a customer web-notification record.</li></ol>
      <p>Server endpoints: staff-case, staff-decision, and customer notifications. Browser calls remain disabled until the authenticated BFF/RBAC boundary is available; the internal gateway token is never exposed here.</p>
      <button type="button" disabled>Record staff decision (BFF required)</button>
    </div>
    <p><Link to="/tasks/c02/notifications">Customer notification status</Link></p>
  </section>
}
