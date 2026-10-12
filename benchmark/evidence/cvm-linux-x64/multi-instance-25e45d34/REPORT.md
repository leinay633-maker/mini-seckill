# Consumer/pool-budget retest — all planned cells

No performance conclusion is pre-filled. Diagnostic rows include jstack overhead and must not be pooled with A/B.
60s SUCCESS/s is a sampling estimate; not long-run sustainable TPS. Drain tail includes a 5s quiescence guard.
Disk grouping was fixed before this run: write await >2ms in >=5 observed seconds = noisy; <90% coverage = unknown.
No row is excluded. Null means missing/unknown, not zero. Full pool/disk/final-state evidence is in summary.json.

| Case | Rate | Status | Admission | Correctness | 40-budget | p99 ms | SUCCESS/s estimate | Drain tail s | Disk |
|---|---:|---|---|---|---|---:|---:|---:|---|
| 01-ablation-shared-round1 | 1000 | observed | True | True | True | 10.083960390000007 | 992.0 | 13.69 | low-stall |
| 01-ablation-shared-round1 | 2000 | observed | True | True | True | 23.590977390000006 | 1974.3 | 13.01 | low-stall |
| 01-ablation-shared-round1 | 3000 | observed | True | True | True | 131.5359235500002 | 2908.8 | 10.07 | low-stall |
| 01-ablation-shared-round1 | 3500 | observed | True | True | True | 477.4807206999999 | 2145.53 | 17.42 | low-stall |
| 01-ablation-shared-round1 | 4000 | observed | False | True | True | 1607.059650079996 | 1982.2 | 16.32 | low-stall |
| 01-ablation-shared-round1 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 01-ablation-shared-round1 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 02-ablation-multi3-round1 | 1000 | observed | True | True | True | 13.974276699999999 | 993.62 | 12.66 | low-stall |
| 02-ablation-multi3-round1 | 2000 | observed | True | True | True | 16.586817509999992 | 1974.82 | 7.67 | low-stall |
| 02-ablation-multi3-round1 | 3000 | observed | True | True | True | 250.89488694000013 | 2483.53 | 10.38 | low-stall |
| 02-ablation-multi3-round1 | 3500 | observed | False | True | True | 1750.7698863300004 | 1972.63 | 19.66 | low-stall |
| 02-ablation-multi3-round1 | 4000 | not-run | None | None | None | None | None | None | unknown |
| 02-ablation-multi3-round1 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 02-ablation-multi3-round1 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 03-ablation-multi3-round2 | 1000 | observed | True | True | True | 10.298529720000026 | 994.03 | 8.49 | low-stall |
| 03-ablation-multi3-round2 | 2000 | observed | True | True | True | 17.660064489999964 | 1977.43 | 7.67 | low-stall |
| 03-ablation-multi3-round2 | 3000 | observed | True | True | True | 265.7336591099999 | 2413.2 | 10.38 | low-stall |
| 03-ablation-multi3-round2 | 3500 | observed | False | True | True | 1308.9136615 | 1942.2 | 19.98 | low-stall |
| 03-ablation-multi3-round2 | 4000 | not-run | None | None | None | None | None | None | unknown |
| 03-ablation-multi3-round2 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 03-ablation-multi3-round2 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 04-ablation-shared-round2 | 1000 | observed | True | True | True | 11.836429520000042 | 992.23 | 12.66 | low-stall |
| 04-ablation-shared-round2 | 2000 | observed | True | True | True | 24.38381280999982 | 1969.82 | 8.73 | low-stall |
| 04-ablation-shared-round2 | 3000 | observed | True | True | True | 77.96643350000015 | 2933.18 | 10.07 | low-stall |
| 04-ablation-shared-round2 | 3500 | observed | True | True | True | 283.08934790999996 | 2211.62 | 18.56 | low-stall |
| 04-ablation-shared-round2 | 4000 | observed | False | True | True | 1598.8345061400007 | 2084.38 | 16.17 | low-stall |
| 04-ablation-shared-round2 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 04-ablation-shared-round2 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 05-ablation-shared-round3 | 1000 | observed | True | True | True | 11.0442212 | 992.43 | 8.48 | low-stall |
| 05-ablation-shared-round3 | 2000 | observed | True | True | True | 22.14364150000002 | 1974.2 | 7.68 | low-stall |
| 05-ablation-shared-round3 | 3000 | observed | True | True | True | 74.26174649999993 | 2935.2 | 11.17 | low-stall |
| 05-ablation-shared-round3 | 3500 | observed | True | True | True | 181.5789584 | 2135.83 | 17.44 | low-stall |
| 05-ablation-shared-round3 | 4000 | observed | False | True | True | 1374.55979863 | 1979.33 | 16.34 | low-stall |
| 05-ablation-shared-round3 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 05-ablation-shared-round3 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 06-ablation-multi3-round3 | 1000 | observed | True | True | True | 12.25788849000001 | 994.47 | 13.71 | low-stall |
| 06-ablation-multi3-round3 | 2000 | observed | True | True | True | 20.278198109999995 | 1980.18 | 7.67 | low-stall |
| 06-ablation-multi3-round3 | 3000 | observed | False | True | True | 870.1551245500008 | 2284.77 | 110.4 | low-stall |
| 06-ablation-multi3-round3 | 3500 | not-run | None | None | None | None | None | None | unknown |
| 06-ablation-multi3-round3 | 4000 | not-run | None | None | None | None | None | None | unknown |
| 06-ablation-multi3-round3 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 06-ablation-multi3-round3 | 5000 | not-run | None | None | None | None | None | None | unknown |
