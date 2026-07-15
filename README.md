# cloud-itonami-isic-3092: Manufacture of bicycles and invalid carriages

Open Business Blueprint for **ISIC 3092**: manufacture of bicycles and invalid carriages (wheelchairs and other mobility aids) — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **bicycle/mobility-device plant operations**: production-batch data logging (product-category/weight-capacity/quantity/weld-defect-rate), welding/assembly/test-bench-equipment maintenance scheduling, safety-concern flagging, and outbound shipment coordination.

This repository designs a forkable OSS business for bicycle/mobility-
device plant operations: run by a qualified operator so a plant keeps
its own operating records instead of renting a closed SaaS.

## Scope: plant operations coordination, not welding/assembly-line control

ISIC 3092 covers the **manufacturing plant** that welds and assembles
bicycle and invalid-carriage (wheelchair/mobility-scooter) frames, and
inspects the resulting products on structural/brake test benches. This
actor coordinates the back-office record keeping around that plant —
it never touches the welding/assembly/test-bench equipment directly,
and it is never the ISO 4210 (bicycle safety) / ISO 7176 (wheelchair
safety) certification authority.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — frame-weld/assembly batch, output-quality/test-result data logging (administrative, not an operational decision)
- `:schedule-maintenance` — welding/assembly/test-bench-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a frame-weld-defect/brake-safety/structural-integrity concern (always escalates)
- `:coordinate-shipment` — outbound product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(welding/assembly/test-bench equipment, structural-integrity and
brake-safety hazard, ISO 4210/ISO 7176 safety certification, direct
rider/occupant-safety consequence — including riders and mobility-aid
occupants with reduced mobility):

- Does NOT control welding, assembly, or test-bench equipment directly
- Does NOT make plant-safety or certification decisions (that's the plant supervisor's / certification body's exclusive human/institutional authority)
- Does NOT actuate welding/assembly/test-bench equipment (human plant supervisor decides)
- Does NOT self-issue an ISO 4210/ISO 7176 bicycle-or-wheelchair safety certification mark (the accredited certification body's exclusive authority — a PERMANENT, unconditional block)
- ONLY proposes/coordinates operations back-office; all actuation and certification requires explicit human/institutional authority
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`bikemfg.operation/build`, a langgraph-clj StateGraph):
1. **`bikemfg.advisor`** (sealed intelligence node, `BikeAdvisor`): proposes decisions only, never commits
2. **`bikemfg.governor`** (independent, `Bicycle & Mobility Device Plant Operations Governor`): validates against domain rules, re-derived from `bikemfg.registry`'s pure functions and `bikemfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct welding/assembly/test-bench-equipment control)
     - Directly actuating welding/assembly/test-bench equipment (`:actuate-equipment? true`) is a PERMANENT, unconditional block
     - Self-issuing an ISO 4210/ISO 7176 bicycle-or-wheelchair safety certification (`:issue-certification? true`, any op) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped quantity past its own logged production quantity (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-category` value on a production-batch patch
     - No physically implausible `:weight-capacity-kg` value on a production-batch patch
     - No physically implausible `:weld-defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`bikemfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`bikemfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
