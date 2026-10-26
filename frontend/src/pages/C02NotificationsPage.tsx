/**
 * Customer-facing route shell for the C-02 web notification returned by Spring.
 * It deliberately does not call the internal Gateway API from a browser.
 */
export function C02NotificationsPage() {
  return <section>
    <h1>C-02 processing notifications</h1>
    <p>Your C-02 outcome is delivered as an in-service web notification after a staff member records a final mock intake review.</p>
    <div className="placeholder">
      <p>Notification retrieval requires the customer-authenticated BFF. This UI does not treat a precheck as an approval and does not display fabricated results.</p>
    </div>
  </section>
}
