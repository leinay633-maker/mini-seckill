# Multi-instance coordination evidence

Candidate: `1fc9e77a58354921cf59708beeaec2954d6daade`
Jar SHA-256: `3da1a000f43411176f3691d3853cecb4bb05a25d79faa26b191abe66752b3ad5`

| Scenario | Status | Detail |
|---|---|---|
| 01-live-three-and-repair | passed | SUCCESS=14999, cancelled=0, remaining=15001 |
| 02-live-deduction-frontier | passed | SUCCESS=1, cancelled=0, remaining=9 |
| 03-expired-owner-successor | passed | SUCCESS=1, cancelled=0, remaining=9 |
| 04-paused-reservation-reclaimed | passed | SUCCESS=1, cancelled=1, remaining=9 |
| 05-killed-before-persist | passed | SUCCESS=1, cancelled=1, remaining=9 |
| 06-killed-after-persist | passed | SUCCESS=1, cancelled=0, remaining=9 |
| 07-send-attempt-aba | passed | SUCCESS=1, cancelled=0, remaining=9 |
| 08-live-consumer-loss | passed | SUCCESS=15001, cancelled=0, remaining=14999 |
| 09-scarce-stock | passed | SUCCESS=41, cancelled=0, remaining=0 |

Signal faults are same-host process faults, not network partitions or Redis/MySQL failover.
Raw logs, SQL/Redis/broker observations and k6 summaries override this generated table.
Historical REPORT/evidence files were not overwritten. No capacity comparison is inferred.
