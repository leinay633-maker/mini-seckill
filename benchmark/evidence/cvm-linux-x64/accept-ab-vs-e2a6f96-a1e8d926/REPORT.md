# Consumer/pool-budget retest — all planned cells

No performance conclusion is pre-filled. Diagnostic rows include jstack overhead and must not be pooled with A/B.
60s SUCCESS/s is a sampling estimate; not long-run sustainable TPS. Drain tail includes a 5s quiescence guard.
Disk grouping was fixed before this run: write await >2ms in >=5 observed seconds = noisy; <90% coverage = unknown.
No row is excluded. Null means missing/unknown, not zero. Full pool/disk/final-state evidence is in summary.json.

| Case | Rate | Status | Admission | Correctness | 40-budget | p99 ms | SUCCESS/s estimate | Drain tail s | Disk |
|---|---:|---|---|---|---|---:|---:|---:|---|
| 01-ab-baseline-round1 | 1000 | observed | True | True | True | 7.983588099999981 | 992.35 | 13.7 | low-stall |
| 01-ab-baseline-round1 | 2000 | observed | True | True | True | 34.87877213000001 | 1969.47 | 13.01 | low-stall |
| 01-ab-baseline-round1 | 3000 | observed | True | True | True | 60.29972550999993 | 2943.05 | 10.07 | low-stall |
| 01-ab-baseline-round1 | 3500 | observed | True | True | True | 360.11889610000054 | 2443.7 | 17.35 | low-stall |
| 01-ab-baseline-round1 | 4000 | observed | False | True | True | 1637.6604087400012 | 2378.17 | 11.62 | low-stall |
| 01-ab-baseline-round1 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 01-ab-baseline-round1 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 01-ab-baseline-round1 | 5500 | not-run | None | None | None | None | None | None | unknown |
| 02-ab-shared-round1 | 1000 | observed | True | True | True | 12.273059930000072 | 993.37 | 7.44 | low-stall |
| 02-ab-shared-round1 | 2000 | observed | True | True | True | 25.894781809999984 | 1973.77 | 8.72 | low-stall |
| 02-ab-shared-round1 | 3000 | observed | True | True | True | 73.21717039999997 | 2933.82 | 11.16 | low-stall |
| 02-ab-shared-round1 | 3500 | observed | True | True | True | 199.7931138599999 | 2220.32 | 17.42 | low-stall |
| 02-ab-shared-round1 | 4000 | observed | False | True | True | 1488.5590533599986 | 2068.05 | 16.24 | low-stall |
| 02-ab-shared-round1 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 02-ab-shared-round1 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 02-ab-shared-round1 | 5500 | not-run | None | None | None | None | None | None | unknown |
| 03-ab-shared-round2 | 1000 | observed | True | True | True | 12.200768489999945 | 992.32 | 8.48 | low-stall |
| 03-ab-shared-round2 | 2000 | observed | True | True | True | 23.35798239999996 | 1969.53 | 7.66 | low-stall |
| 03-ab-shared-round2 | 3000 | observed | True | True | True | 75.58520055999999 | 2931.45 | 6.79 | low-stall |
| 03-ab-shared-round2 | 3500 | observed | True | True | True | 277.21028836999966 | 2148.7 | 21.89 | low-stall |
| 03-ab-shared-round2 | 4000 | observed | False | True | True | 2123.281786079999 | 2136.12 | 81.77 | low-stall |
| 03-ab-shared-round2 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 03-ab-shared-round2 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 03-ab-shared-round2 | 5500 | not-run | None | None | None | None | None | None | unknown |
| 04-ab-baseline-round2 | 1000 | observed | True | True | True | 10.094342190000024 | 992.22 | 7.45 | low-stall |
| 04-ab-baseline-round2 | 2000 | observed | True | True | True | 34.792300300000065 | 1972.4 | 8.73 | low-stall |
| 04-ab-baseline-round2 | 3000 | observed | True | True | True | 45.080581909999964 | 2941.83 | 10.06 | low-stall |
| 04-ab-baseline-round2 | 3500 | observed | True | True | True | 578.6080483 | 2390.78 | 17.38 | low-stall |
| 04-ab-baseline-round2 | 4000 | observed | False | True | True | 1592.18396697 | 2343.32 | 11.57 | low-stall |
| 04-ab-baseline-round2 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 04-ab-baseline-round2 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 04-ab-baseline-round2 | 5500 | not-run | None | None | None | None | None | None | unknown |
| 05-ab-baseline-round3 | 1000 | observed | True | True | True | 7.428538900000024 | 992.18 | 7.45 | low-stall |
| 05-ab-baseline-round3 | 2000 | observed | True | True | True | 23.70276020999989 | 1971.33 | 8.74 | low-stall |
| 05-ab-baseline-round3 | 3000 | observed | True | True | True | 59.430649799999934 | 2950.25 | 10.07 | low-stall |
| 05-ab-baseline-round3 | 3500 | observed | True | True | True | 463.0417802999995 | 2536.58 | 17.26 | low-stall |
| 05-ab-baseline-round3 | 4000 | observed | False | True | True | 1574.75892205 | 2368.32 | 11.64 | low-stall |
| 05-ab-baseline-round3 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 05-ab-baseline-round3 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 05-ab-baseline-round3 | 5500 | not-run | None | None | None | None | None | None | unknown |
| 06-ab-shared-round3 | 1000 | observed | True | True | True | 9.756799200000113 | 992.68 | 7.44 | low-stall |
| 06-ab-shared-round3 | 2000 | observed | True | True | True | 19.97907449999987 | 1973.28 | 8.73 | low-stall |
| 06-ab-shared-round3 | 3000 | observed | True | True | True | 64.43969720000018 | 2932.4 | 11.16 | low-stall |
| 06-ab-shared-round3 | 3500 | observed | True | True | True | 624.7418964999996 | 2233.15 | 16.34 | low-stall |
| 06-ab-shared-round3 | 4000 | observed | False | True | True | 1601.8753096899998 | 2010.13 | 16.28 | low-stall |
| 06-ab-shared-round3 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 06-ab-shared-round3 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 06-ab-shared-round3 | 5500 | not-run | None | None | None | None | None | None | unknown |
