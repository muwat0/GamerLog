# GamerLog domain terms

- **Local session:** A persisted interval of playtime. Local sessions remain the source of truth regardless of export status.
- **Backloggd account:** The verified destination identity for optional one-way export. Connecting credentials does not authorize exporting every historical session.
- **Game mapping:** An explicit association between an installed application's package name and a Backloggd game. Tracking and exporting are independent choices.
- **Export item:** A durable contribution record for one local session, bound to a destination account and a frozen game/date/duration payload before its first delivery attempt. Multiple sent items can share one remote journal-entry identifier.
- **Waiting:** Eligible for delivery but not acknowledged by Backloggd.
- **Sending:** An attempt may be in progress; interruption does not prove that the destination rejected it.
- **Sent:** Backloggd has acknowledged the contribution, or read-only reconciliation has found its UUID, original day, and exact duration in the remote entry's contribution ledger with the aggregate time intact.
- **Excluded:** The user has explicitly chosen not to export the item. The local session is retained.
- **Failed:** Delivery has definitively failed and requires corrective action or a safe retry; it is not success.
- **Uncertain delivery:** A request might have reached Backloggd, but its acknowledgement is unavailable. Reconcile before any resend; absence of a response is not permission to create another log.
- **Reconciliation:** Read-only inspection of the destination to establish whether a particular export item already exists. It must not import or overwrite local playtime.
- **Exported playtime:** Whole minutes obtained by truncating the combined local milliseconds for each original local day, never inflating recorded time. Sub-minute contributions accumulate within that day instead of being independently discarded. Preexisting unmanaged remote minutes are preserved.
- **Export note:** A Backloggd session note containing a versioned contribution ledger with immutable UUID/day/exact-duration entries. Existing note text outside the ledger is retained. Notes may be publicly visible; they are not a place for credentials or private device metadata.
- **Automatic-export consent:** An explicit opt-in for mapped sessions started after enabling. Exporting earlier recorded history requires a separate explicit action.
- **Remote journal entry:** A Backloggd calendar range containing one or more local contributions. Its range, privacy, tags, and unrelated metadata are preserved when updated; an existing day entry is updated by its positive ID rather than replaced with a new same-day entry.
- **Unmanaged baseline:** Remote playtime that predates or was manually added outside the GamerLog contribution ledger. It is retained separately from summed local contributions, without inventing its distribution across days.
- **Playthrough target:** The existing most-recent Backloggd playthrough, as selected by the user. A new playthrough is created only when none exists; exporting does not replace existing reviews or settings.
