# ADR-0001: MagOpticalMediaAdvisor ⊣ Magnetic and Optical Media Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2680` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2680` publishes an OSS blueprint for magnetic/
optical-media **plant operations coordination** (production-batch
product-type/substrate-thickness/quantity/defect-rate data logging,
coating/molding/data-stamping-line-equipment maintenance scheduling,
safety-concern flagging, and outbound magnetic/optical-media product
shipment coordination). Like every actor in this fleet, the blueprint
alone is not an implementation: this ADR records the governed-actor
architecture that promotes it to real, tested code, following the same
langgraph StateGraph + independent Governor + Phase 0->3 rollout
pattern established across the cloud-itonami fleet.

The closest domain analog is `cloud-itonami-isic-2640` (Manufacture of
consumer electronics): both are back-office coordination actors for a
fixed processing PLANT with electronics/precision-manufacturing
equipment and a real physical safety dimension, and both share the
same four-op shape (`:log-production-batch`/`:schedule-maintenance`/
`:flag-safety-concern`/`:coordinate-shipment`) and the same two-entity
verified/registered gate structure (equipment for maintenance
scheduling, batch for shipment coordination). This build mirrors
`cloud-itonami-isic-2640`'s architecture closely but adapts the hazard
profile and equipment/product vocabulary to the magnetic/optical-media
plant: this vertical's central equipment is a magnetic-tape coating
line (coating a substrate film with magnetic particles) and a CD/DVD/
Blu-ray-style optical-disc injection-molding and data-stamping line
(replicating a stamper's pit pattern into a polycarbonate substrate),
rather than 2640's SMT/assembly/test-bench line; its permanent
equipment-actuation block guards coating/molding/stamping-line
EQUIPMENT (`:actuate-equipment?`, same field name, same posture)
rather than 2640's SMT/assembly/test-bench-equipment; its
production-batch record declares a `:product-type` (closed set
spanning cd/dvd/blu-ray/magnetic-tape/magnetic-strip-card) and a
`:substrate-thickness-mm` (the disc/tape/card substrate thickness in
mm, plausibility-checked 0-1.5 mm against ECMA-130 (CD, Red Book) /
ECMA-267 (DVD) / ECMA-405 (Blu-ray Disc)'s approximately 1.2 mm overall
disc thickness and ISO/IEC 7810's approximately 0.76 mm ID-1
magnetic-strip-card thickness, a very different physical quantity from
2640's `:dielectric-test-kv` electrical hipot/withstand safety-test
voltage) in addition to a `:defect-rate-percent`, rather than 2640's
dielectric-safety-test-voltage field; and its shipment quantity is
tracked in finished-unit UNITS (`:units`/`:quantity-units`/
`:shipped-units`), the same shape 2640 uses for finished consumer-
electronics units (counted, not weighed, for freight coordination)
since discs/tapes/cards are likewise discrete counted units rather
than a bulk weight.

This vertical additionally has a DOMAIN-SPECIFIC safety-concern and
licensing-authority vocabulary neither 2640 nor its own analogs need in
the same form: manufacture of magnetic and optical media carries a
solvent-coating chemical hazard (magnetic-particle coating uses
solvent-based binders whose vapor concentration is a real plant hazard)
in place of 2640's battery-safety hazard, alongside the same shared
equipment-safety hazard 2640 shares. `:flag-safety-concern` therefore
surfaces a "solvent-coating chemical-hazard/equipment-safety concern"
(vs. 2640's "battery-safety/electrical-safety/RoHS-compliance
concern"), and this actor is never the licensing authority -- any
proposal (regardless of op) that declares `:issue-certification? true`
is a HARD, PERMANENT, unconditional block
(`magopticalmedia.governor/certification-authority-blocked-violations`),
the same "no phase, no human override" posture 2640 establishes for its
own consumer-electronics safety-certification block, adapted to this
vertical's actual authority boundary: a content-replication
licensing/source-identification authorization mark (e.g. an IFPI
Source Identification (SID) Code, the physical code a licensed optical-
disc mastering/replication plant stamps into every disc it produces
under authorization from the International Federation of the
Phonographic Industry or a comparable rights-holder/licensing body) --
not a safety-certification mark, since this vertical's authority
boundary is about copyright/replication licensing rather than
electrical/RoHS product-safety certification.

This vertical has NO pre-existing `kotoba-lang/magopticalmedia`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic -- pure functions in
`magopticalmedia.registry` (equipment/batch verification, shipment-
quantity recompute, product-type validation, substrate-thickness
plausibility validation, defect-rate plausibility validation) are
re-verified independently by the governor, the same "ground truth, not
self-report" discipline established across prior actors (most directly
`cloud-itonami-isic-2640`'s `consumerelec.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:magnetic-optical-media-plant-operations-governor`, is grep-verified
UNIQUE fleet-wide (`gh search code
"magnetic-optical-media-plant-operations-governor" --owner
cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external magnetic/optical-media-manufacturing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
magnetic/optical-media vertical has NO pre-existing capability library
to wrap. The equipment/batch-verification / shipment-quantity /
product-type / substrate-thickness / defect-rate validation functions
live as pure functions in `magopticalmedia.registry` and are
re-verified independently by `magopticalmedia.governor` -- the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-2640`'s
`consumerelec.registry`).

### Decision 2: Coordination, not control -- scope boundary at the back-office

This actor is **strictly back-office coordination** of magnetic/
optical-media plant operations. It does NOT:
- Control coating, molding, or data-stamping equipment directly
- Make plant-safety or licensing decisions (exclusive to the human plant supervisor / rights holder / licensing body)
- Actuate coating/molding/stamping-line equipment
- Self-issue a content-replication licensing/source-identification authorization mark (e.g. an IFPI Source Identification (SID) Code)

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority or
the licensing body's authority -- it is a proposal-screening and
documentation layer.

**CRITICAL SAFETY BOUNDARY**: magnetic/optical-media manufacturing is a
safety-critical domain (solvent-coating chemical hazard,
equipment-safety hazard, downstream product-safety and worker-safety
consequence). Safety-concern flagging NEVER auto-commits. All safety
concerns escalate immediately to human review.

### Decision 3: Safety-concern escalation -- always human sign-off

`:flag-safety-concern` (solvent-coating chemical-hazard concern,
equipment-safety concern) ALWAYS escalates, never auto-commits. This is
not a "low-stakes proposal" -- it is a circuit-breaker that must reach
human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2640`, this vertical has TWO entity kinds each
gating a different op: `:schedule-maintenance` independently verifies
the referenced **equipment** unit's own `:verified?`/`:registered?`
fields; `:coordinate-shipment` independently verifies the referenced
**batch**'s own `:verified?`/`:registered?` fields. Both are the same
"plant/batch record must be independently verified/registered before
any action" HARD invariant applied to the two distinct record kinds
this domain actually has. `:coordinate-shipment` additionally
independently recomputes whether a batch's own recorded shipped-to-
date unit quantity plus the proposal's own claimed unit quantity would
exceed the batch's own recorded production quantity -- never taken on
the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into twelve concrete checks
in `magopticalmedia.governor`, mirroring `cloud-itonami-isic-2640`'s
own elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's quantity must independently recompute within the batch's own logged production quantity
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct coating/molding/stamping-line-equipment control, equipment actuation, or self-issued content-replication licensing is permanently blocked
4. The op allowlist is closed -- `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Magnetic/optical-media plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off, and no content-replication licensing mark can
ever be self-issued.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into twelve concrete governor checks) protect against scope creep into
unauthorized equipment operation, equipment actuation, or licensing
self-issuance. Safety concerns are a circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, line operation, and licensing
issuance remain human-/institution-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, licensing-body APIs) --
this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-2680`: `clojure -M:test` green (see the
  superproject ADR and `kotoba-lang/industry` registry entry for the
  exact raw output, verified from an independent fresh clone),
  `clojure -M:lint` clean, `clojure -M:dev:run` demo narrative
  exercises proposal submission, escalation, and every HARD-hold
  scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified,
  shipment-quantity-exceeded, equipment-actuate-blocked,
  certification-authority-blocked, already-scheduled,
  invalid-product-type, invalid-substrate-thickness-mm,
  invalid-defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
