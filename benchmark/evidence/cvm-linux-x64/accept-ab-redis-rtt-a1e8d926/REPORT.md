# Consumer/pool-budget retest — all planned cells

No performance conclusion is pre-filled. Diagnostic rows include jstack overhead and must not be pooled with A/B.
60s SUCCESS/s is a sampling estimate; not long-run sustainable TPS. Drain tail includes a 5s quiescence guard.
Disk grouping was fixed before this run: write await >2ms in >=5 observed seconds = noisy; <90% coverage = unknown.
No row is excluded. Null means missing/unknown, not zero. Full pool/disk/final-state evidence is in summary.json.

| Case | Rate | Status | Admission | Correctness | 40-budget | p99 ms | SUCCESS/s estimate | Drain tail s | Disk |
|---|---:|---|---|---|---|---:|---:|---:|---|
| 01-ab-baseline-round1 | 1000 | observed | True | True | True | 18.1054079100001 | 991.0 | 11.6 | low-stall |
| 01-ab-baseline-round1 | 2000 | observed | True | True | True | 24.17391220000007 | 1969.93 | 9.8 | low-stall |
| 01-ab-baseline-round1 | 3000 | observed | True | True | True | 152.01279579999994 | 2135.68 | 15.87 | low-stall |
| 02-ab-shared-round1 | 1000 | observed | True | True | True | 11.718510460000008 | 990.7 | 11.59 | low-stall |
| 02-ab-shared-round1 | 2000 | observed | True | True | True | 22.081922589999916 | 1969.73 | 9.79 | low-stall |
| 02-ab-shared-round1 | 3000 | observed | True | True | True | 118.67338859999995 | 2408.6 | 15.76 | low-stall |
| 03-ab-shared-round2 | 1000 | observed | True | True | True | 11.908014710000002 | 991.07 | 11.59 | low-stall |
| 03-ab-shared-round2 | 2000 | observed | True | True | True | 16.72067218999997 | 1967.82 | 9.79 | low-stall |
| 03-ab-shared-round2 | 3000 | observed | True | True | True | 105.27746869999993 | 2440.92 | 15.76 | low-stall |
| 04-ab-baseline-round2 | 1000 | observed | True | True | True | 16.25979079000003 | 991.25 | 10.55 | low-stall |
| 04-ab-baseline-round2 | 2000 | observed | True | True | True | 25.730046110000018 | 1968.42 | 10.86 | low-stall |
| 04-ab-baseline-round2 | 3000 | observed | True | True | True | 152.19700288999965 | 2184.2 | 14.74 | low-stall |
| 05-ab-baseline-round3 | 1000 | observed | True | True | True | 16.13916079000003 | 990.9 | 10.55 | low-stall |
| 05-ab-baseline-round3 | 2000 | observed | True | True | True | 28.58280829999983 | 1968.43 | 10.86 | low-stall |
| 05-ab-baseline-round3 | 3000 | observed | True | True | True | 156.4162702799999 | 2161.78 | 14.78 | low-stall |
| 06-ab-shared-round3 | 1000 | observed | True | True | True | 12.317190099999872 | 990.98 | 11.59 | low-stall |
| 06-ab-shared-round3 | 2000 | observed | True | True | True | 20.450168790000102 | 1967.58 | 9.79 | low-stall |
| 06-ab-shared-round3 | 3000 | observed | True | True | True | 106.44037112999999 | 2406.35 | 15.77 | low-stall |
