# CI history audit

Audit date: 2026-08-29

Baseline: `a50a958` plus its 39 direct ancestors on `main`.

## Scope and method

- Compared the latest 40 local `main` commits with all 34 GitHub Actions runs available for the branch.
- Read the failed job and step metadata for every failed run.
- Opened all nine failed jobs from the eight failed runs and reviewed their logs.
- Checked each failure against the current source, dependency manifests, tests, packaging rules, and workflow.
- A commit marked `no independent run` has no matching Actions run. It is not counted as a failed run.

## Result

- 40 commits reviewed.
- 34 commits had an independent CI run: 26 succeeded and 8 failed historically.
- 6 commits had no independent CI run.
- All six historical failure classes are covered by later fixes and by checks in the current workflow.
- The baseline run, [CI #34](https://github.com/duyu0218-flash/ai-outbound-platform/actions/runs/33253976419), completed successfully in all five jobs.

## Historical failures and resolution

| Failed commit | Failure class | Evidence from failed job | Resolution | Current regression guard |
| --- | --- | --- | --- | --- |
| `399f97e` | Python package discovery | setuptools found both `app` and `migrations` as top-level packages | `b7559ac` scoped package discovery | explicit `[tool.setuptools.packages.find]` configuration |
| `5143a80` | Packaged frontend missing | backend page test returned `503` | `05ba790` added frontend assets to the package and CI checks | frontend build plus packaged-index assertion |
| `acce4b9` | Packaged frontend missing | backend page test returned `503` | `05ba790` added frontend assets to the package and CI checks | frontend build plus packaged-index assertion |
| `1b06985` | Agent dependency missing | `ModuleNotFoundError: pydantic_settings` | `453e7d4` declared the dependency | `pip check`, compile, and agent tests |
| `8b73b18` | Dispatch concurrency regression | expected three dispatched calls but observed two | `b6b3931` corrected retry/concurrency handling | concurrency unit and integration tests |
| `b0fd1f1` | Voice gateway test dependency missing | Starlette could not import `httpx2` | `a07854e` declared `httpx2` for development tests | clean Python install, `pip check`, compile, and gateway tests |
| `c89a651` | Wall-clock-dependent tests | 20 backend failures and 21 PostgreSQL failures outside configured calling hours | `1148c01` made test calling hours deterministic | autouse test fixture plus both SQLite and PostgreSQL suites |
| `1148c01` | Integration job skipped frontend build | page test returned `503`; 41 tests passed and one failed | `a50a958` builds and verifies frontend assets in the PostgreSQL/Redis job | both backend jobs build and verify packaged frontend |

## Commit-by-commit inventory

| # | Commit | CI result | Run |
| ---: | --- | --- | ---: |
| 1 | `a50a958` | success | 34 |
| 2 | `1148c01` | historical failure, resolved | 33 |
| 3 | `c89a651` | historical failure, resolved | 32 |
| 4 | `7ac699c` | no independent run | - |
| 5 | `8147226` | no independent run | - |
| 6 | `a07854e` | success | 31 |
| 7 | `b0fd1f1` | historical failure, resolved | 30 |
| 8 | `959a3b7` | success | 29 |
| 9 | `75f1470` | no independent run | - |
| 10 | `71c686c` | success | 28 |
| 11 | `b4cf90f` | success | 27 |
| 12 | `5b84970` | success | 26 |
| 13 | `0a6e3aa` | success | 25 |
| 14 | `8b73b18` | historical failure, resolved | 24 |
| 15 | `b6b3931` | success | 23 |
| 16 | `453e7d4` | success | 22 |
| 17 | `1b06985` | historical failure, resolved | 21 |
| 18 | `05ba790` | success | 20 |
| 19 | `acce4b9` | historical failure, resolved | 19 |
| 20 | `5143a80` | historical failure, resolved | 18 |
| 21 | `7efad7e` | success | 17 |
| 22 | `1bff129` | success | 16 |
| 23 | `b7559ac` | success | 15 |
| 24 | `399f97e` | historical failure, resolved | 14 |
| 25 | `27e5d24` | no independent run | - |
| 26 | `6d23757` | no independent run | - |
| 27 | `cf83a0f` | success | 13 |
| 28 | `66f8921` | success | 12 |
| 29 | `f151004` | success | 11 |
| 30 | `aa0c3f4` | success | 10 |
| 31 | `3e8f5c4` | success | 9 |
| 32 | `2a72c19` | success | 8 |
| 33 | `1db23e9` | success | 7 |
| 34 | `13f20a4` | success | 6 |
| 35 | `89b9132` | success | 5 |
| 36 | `33a7527` | success | 4 |
| 37 | `d9d74fd` | success | 3 |
| 38 | `194e244` | success | 2 |
| 39 | `93c90cd` | success | 1 |
| 40 | `b764913` | no independent run | - |

## Interpretation

GitHub keeps the original result of an old workflow run, so later fixes do not turn an old red run green. The supported delivery signal is the current `main` commit and its complete workflow result. Rewriting or force-pushing history solely to hide old failures is intentionally not performed.
