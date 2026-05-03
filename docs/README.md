# `docs/` — how this directory is organised, and how to design a feature

## Layout

| Path | Contents |
|---|---|
| `docs/VISION.md` | Product vision and principles. The north star every design doc must align to. |
| `docs/design/` | One file per feature design doc. Shape mandated by [`../DESIGN_DOCS.md`](../DESIGN_DOCS.md). |
| `docs/diagrams/` | All committed diagrams (SVG preferred). Referenced from design docs and `VISION.md`. |
| `docs/preso/` | Marp slide decks for sharing designs with stakeholders. Companion artifacts, not substitutes. |
| `docs/blog/` | Long-form write-ups not tied to a specific feature design. |
| `docs/assets/` | Static assets (logos, brand SVGs) used by docs and the `docs/index.html` site. |
| `docs/code-review.md` | Process doc for the local code-review workflow. |
| `docs/index.html` | Landing page for the GitHub Pages site. |

The rest of this file describes the *process* for producing a design doc. The *shape* of those docs is fixed by [`../DESIGN_DOCS.md`](../DESIGN_DOCS.md).

## The Elephant and the Goldfish

Borrowed from [drensin's "Elephants, goldfish, and the new golden age of software engineering"](https://drensin.medium.com/elephants-goldfish-and-the-new-golden-age-of-software-engineering-c33641a48874). Read the original — it is short and worth the time. What follows is the version we use here.

### The metaphor

- **The Elephant** is a long-running AI session you have been growing for hours or days. It has read the codebase, argued with you about tradeoffs, and accumulated all the context behind the design. Elephants never forget.
- **The Goldfish** is a fresh AI session with no memory of any of that. It only knows what is written in the design doc you hand it.

The bet: as AI writes more of the code, **the design doc becomes the primary human-readable artifact**. The code is downstream. So the design doc has to survive being read by a goldfish — anyone (or any future AI session) who shows up cold and needs to understand, critique, or implement the feature from the doc alone.

> *"sizeof(docs) << sizeof(code)"* — drensin

A good design doc is dense, self-contained, and small enough that any session can hold the whole thing in context.

### The four phases

#### Phase 1 — Grow the elephant (no code)

Start a session and **forbid yourself from writing any implementation code.** The temptation will be strong; resist it. The goal of this phase is to build shared context with the AI about the problem, not to ship anything.

- Load the relevant existing code and `docs/VISION.md` into the session.
- Have the AI read it and explain the system back to you. If it gets things wrong, correct it. Repeat until it is fluent.
- Describe the feature you want. Ask the AI to challenge it: what assumptions are unstated? What edge cases break it? What in the existing system makes this harder than it looks?
- Iterate. This phase often takes a session or two. Do not rush it. The output of this phase is **conviction about the right shape**, not a document yet.

If you skip this phase you will produce a design doc that is fluent prose hiding a confused design. The elephant's job is to *earn* the design.

#### Phase 2 — Teach the elephant (write the doc)

Now write the design doc, in the structure mandated by [`../DESIGN_DOCS.md`](../DESIGN_DOCS.md). Build it iteratively, section by section, in the same elephant session that has the context. Do not try to one-shot it.

The hardest section is the **file enumeration** in §6. Every file the implementation will touch, listed by path, with a one-sentence rationale. If you cannot list the files, the design is not done — go back to Phase 1.

The doc is not a transcript of the elephant's thinking. It is the *distilled output* — the conclusions, the chosen approach, the rejected alternatives and why. Everything the elephant learned that a goldfish would need.

#### Phase 3 — Test against the goldfish

Open a **fresh session.** No CLAUDE.md context beyond what the goldfish would see by default, no chat history, nothing. Hand it only the design doc. Then run these three tests:

1. **Comprehension.** Ask the goldfish to explain the feature back to you in its own words — what problem it solves, why the chosen approach beats the alternatives, what files will change. If the goldfish gets it wrong, the doc is missing something. Add what was missing. Do *not* explain it in chat — fix the doc.

2. **Critique.** Ask the goldfish to find gaps, faulty assumptions, edge cases the design ignores, and risks. Take its critiques seriously: it is reading the doc the way every future reader will. If it surfaces a real gap, update the design (which may mean going back to Phase 1).

3. **Implementation readiness.** Ask the goldfish: "Could you implement this from the doc alone? What is missing?" If the answer is "I would need to ask about X," then X belongs in the doc.

Iterate. A doc usually needs 2–4 goldfish passes before it survives.

#### Phase 4 — Implement

Hand the finalized design doc to a fresh session and tell it to implement strictly to the doc. Then review the resulting code rigorously — your job is no longer typing, it is **judgment**: does the code match the design, is it clean, is it correct?

If implementation forces a real design change, stop. Update the doc, re-run a goldfish pass on the changed section, then continue. Drift between doc and code is the failure mode this whole process exists to prevent.

### Why this works

Three things shift when AI writes most of the code:

1. **Design is the bottleneck, not typing.** Once the design is right, generating the code is mechanical. So invest hours in design and minutes in code, not the inverse.
2. **Context is the scarce resource.** A design doc you can fit in one context window is worth more than a sprawling codebase you cannot. Keep docs dense.
3. **Judgment is what humans contribute.** The elephant proposes; the goldfish stress-tests; you decide. Neither AI session replaces the call you make about whether the design is good.

### When to skip this

This is for features that need a design doc per the table in [`../DESIGN_DOCS.md`](../DESIGN_DOCS.md). For bug fixes, single-file refactors, copy changes, and dependency bumps — just do the work. The elephant/goldfish loop is overhead, and overhead on a one-line fix is malpractice.
