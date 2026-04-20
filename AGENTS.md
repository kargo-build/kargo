---
project: KARGO
languages: [Kotlin, Java]
build-system: Amper
---

# Guidelines for Amper Development

## Project Guidelines

**CRITICAL: The following skill files MUST be followed at all times:**
- [kargo-package-guidelines](./.agent/skills/kargo-package-guidelines/SKILL.md)
- [maintain-low-delta](./.agent/skills/maintain-low-delta/SKILL.md)

**DO NOT use `.ai/guidelines.md`.**

## Build & Test

Use `./amper-from-sources` (not `./amper` or `./kargo`) for all build and test commands.

```bash
# Build
./amper-from-sources build

# Run tests
./amper-from-sources test -m <module-name>
```