# One app, two kinds of user

Claimants and claims staff use the **same Angular application** (http://localhost:4200). Signing in returns a JWT whose
role decides where the router sends you and which screens the guards allow; the backend enforces the same rules
independently. The two sides never talk directly: every hand-off is a change of claim state through the API, which
the other person sees on their next poll (10 s for claimants, 15 s for officers).

```mermaid
sequenceDiagram
    autonumber
    actor C as Claimant (aina)
    participant UI as Angular app :4200
    participant API as claims-service
    participant R as reporting-service
    actor O as Officer (wei)
    actor M as Manager (mark)

    C->>UI: Sign in -> /claims
    C->>UI: Report an incident (/claims/new)
    UI->>API: POST /api/claims  -> SUBMITTED
    API-->>R: CLAIM_SUBMITTED (outbox -> Kafka)
    O->>UI: Sign in -> /queue (Unassigned, SLA "pick up in 24h")
    O->>UI: Pick up
    UI->>API: POST /assign -> UNDER_REVIEW (409 if someone was faster)
    O->>UI: Workbench: request information
    UI->>API: POST /info-requests -> PENDING_INFO
    C->>UI: Claim page shows "We need more information"
    UI->>API: POST /info-responses -> UNDER_REVIEW
    O->>UI: Sees the answer, approves with a reserve
    UI->>API: POST /approve -> APPROVED (reserve counts as exposure)
    API-->>R: CLAIM_APPROVED
    M->>UI: Sign in -> /dashboard: exposure, workload, "data as of"
    O->>UI: Settle (<= coverage limit)
    UI->>API: POST /settle -> SETTLED (exposure released)
    C->>UI: Claim page: "We settled this claim for ..."
```

| Need from the brief | Who | Screen | Status / API |
|---|---|---|---|
| Report an incident | Claimant | `/claims/new` | `POST /api/claims` -> `SUBMITTED` |
| Track their claim | Claimant | `/claims`, `/claims/:id` (steps + timeline) | `GET /api/claims[/id]` |
| Provide additional information when asked | Claimant | answer box on `/claims/:id` | `PENDING_INFO` -> `UNDER_REVIEW` |
| Receive decisions | Claimant | decision card on `/claims/:id` | `APPROVED`, `SETTLED`, `REJECTED` (with reason) |
| Pick up incoming claims | Officer | `/queue` Unassigned, oldest first or longest waiting | `POST /assign` |
| Review and assess | Officer | `/workbench/:id`, request info, adjust reserve | `UNDER_REVIEW` ⇄ `PENDING_INFO` |
| Progress to settlement or rejection | Officer | workbench actions valid for the current state | `approve`, `settle`, `reject` |
| See the team's workload and performance | Officer / Manager | queue SLA summary; `/dashboard` workload per officer | `/api/staff/claims/summary`, `/api/reports/workload` |

**Performance today** means SLA state per claim (on track / due soon / overdue against a per-status target) and
workload per officer. Not built yet: historical throughput (claims closed per week) and average time per stage, which
the event history already supports.
