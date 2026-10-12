# Consumer/pool-budget retest — all planned cells

No performance conclusion is pre-filled. Diagnostic rows include jstack overhead and must not be pooled with A/B.
60s SUCCESS/s is a sampling estimate; not long-run sustainable TPS. Drain tail includes a 5s quiescence guard.
Disk grouping was fixed before this run: write await >2ms in >=5 observed seconds = noisy; <90% coverage = unknown.
No row is excluded. Null means missing/unknown, not zero. Full pool/disk/final-state evidence is in summary.json.

| Case | Rate | Status | Admission | Correctness | 40-budget | p99 ms | SUCCESS/s estimate | Drain tail s | Disk |
|---|---:|---|---|---|---|---:|---:|---:|---|
| 01-ablation-shared-round1 | 1000 | observed | True | True | True | 10.523625999999954 | 992.58 | 7.45 | low-stall |
| 01-ablation-shared-round1 | 2000 | observed | True | True | True | 30.43926783999996 | 1972.2 | 8.73 | low-stall |
| 01-ablation-shared-round1 | 3000 | observed | True | True | True | 83.56024418999999 | 2933.28 | 10.07 | low-stall |
| 01-ablation-shared-round1 | 3500 | observed | True | True | True | 222.14986779999998 | 2166.48 | 18.49 | low-stall |
| 01-ablation-shared-round1 | 4000 | observed | False | True | True | 1711.38820502 | 2047.73 | 16.2 | low-stall |
| 01-ablation-shared-round1 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 01-ablation-shared-round1 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 02-ablation-unsharded-round1 | 1000 | observed | True | True | True | 8.126026320000138 | 992.8 | 8.49 | low-stall |
| 02-ablation-unsharded-round1 | 2000 | observed | True | True | True | 17.88185598999968 | 1972.38 | 7.66 | low-stall |
| 02-ablation-unsharded-round1 | 3000 | observed | True | True | True | 65.8452719 | 2936.88 | 6.79 | low-stall |
| 02-ablation-unsharded-round1 | 3500 | observed | False | True | True | 725.7285128999998 | 2228.52 | 21.84 | low-stall |
| 02-ablation-unsharded-round1 | 4000 | not-run | None | None | None | None | None | None | unknown |
| 02-ablation-unsharded-round1 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 02-ablation-unsharded-round1 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 03-ablation-unsharded-round2 | 1000 | observed | True | True | True | 9.844799890000012 | 992.08 | 8.48 | low-stall |
| 03-ablation-unsharded-round2 | 2000 | observed | True | True | True | 19.432686789999966 | 1975.32 | 8.73 | low-stall |
| 03-ablation-unsharded-round2 | 3000 | observed | True | True | True | 59.44954281000003 | 2955.28 | 5.68 | low-stall |
| 03-ablation-unsharded-round2 | 3500 | observed | True | True | True | 652.8712104999998 | 2109.72 | 21.91 | low-stall |
| 03-ablation-unsharded-round2 | 4000 | observed | False | True | True | 1645.90311467 | 2141.58 | 16.11 | low-stall |
| 03-ablation-unsharded-round2 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 03-ablation-unsharded-round2 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 04-ablation-shared-round2 | 1000 | observed | True | True | True | 13.713568700000085 | 992.03 | 8.48 | low-stall |
| 04-ablation-shared-round2 | 2000 | observed | True | True | True | 19.057467909999996 | 1973.13 | 7.66 | low-stall |
| 04-ablation-shared-round2 | 3000 | observed | True | True | True | 78.79375628999998 | 2934.75 | 5.69 | low-stall |
| 04-ablation-shared-round2 | 3500 | observed | True | True | True | 253.68547549999994 | 2174.33 | 18.53 | low-stall |
| 04-ablation-shared-round2 | 4000 | observed | False | True | True | 1459.6981747999996 | 2026.53 | 15.11 | low-stall |
| 04-ablation-shared-round2 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 04-ablation-shared-round2 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 05-ablation-shared-round3 | 1000 | observed | True | True | True | 9.883862 | 993.3 | 8.48 | low-stall |
| 05-ablation-shared-round3 | 2000 | observed | True | True | True | 21.707814609999982 | 1971.78 | 7.66 | low-stall |
| 05-ablation-shared-round3 | 3000 | observed | True | True | True | 79.09176630999997 | 2935.45 | 11.16 | low-stall |
| 05-ablation-shared-round3 | 3500 | observed | True | True | True | 266.85957649999904 | 2152.25 | 17.49 | low-stall |
| 05-ablation-shared-round3 | 4000 | observed | False | True | True | 2269.234830300001 | 2007.28 | 21.88 | low-stall |
| 05-ablation-shared-round3 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 05-ablation-shared-round3 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 06-ablation-unsharded-round3 | 1000 | observed | True | True | True | 9.697049210000019 | 992.63 | 12.65 | low-stall |
| 06-ablation-unsharded-round3 | 2000 | observed | True | True | True | 17.720868999999997 | 1970.78 | 14.07 | low-stall |
| 06-ablation-unsharded-round3 | 3000 | observed | True | True | True | 55.67145557999995 | 2946.92 | 10.07 | low-stall |
| 06-ablation-unsharded-round3 | 3500 | observed | False | True | True | 993.7558555299993 | 2318.7 | 16.19 | low-stall |
| 06-ablation-unsharded-round3 | 4000 | not-run | None | None | None | None | None | None | unknown |
| 06-ablation-unsharded-round3 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 06-ablation-unsharded-round3 | 5000 | not-run | None | None | None | None | None | None | unknown |
