# HassGlass — Claude working instructions

## BEFORE EVERY COMMIT — run all gates, fix every failure, then commit

```bash
venv/bin/ruff check custom_components/ tests/
venv/bin/ruff format --check custom_components/ tests/
venv/bin/mypy custom_components/
venv/bin/pytest tests/ -q
```

All four must be clean. Do not commit if any gate fails. Fix the failures first.

## Project layout

- `custom_components/hassglass/` — Home Assistant integration (Python)
- `apps/glass_agent/` — Android agent (Kotlin/Gradle)
- `tests/` — pytest test suite for the HA integration

## Android builds

```bash
gradle :apps:glass_agent:assembleDebug
adb install -r apps/glass_agent/build/outputs/apk/debug/glass_agent-debug.apk
```

No `gradlew` in the repo — use the system `gradle` (Homebrew).

## Key constraints

- Python quality gates use the `venv/` virtualenv at the repo root.
- mypy is strict (`strict = true`) with `warn_unused_ignores = true` — stale
  `# type: ignore` comments are errors, not warnings.
- ruff PLR0911 limit is 6 return statements per function.
- Tests live in `tests/`; run with `venv/bin/pytest tests/ -q`.
