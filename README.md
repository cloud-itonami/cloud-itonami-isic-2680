# cloud-itonami-isic-2680: Manufacture of magnetic and optical media

Open Business Blueprint for **ISIC 2680**: manufacture of magnetic and optical media — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **magnetic/optical-media-plant operations**: production-batch data logging (product-type/substrate-thickness/quantity/defect-rate), coating/molding/data-stamping-line-equipment maintenance scheduling, safety-concern flagging, and outbound product shipment coordination.

This repository designs a forkable OSS business for magnetic/
optical-media-plant operations: run by a qualified operator so a plant
keeps its own operating records instead of renting a closed SaaS.

## Scope: plant operations coordination, not coating/molding/stamping-line control

ISIC 2680 covers the **manufacturing plant** that coats a substrate film with magnetic particles (magnetic-tape and magnetic-strip-card production) or injection-molds and data-stamps a polycarbonate substrate to replicate a stamper's pit pattern (CD/DVD/Blu-ray-style optical-disc production), producing blank and pre-recorded magnetic tape, magnetic-strip cards, and optical discs. This actor coordinates the back-office record keeping around that plant — it never touches the coating/molding/stamping-line equipment directly, and it is never a content-replication licensing authority (e.g. an IFPI Source Identification (SID) Code or comparable rights-holder authorization mark).

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — coating/molding/stamping batch, output-quality/test-result data logging (administrative, not an operational decision)
- `:schedule-maintenance` — coating/molding/stamping-line-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a solvent-coating chemical-hazard/equipment-safety concern (always escalates)
- `:coordinate-shipment` — outbound magnetic/optical-media product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(coating/molding/stamping-line equipment, solvent-coating chemical
hazard, content-replication licensing, downstream product and worker
safety consequence):

- Does NOT control coating, molding, or data-stamping equipment directly
- Does NOT make plant-safety or licensing decisions (that's the plant supervisor's / rights-holder's exclusive human/institutional authority)
- Does NOT actuate coating/molding/stamping-line equipment (human plant supervisor decides)
- Does NOT self-issue a content-replication licensing/source-identification authorization mark (e.g. an IFPI Source Identification (SID) Code — the rights holder's / licensing body's exclusive authority — a PERMANENT, unconditional block)
- ONLY proposes/coordinates operations back-office; all actuation and licensing requires explicit human/institutional authority
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`magopticalmedia.operation/build`, a langgraph-clj StateGraph):
1. **`magopticalmedia.advisor`** (sealed intelligence node, `MagOpticalMediaAdvisor`): proposes decisions only, never commits
2. **`magopticalmedia.governor`** (independent, `Magnetic and Optical Media Plant Operations Governor`): validates against domain rules, re-derived from `magopticalmedia.registry`'s pure functions and `magopticalmedia.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct coating/molding/stamping-line-equipment control)
     - Directly actuating coating/molding/stamping-line equipment (`:actuate-equipment? true`) is a PERMANENT, unconditional block
     - Self-issuing a content-replication licensing/source-identification authorization mark (`:issue-certification? true`, any op) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped quantity past its own logged production quantity (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-type` value on a production-batch patch
     - No physically implausible `:substrate-thickness-mm` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`magopticalmedia.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`magopticalmedia.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
kbb -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
kbb -M:dev:test

# Run the demo
kbb -M:dev:run

# Lint
kbb -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
