# Consumer/pool-budget retest — all planned cells

No performance conclusion is pre-filled. Diagnostic rows include jstack overhead and must not be pooled with A/B.
60s SUCCESS/s is a sampling estimate; not long-run sustainable TPS. Drain tail includes a 5s quiescence guard.
Disk grouping was fixed before this run: write await >2ms in >=5 observed seconds = noisy; <90% coverage = unknown.
No row is excluded. Null means missing/unknown, not zero. Full pool/disk/final-state evidence is in summary.json.

| Case | Rate | Status | Admission | Correctness | 40-budget | p99 ms | SUCCESS/s estimate | Drain tail s | Disk |
|---|---:|---|---|---|---|---:|---:|---:|---|
| 01-ab-baseline-round1 | 1000 | observed | True | True | True | 14.794265599999983 | 994.55 | 9.6 | low-stall |
| 01-ab-baseline-round1 | 2000 | observed | True | True | True | 22.046438789999996 | 1966.13 | 7.52 | low-stall |
| 01-ab-baseline-round1 | 3000 | observed | True | True | True | 41.32369449999959 | 2955.67 | 5.52 | low-stall |
| 01-ab-baseline-round1 | 3500 | observed | True | True | True | 60.642583399999985 | 3341.98 | 14.43 | low-stall |
| 01-ab-baseline-round1 | 4000 | observed | True | True | True | 87.85708958999983 | 2802.55 | 16.67 | low-stall |
| 01-ab-baseline-round1 | 4500 | observed | True | True | True | 195.13099094999978 | 2033.22 | 31.33 | low-stall |
| 01-ab-baseline-round1 | 5000 | observed | True | True | True | 318.55376369000004 | 1706.07 | 36.12 | low-stall |
| 02-ab-shared-round1 | 1000 | observed | True | True | True | 38.545191889999856 | 995.05 | 12.52 | low-stall |
| 02-ab-shared-round1 | 2000 | observed | True | True | True | 16.866978709999984 | 1968.17 | 7.51 | low-stall |
| 02-ab-shared-round1 | 3000 | observed | True | True | True | 60.77366378999995 | 2942.45 | 5.59 | low-stall |
| 02-ab-shared-round1 | 3500 | observed | True | True | True | 68.71124709999997 | 3119.95 | 14.3 | low-stall |
| 02-ab-shared-round1 | 4000 | observed | True | True | True | 107.61133059999985 | 2573.18 | 22.24 | low-stall |
| 02-ab-shared-round1 | 4500 | observed | True | True | True | 182.06999368000004 | 1874.87 | 30.14 | low-stall |
| 02-ab-shared-round1 | 5000 | observed | False | True | True | 1126.5030249999995 | 1726.3 | 30.49 | low-stall |
| 03-ab-shared-round2 | 1000 | observed | True | True | True | 72.18201129000008 | 994.87 | 11.47 | low-stall |
| 03-ab-shared-round2 | 2000 | observed | True | True | True | 18.22780159999999 | 1967.8 | 12.8 | low-stall |
| 03-ab-shared-round2 | 3000 | observed | True | True | True | 53.18804310000004 | 2960.47 | 10.91 | low-stall |
| 03-ab-shared-round2 | 3500 | observed | True | True | True | 80.4602493 | 2969.12 | 13.17 | low-stall |
| 03-ab-shared-round2 | 4000 | observed | True | True | True | 128.1407635 | 2331.9 | 23.26 | low-stall |
| 03-ab-shared-round2 | 4500 | observed | True | True | True | 281.01094480999996 | 1755.77 | 31.21 | low-stall |
| 03-ab-shared-round2 | 5000 | observed | False | True | True | 1053.2529256000005 | 1707.5 | 30.28 | low-stall |
| 04-ab-baseline-round2 | 1000 | observed | True | True | True | 13.683791690000081 | 997.43 | 10.42 | low-stall |
| 04-ab-baseline-round2 | 2000 | observed | True | True | True | 16.09662901999994 | 1966.42 | 7.54 | low-stall |
| 04-ab-baseline-round2 | 3000 | observed | True | True | True | 51.239101779999785 | 2965.58 | 5.54 | low-stall |
| 04-ab-baseline-round2 | 3500 | observed | True | True | True | 66.9196971 | 3385.75 | 13.18 | low-stall |
| 04-ab-baseline-round2 | 4000 | observed | True | True | True | 81.67789869999996 | 2770.25 | 22.24 | low-stall |
| 04-ab-baseline-round2 | 4500 | observed | True | True | True | 135.51761422999957 | 2059.42 | 30.2 | low-stall |
| 04-ab-baseline-round2 | 5000 | observed | True | True | True | 359.514425 | 1665.13 | 35.31 | low-stall |
| 05-ab-baseline-round3 | 1000 | observed | True | True | True | 14.603524100000044 | 995.22 | 11.46 | low-stall |
| 05-ab-baseline-round3 | 2000 | observed | True | True | True | 19.697188909999994 | 1968.33 | 12.79 | low-stall |
| 05-ab-baseline-round3 | 3000 | observed | True | True | True | 43.93743599999999 | 2960.83 | 10.9 | low-stall |
| 05-ab-baseline-round3 | 3500 | observed | True | True | True | 90.3169195199999 | 3347.2 | 13.3 | low-stall |
| 05-ab-baseline-round3 | 4000 | observed | True | True | True | 96.11890900999984 | 2815.55 | 17.77 | low-stall |
| 05-ab-baseline-round3 | 4500 | observed | True | True | True | 152.7178656699998 | 2127.23 | 31.42 | low-stall |
| 05-ab-baseline-round3 | 5000 | observed | False | True | True | 800.9040160999999 | 1649.52 | 34.98 | low-stall |
| 06-ab-shared-round3 | 1000 | observed | True | True | True | 17.95633819000004 | 994.05 | 9.41 | low-stall |
| 06-ab-shared-round3 | 2000 | observed | True | True | True | 20.1691095899999 | 1962.63 | 11.75 | low-stall |
| 06-ab-shared-round3 | 3000 | observed | True | True | True | 48.52979899999979 | 2941.3 | 11.95 | low-stall |
| 06-ab-shared-round3 | 3500 | observed | True | True | True | 99.06081599 | 3073.02 | 13.27 | low-stall |
| 06-ab-shared-round3 | 4000 | observed | True | True | True | 124.64457970000004 | 2442.18 | 23.27 | low-stall |
| 06-ab-shared-round3 | 4500 | observed | True | True | True | 280.46922380999985 | 1928.03 | 31.26 | low-stall |
| 06-ab-shared-round3 | 5000 | observed | False | True | True | 1025.615692580001 | 1731.65 | 33.63 | low-stall |
