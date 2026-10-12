# Multi-instance coordination evidence

Candidate: `37a88589480e46c8dec8dc94c3bfd36731d1207c`
Jar SHA-256: `eef28e6fb4c6750dcaf5b785ab46dc9d88fa8f0b1aa489562fcb78fc541df1b3`

| Scenario | Status | Detail |
|---|---|---|
| 01-live-three-and-repair | passed | SUCCESS=14883, cancelled=0, remaining=15117 |
| 02-live-deduction-frontier | passed | SUCCESS=1, cancelled=0, remaining=9 |
| 03-expired-owner-successor | passed | SUCCESS=1, cancelled=0, remaining=9 |
| 04-paused-reservation-reclaimed | passed | SUCCESS=1, cancelled=1, remaining=9 |
| 05-killed-before-persist | passed | SUCCESS=1, cancelled=1, remaining=9 |
| 06-killed-after-persist | passed | SUCCESS=1, cancelled=0, remaining=9 |
| 07-send-attempt-aba | passed | SUCCESS=1, cancelled=0, remaining=9 |
| 08-live-consumer-loss | passed | SUCCESS=15000, cancelled=0, remaining=15000 |
| 09-scarce-stock | passed | SUCCESS=41, cancelled=0, remaining=0 |

Signal faults are same-host process faults, not network partitions or Redis/MySQL failover.
Raw logs, SQL/Redis/broker observations and k6 summaries override this generated table.
Historical REPORT/evidence files were not overwritten. No capacity comparison is inferred.
