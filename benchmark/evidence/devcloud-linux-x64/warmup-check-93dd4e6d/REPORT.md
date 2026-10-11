# Consumer/pool-budget retest — all planned cells

No performance conclusion is pre-filled. Diagnostic rows include jstack overhead and must not be pooled with A/B.
60s SUCCESS/s is a sampling estimate; not long-run sustainable TPS. Drain tail includes a 5s quiescence guard.
Disk grouping was fixed before this run: write await >2ms in >=5 observed seconds = noisy; <90% coverage = unknown.
No row is excluded. Null means missing/unknown, not zero. Full pool/disk/final-state evidence is in summary.json.

| Case | Rate | Status | Admission | Correctness | 40-budget | p99 ms | SUCCESS/s estimate | Drain tail s | Disk |
|---|---:|---|---|---|---|---:|---:|---:|---|
| 01-ab-baseline-round1 | 1000 | observed | True | True | True | 12.602239799999976 | 996.98 | 11.48 | low-stall |
| 02-ab-shared-round1 | 1000 | observed | True | True | True | 12.534882700000002 | 994.68 | 12.53 | low-stall |
| 03-ab-shared-round2 | 1000 | observed | True | True | True | 12.742719010000013 | 995.85 | 12.53 | low-stall |
| 04-ab-baseline-round2 | 1000 | observed | True | True | True | 11.973374010000008 | 996.15 | 12.51 | low-stall |
| 05-ab-baseline-round3 | 1000 | observed | True | True | True | 11.74210969999998 | 996.12 | 11.48 | low-stall |
| 06-ab-shared-round3 | 1000 | observed | True | True | True | 11.756057300000002 | 996.33 | 10.47 | low-stall |
