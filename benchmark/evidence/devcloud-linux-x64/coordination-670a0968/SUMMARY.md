# Multi-instance coordination evidence

Candidate: `670a09687a91c0a80fbe2ab131990fa5e42d6692`
Jar SHA-256: `97a48fe7c5ba107bc19cb5a3fc82c437209fff153f1f8837a64eab7777519809`

| Scenario | Status | Detail |
|---|---|---|
| 01-live-three-and-repair | passed | SUCCESS=14986, cancelled=0, remaining=15014 |
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
