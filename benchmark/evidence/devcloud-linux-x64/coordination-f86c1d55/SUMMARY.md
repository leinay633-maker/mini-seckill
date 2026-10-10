# Multi-instance coordination evidence

Candidate: `f86c1d55ef37d25bcb14b9443b72f6cad436802f`
Jar SHA-256: `f02b2b0ae1c79ccf42e8f5b97fae855ca1e1af2be9be08c0ff4c706c155562ec`

| Scenario | Status | Detail |
|---|---|---|
| 01-live-three-and-repair | failed | 'values' |
| 02-live-deduction-frontier | failed | timed out: concurrent durable order |
| 03-expired-owner-successor | passed | SUCCESS=1, cancelled=0, remaining=9 |
| 04-paused-reservation-reclaimed | passed | SUCCESS=1, cancelled=1, remaining=9 |
| 05-killed-before-persist | passed | SUCCESS=1, cancelled=1, remaining=9 |
| 06-killed-after-persist | passed | SUCCESS=1, cancelled=0, remaining=9 |
| 07-send-attempt-aba | passed | SUCCESS=1, cancelled=0, remaining=9 |
| 08-live-consumer-loss | failed | 'values' |
| 09-scarce-stock | failed | 'values' |

Signal faults are same-host process faults, not network partitions or Redis/MySQL failover.
Raw logs, SQL/Redis/broker observations and k6 summaries override this generated table.
Historical REPORT/evidence files were not overwritten. No capacity comparison is inferred.
