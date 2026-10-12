# Consumer/pool-budget retest — all planned cells

No performance conclusion is pre-filled. Diagnostic rows include jstack overhead and must not be pooled with A/B.
60s SUCCESS/s is a sampling estimate; not long-run sustainable TPS. Drain tail includes a 5s quiescence guard.
Disk grouping was fixed before this run: write await >2ms in >=5 observed seconds = noisy; <90% coverage = unknown.
No row is excluded. Null means missing/unknown, not zero. Full pool/disk/final-state evidence is in summary.json.

| Case | Rate | Status | Admission | Correctness | 40-budget | p99 ms | SUCCESS/s estimate | Drain tail s | Disk |
|---|---:|---|---|---|---|---:|---:|---:|---|
| 01-ablation-shared-round1 | 2000 | observed | True | True | True | 21.250412309999923 | 1967.32 | 14.06 | low-stall |
| 01-ablation-shared-round1 | 3000 | observed | True | True | True | 83.18784889999964 | 2916.83 | 10.05 | low-stall |
| 01-ablation-shared-round1 | 3500 | observed | True | True | True | 276.6601227 | 2081.48 | 17.45 | low-stall |
| 01-ablation-shared-round1 | 4000 | observed | False | True | True | 1539.5279078200006 | 2075.87 | 16.19 | low-stall |
| 01-ablation-shared-round1 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 01-ablation-shared-round1 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 02-ablation-chan256-round1 | 2000 | observed | True | True | True | 26.17609420000006 | 1965.9 | 7.65 | low-stall |
| 02-ablation-chan256-round1 | 3000 | observed | True | True | True | 63.01347339000007 | 2925.68 | 10.06 | low-stall |
| 02-ablation-chan256-round1 | 3500 | observed | True | True | True | 129.34130041 | 2473.02 | 18.39 | low-stall |
| 02-ablation-chan256-round1 | 4000 | observed | True | True | True | 290.94379510000005 | 1955.5 | 21.32 | low-stall |
| 02-ablation-chan256-round1 | 4500 | observed | False | True | True | 1340.6038607999997 | 1913.23 | 20.17 | low-stall |
| 02-ablation-chan256-round1 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 03-ablation-chan256-round2 | 2000 | observed | True | True | True | 17.466477289999983 | 1971.37 | 12.99 | low-stall |
| 03-ablation-chan256-round2 | 3000 | observed | True | True | True | 72.4151125 | 2929.88 | 11.15 | low-stall |
| 03-ablation-chan256-round2 | 3500 | observed | True | True | True | 178.62215833999997 | 2492.02 | 17.26 | low-stall |
| 03-ablation-chan256-round2 | 4000 | observed | True | True | True | 345.34879638999996 | 1951.43 | 21.38 | low-stall |
| 03-ablation-chan256-round2 | 4500 | observed | False | True | True | 1371.5758184 | 1956.52 | 20.05 | low-stall |
| 03-ablation-chan256-round2 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 04-ablation-shared-round2 | 2000 | observed | True | True | True | 28.435647489999976 | 1972.18 | 8.72 | low-stall |
| 04-ablation-shared-round2 | 3000 | observed | True | True | True | 69.13030299999954 | 2927.78 | 10.06 | low-stall |
| 04-ablation-shared-round2 | 3500 | observed | True | True | True | 263.74139599999995 | 2040.93 | 22.97 | low-stall |
| 04-ablation-shared-round2 | 4000 | observed | False | True | True | 2194.1339214999994 | 1923.57 | 47.54 | low-stall |
| 04-ablation-shared-round2 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 04-ablation-shared-round2 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 05-ablation-shared-round3 | 2000 | observed | True | True | True | 22.02548841000002 | 1968.07 | 12.99 | low-stall |
| 05-ablation-shared-round3 | 3000 | observed | True | True | True | 109.20977258000042 | 2917.78 | 11.15 | low-stall |
| 05-ablation-shared-round3 | 3500 | observed | True | True | True | 506.59426200999997 | 2123.68 | 17.42 | low-stall |
| 05-ablation-shared-round3 | 4000 | observed | False | True | True | 1548.7401272500001 | 1903.03 | 15.25 | low-stall |
| 05-ablation-shared-round3 | 4500 | not-run | None | None | None | None | None | None | unknown |
| 05-ablation-shared-round3 | 5000 | not-run | None | None | None | None | None | None | unknown |
| 06-ablation-chan256-round3 | 2000 | observed | True | True | True | 20.12750829999998 | 1970.45 | 7.66 | low-stall |
| 06-ablation-chan256-round3 | 3000 | observed | True | True | True | 78.79086520999985 | 2921.77 | 11.16 | low-stall |
| 06-ablation-chan256-round3 | 3500 | observed | True | True | True | 173.07876944999984 | 2555.53 | 18.3 | low-stall |
| 06-ablation-chan256-round3 | 4000 | observed | True | True | True | 457.3747390000001 | 2023.53 | 20.24 | low-stall |
| 06-ablation-chan256-round3 | 4500 | observed | False | True | True | 1283.0749090999998 | 1896.78 | 20.14 | low-stall |
| 06-ablation-chan256-round3 | 5000 | not-run | None | None | None | None | None | None | unknown |
