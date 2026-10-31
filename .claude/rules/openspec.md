---
paths:
  - "openspec/**"
---

# OpenSpec

**OpenSpec changes use the stock `spec-driven` schema**, driven by `/opsx:explore` → `/opsx:propose`
→ `/opsx:apply` → `/opsx:verify` → `/opsx:archive`. For those, explore replaces
`superpowers:brainstorming` and `tasks.md` replaces `writing-plans`: both skills write a second copy
under `docs/superpowers/`, the duplication the retired `superspec` schema existed to redirect
(`git log -- openspec/schemas/superspec`). `verify.md` is a convention, not an artifact — it holds
the requirement → code → test → gap table and the `W<n>` warning IDs later notes cite. Project rules
live in `openspec/config.yaml`, keyed by artifact ID only; any other key is dropped with a stderr
warning the agent never sees. Archived changes still pinned to `superspec` are never re-read, so
the deleted schema breaks nothing.

- **A delta spec's `## REMOVED Requirements` heading must repeat the main spec's requirement name
  *verbatim*.** `openspec archive` matches on the name; a paraphrase warns
  ("not in the current spec; treating it as already removed") and then archives anyway, leaving the
  real requirement in `openspec/specs/` describing behaviour the code no longer has. Both of
  `dynamodb-results-store`'s REMOVED blocks missed this way, and the main spec kept documenting
  `--mongo-uri` after the option was deleted. Grep the main spec for the exact heading before
  writing the REMOVED block, and read the archive warnings rather than skimming them.
