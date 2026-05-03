# Design Docs

Every non-trivial feature in this repo lands a design doc **before** code. The doc is the artifact reviewers reason about; the code is the mechanical follow-up. If a change is large enough that a reviewer would ask "why this shape?", it needs a design doc.

For the *process* of producing one (the elephant/goldfish workflow), see [`docs/README.md`](docs/README.md). This file defines the *shape* of the finished document.

## When a design doc is required

| Change | Doc required? |
|---|---|
| New user-facing feature | Yes |
| New subsystem, model, or data format | Yes |
| Schema change to `document.proto` (any field add/remove/rename) | Yes |
| Cross-cutting refactor (touches >5 files in unrelated packages) | Yes |
| Bug fix, single-file refactor, dependency bump, copy change | No |
| Performance tuning that changes an algorithm | Yes |
| Performance tuning that changes a constant | No |

When in doubt, write one. A 200-line design doc is cheaper than a 2,000-line PR that has to be unwound.

## Where it lives

- Path: `docs/design/<feature-slug>.md` (kebab-case, e.g. `docs/design/cornell-notes.md`, `docs/design/audio-transcription.md`). No `-design` suffix — the directory already says it.
- One doc per feature. If a feature grows multiple sub-designs, create a directory `docs/design/<feature>/` and split.
- Diagrams referenced from a design doc go in `docs/diagrams/` as committed `.svg` (or `.png` if the source is a screenshot). Reference them with a relative path: `![…](../diagrams/foo.svg)`.
- Slide decks (Marp) for sharing a design with stakeholders go in `docs/preso/`. They are companion artifacts, not replacements for the doc.
- Commit the doc to the same feature branch as the implementation, in its own commit, **before** the implementation commits.

## Front matter

Every design doc opens with a YAML frontmatter block at the very top of the file, before the H1 title. It is the doc's metadata in a machine-parseable form, so a script — or a fresh goldfish session — can answer "what is the status of every design in this repo?" with a single `grep`.

```yaml
---
status: draft        # draft | review | approved | superseded
author: spm@edwei.com
created: 2026-04-12
updated: 2026-05-02
---
```

| Field | Required | Notes |
|---|---|---|
| `status` | yes | One of `draft`, `review`, `approved`, `superseded`. See meanings below. |
| `author` | yes | Primary author's email. For multiple authors use a list: `[a@x, b@x]`. |
| `created` | yes | ISO date (`YYYY-MM-DD`) the doc was first committed. Never changes. |
| `updated` | yes | ISO date of the most recent substantive content change. Bump on edits to the design itself, not on typo fixes or renames. |
| `superseded-by` | only if `status: superseded` | Repo-relative path to the replacement doc, e.g. `docs/design/foo.md`. |

Status meanings:

- `draft` — being written; no goldfish pass yet. Reviewers should not gate on it.
- `review` — going through the goldfish protocol (see [`docs/README.md`](docs/README.md)).
- `approved` — survived goldfish review; ready to implement, or already implementing. This is the steady state for a merged design.
- `superseded` — replaced by another doc. Body is preserved as history; add `superseded-by`.

## Required sections (in order)

Every design doc must have these sections, in this order, with these names:

### 1. Context (3–10 sentences)
What problem is this solving, for whom, and why now? Link to `docs/VISION.md` and name which Layer / Principle this serves. A reader who has never seen the feature should understand the motivation from this section alone.

### 2. Vision alignment
A table mapping VISION.md concepts/principles to how this feature serves them. If a feature does not map cleanly to the vision, that is a signal to revisit the feature, not to skip the section. Existing examples: `docs/design/cornell-notes.md`, `docs/design/audio-transcription.md`.

### 3. Use cases / driving scenarios
Concrete scenarios that constrain the design. Prefer a table with axes that actually pull in opposite directions (latency vs. quality, online vs. offline, etc.) — the tension is what the design has to resolve.

### 4. Approaches considered
For each viable approach: a sub-section with a tradeoff table covering the dimensions that matter for *this* feature (latency, accuracy, APK size, battery, privacy, e-ink fit, etc.). Include approaches you rejected and **why**. A doc with only one approach is suspicious — it usually means alternatives were not seriously considered.

### 5. Recommendation
The chosen approach, in one paragraph, with the decisive reasons. If the recommendation is a hybrid, name the split (e.g. "SpeechRecognizer for memos, Whisper for lectures").

### 6. Detailed implementation — file enumeration
**Every file that will be created or modified, listed by path, with a one-sentence rationale.** This is the section that prevents AI implementation drift. If you cannot enumerate the files, the design is not finished.

Format:

```
- `app/src/main/java/com/writer/audio/AudioCapture.kt` (new) — wraps MediaRecorder, exposes a Flow<ShortArray> of 16 kHz PCM frames
- `app/src/main/proto/com/writer/model/proto/document.proto` (modify) — add `optional AudioBlock audio = 14;` (reserve 14 in version notes)
- `app/src/test/.../AudioCaptureTest.kt` (new) — unit test for frame timing and silence detection
```

Mark each entry `(new)`, `(modify)`, or `(delete)`. Group by package or layer if the list exceeds ~15 entries.

### 7. Test plan
Which tests prove the feature works. Name the test classes (new and modified). For features that touch the document format, this section must include a golden file plan (see CLAUDE.md "Document Format Safety").

### 8. Open questions
Things you do not know yet. Empty is fine; "none" is suspicious before implementation begins.

## Style rules

- **Prose for *why*, tables for *what*.** Tradeoff comparisons, state matrices, file lists — all tables. Motivation, rationale, decisions — prose.
- **No code blocks longer than ~30 lines.** A design doc shows shape, not implementation. If you need more, it belongs in pseudocode or a separate appendix.
- **Pseudocode over real code.** Real code in a design doc rots the moment the implementation diverges. Pseudocode signals "this is the shape."
- **Diagrams as ASCII or committed SVG.** Inline ASCII for state machines and small flows; `docs/diagrams/<name>.svg` for anything that ASCII butchers. Reference `docs/diagrams/architecture.svg` as the existing pattern.
- **No status updates.** Do not write "TODO: still designing this" or "we shipped this in v0.4." A design doc describes a *target state*. Ephemeral status belongs in PRs, commits, or `VISION.md`.
- **Self-contained.** A reader who has never seen the feature, working only from this doc, should be able to explain the system back. This is the goldfish test (see `docs/README.md`).

## Lifecycle

- The doc is **frozen at merge time**. After implementation lands, the doc represents what we built and *why we decided to build it that way*. It is not a living document.
- Substantive course-corrections during implementation are a signal to update the doc *and* re-review it. Small drift (renamed variables, file moved one directory over) is fine to leave.
- When a design is superseded, do not delete the old doc. Set `status: superseded` and `superseded-by: <path>` in the frontmatter, bump `updated`, and leave the body intact. The history matters.
