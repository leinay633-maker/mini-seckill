# Consumer/pool-budget retest — all planned cells

No performance conclusion is pre-filled. Diagnostic rows include jstack overhead and must not be pooled with A/B.
60s SUCCESS/s is a sampling estimate; not long-run sustainable TPS. Drain tail includes a 5s quiescence guard.
Disk grouping was fixed before this run: write await >2ms in >=5 observed seconds = noisy; <90% coverage = unknown.
No row is excluded. Null means missing/unknown, not zero. Full pool/disk/final-state evidence is in summary.json.

| Case | Rate | Status | Admission | Correctness | 40-budget | p99 ms | SUCCESS/s estimate | Drain tail s | Disk |
|---|---:|---|---|---|---|---:|---:|---:|---|
| 01-ab-baseline-round1 | 1000 | observed | True | True | True | 19.074425110000096 | 994.85 | 8.41 | low-stall |
| 01-ab-baseline-round1 | 2000 | observed | True | True | True | 16.184913310000056 | 1964.35 | 7.5 | low-stall |
| 01-ab-baseline-round1 | 3000 | observed | True | True | True | 51.96339200999986 | 2959.7 | 5.55 | low-stall |
| 01-ab-baseline-round1 | 3500 | observed | True | True | True | 61.92664099999995 | 3293.27 | 11.01 | low-stall |
| 01-ab-baseline-round1 | 4000 | observed | True | True | True | 127.6662801999997 | 2699.7 | 22.17 | low-stall |
| 01-ab-baseline-round1 | 4500 | observed | True | True | True | 150.89469181 | 2050.55 | 31.42 | low-stall |
| 01-ab-baseline-round1 | 5000 | observed | True | True | True | 554.8549836099997 | 1663.73 | 34.2 | low-stall |
| 02-ab-shared-round1 | 1000 | observed | True | True | True | 17.205139310000067 | 996.4 | 11.51 | low-stall |
| 02-ab-shared-round1 | 2000 | observed | True | True | True | 28.939357089999984 | 1969.23 | 12.79 | low-stall |
| 02-ab-shared-round1 | 3000 | observed | True | True | True | 169.1014415999999 | 2937.48 | 9.85 | low-stall |
| 02-ab-shared-round1 | 3500 | observed | True | True | True | 168.46372619999923 | 2388.57 | 13.34 | low-stall |
| 02-ab-shared-round1 | 4000 | observed | False | True | True | 1136.4051748500003 | 1931.27 | 24.33 | low-stall |
| 02-ab-shared-round1 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 02-ab-shared-round1 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 03-ab-shared-round2 | 1000 | observed | True | True | True | 20.05619061000001 | 996.67 | 10.48 | low-stall |
| 03-ab-shared-round2 | 2000 | observed | True | True | True | 28.381461700000003 | 1962.97 | 12.83 | low-stall |
| 03-ab-shared-round2 | 3000 | observed | True | True | True | 111.82738959999982 | 2936.67 | 9.82 | low-stall |
| 03-ab-shared-round2 | 3500 | observed | True | True | True | 188.09555701999997 | 2182.87 | 23.0 | low-stall |
| 03-ab-shared-round2 | 4000 | observed | False | True | True | 1369.2572067999997 | 1846.65 | 22.1 | low-stall |
| 03-ab-shared-round2 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 03-ab-shared-round2 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 04-ab-baseline-round2 | 1000 | observed | True | True | True | 15.92349279999995 | 995.92 | 10.43 | low-stall |
| 04-ab-baseline-round2 | 2000 | observed | True | True | True | 22.695118699999885 | 1967.58 | 11.7 | low-stall |
| 04-ab-baseline-round2 | 3000 | observed | True | True | True | 40.095866629999946 | 2965.32 | 9.82 | low-stall |
| 04-ab-baseline-round2 | 3500 | observed | True | True | True | 61.8208819999996 | 3215.8 | 13.26 | low-stall |
| 04-ab-baseline-round2 | 4000 | observed | True | True | True | 86.68557609999995 | 2645.63 | 23.29 | low-stall |
| 04-ab-baseline-round2 | 4500 | observed | True | True | True | 118.56425319 | 2025.6 | 31.22 | low-stall |
| 04-ab-baseline-round2 | 5000 | observed | False | True | True | 674.5402193000001 | 1612.53 | 34.05 | low-stall |
| 05-ab-baseline-round3 | 1000 | observed | True | True | True | 14.121603890000031 | 982.72 | 10.44 | low-stall |
| 05-ab-baseline-round3 | 2000 | observed | True | True | True | 15.220385579999945 | 1968.43 | 6.45 | low-stall |
| 05-ab-baseline-round3 | 3000 | observed | True | True | True | 35.71697820999989 | 2958.78 | 5.57 | low-stall |
| 05-ab-baseline-round3 | 3500 | observed | True | True | True | 59.36602259999993 | 3316.77 | 13.17 | low-stall |
| 05-ab-baseline-round3 | 4000 | observed | True | True | True | 81.52854560999998 | 2867.73 | 17.74 | low-stall |
| 05-ab-baseline-round3 | 4500 | observed | False | True | True | 945.6799261799994 | 2144.85 | 29.06 | low-stall |
| 05-ab-baseline-round3 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 06-ab-shared-round3 | 1000 | observed | True | True | True | 18.093059800000056 | 997.08 | 12.54 | low-stall |
| 06-ab-shared-round3 | 2000 | observed | True | True | True | 25.548021499999994 | 1963.57 | 11.71 | low-stall |
| 06-ab-shared-round3 | 3000 | observed | True | True | True | 116.6642160999999 | 2933.73 | 8.74 | low-stall |
| 06-ab-shared-round3 | 3500 | observed | True | True | True | 269.136984 | 2246.43 | 24.13 | low-stall |
| 06-ab-shared-round3 | 4000 | observed | False | True | True | 1202.1654662 | 1930.05 | 25.4 | low-stall |
| 06-ab-shared-round3 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 06-ab-shared-round3 | 5000 | not-run | None | None | None | None | None | None | unknown |
