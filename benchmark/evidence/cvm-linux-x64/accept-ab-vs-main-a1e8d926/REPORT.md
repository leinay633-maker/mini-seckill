# Consumer/pool-budget retest — all planned cells

No performance conclusion is pre-filled. Diagnostic rows include jstack overhead and must not be pooled with A/B.
60s SUCCESS/s is a sampling estimate; not long-run sustainable TPS. Drain tail includes a 5s quiescence guard.
Disk grouping was fixed before this run: write await >2ms in >=5 observed seconds = noisy; <90% coverage = unknown.
No row is excluded. Null means missing/unknown, not zero. Full pool/disk/final-state evidence is in summary.json.

| Case | Rate | Status | Admission | Correctness | 40-budget | p99 ms | SUCCESS/s estimate | Drain tail s | Disk |
|---|---:|---|---|---|---|---:|---:|---:|---|
| 01-ab-baseline-round1 | 1000 | observed | True | True | True | 14.875085799999974 | 992.58 | 8.48 | low-stall |
| 01-ab-baseline-round1 | 2000 | observed | True | True | True | 24.13448491999978 | 1973.13 | 7.66 | low-stall |
| 01-ab-baseline-round1 | 3000 | observed | True | True | True | 109.95058599 | 2942.95 | 11.17 | low-stall |
| 01-ab-baseline-round1 | 3500 | observed | False | True | True | 897.0740832299998 | 2173.45 | 16.17 | low-stall |
| 01-ab-baseline-round1 | 4000 | not-run | None | None | None | None | None | None | unknown |
| 01-ab-baseline-round1 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 01-ab-baseline-round1 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 01-ab-baseline-round1 | 5500 | not-run | None | None | None | None | None | None | unknown |
| 02-ab-shared-round1 | 1000 | observed | True | True | True | 12.68734908999998 | 992.15 | 8.49 | low-stall |
| 02-ab-shared-round1 | 2000 | observed | True | True | True | 23.012041280000204 | 1974.05 | 7.66 | low-stall |
| 02-ab-shared-round1 | 3000 | observed | True | True | True | 120.2658836099999 | 2930.6 | 11.18 | low-stall |
| 02-ab-shared-round1 | 3500 | observed | True | True | True | 400.4737501999999 | 2200.38 | 17.41 | low-stall |
| 02-ab-shared-round1 | 4000 | observed | False | True | True | 1278.38072248 | 1956.78 | 18.87 | low-stall |
| 02-ab-shared-round1 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 02-ab-shared-round1 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 02-ab-shared-round1 | 5500 | not-run | None | None | None | None | None | None | unknown |
| 03-ab-shared-round2 | 1000 | observed | True | True | True | 12.492265000000115 | 992.33 | 7.44 | low-stall |
| 03-ab-shared-round2 | 2000 | observed | True | True | True | 22.76689340000006 | 1972.8 | 14.07 | low-stall |
| 03-ab-shared-round2 | 3000 | observed | True | True | True | 74.55144159999999 | 2931.62 | 10.07 | low-stall |
| 03-ab-shared-round2 | 3500 | observed | True | True | True | 212.7404285099999 | 2085.92 | 17.45 | low-stall |
| 03-ab-shared-round2 | 4000 | observed | False | True | True | 1438.21794112 | 2015.53 | 16.35 | low-stall |
| 03-ab-shared-round2 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 03-ab-shared-round2 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 03-ab-shared-round2 | 5500 | not-run | None | None | None | None | None | None | unknown |
| 04-ab-baseline-round2 | 1000 | observed | True | True | True | 9.975897400000012 | 992.7 | 8.49 | low-stall |
| 04-ab-baseline-round2 | 2000 | observed | True | True | True | 27.731356999999925 | 1973.65 | 8.73 | low-stall |
| 04-ab-baseline-round2 | 3000 | observed | True | True | True | 110.4855586999999 | 2939.82 | 10.07 | low-stall |
| 04-ab-baseline-round2 | 3500 | observed | False | True | True | 812.2231281999993 | 2140.65 | 17.35 | low-stall |
| 04-ab-baseline-round2 | 4000 | not-run | None | None | None | None | None | None | unknown |
| 04-ab-baseline-round2 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 04-ab-baseline-round2 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 04-ab-baseline-round2 | 5500 | not-run | None | None | None | None | None | None | unknown |
| 05-ab-baseline-round3 | 1000 | observed | True | True | True | 11.888893270000029 | 992.68 | 8.49 | low-stall |
| 05-ab-baseline-round3 | 2000 | observed | True | True | True | 20.887487609999923 | 1971.0 | 8.73 | low-stall |
| 05-ab-baseline-round3 | 3000 | observed | True | True | True | 135.3169709099998 | 2932.52 | 10.08 | low-stall |
| 05-ab-baseline-round3 | 3500 | observed | False | True | True | 1603.6027868499923 | 2172.45 | 16.15 | low-stall |
| 05-ab-baseline-round3 | 4000 | not-run | None | None | None | None | None | None | unknown |
| 05-ab-baseline-round3 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 05-ab-baseline-round3 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 05-ab-baseline-round3 | 5500 | not-run | None | None | None | None | None | None | unknown |
| 06-ab-shared-round3 | 1000 | observed | True | True | True | 12.773867430000074 | 992.38 | 8.49 | low-stall |
| 06-ab-shared-round3 | 2000 | observed | True | True | True | 21.651951509999932 | 1971.43 | 7.65 | low-stall |
| 06-ab-shared-round3 | 3000 | observed | True | True | True | 86.34026659999996 | 2909.05 | 11.17 | low-stall |
| 06-ab-shared-round3 | 3500 | observed | True | True | True | 426.0187665999998 | 2169.62 | 17.45 | low-stall |
| 06-ab-shared-round3 | 4000 | observed | False | True | True | 2091.7766261999996 | 2018.32 | 17.42 | low-stall |
| 06-ab-shared-round3 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 06-ab-shared-round3 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 06-ab-shared-round3 | 5500 | not-run | None | None | None | None | None | None | unknown |
