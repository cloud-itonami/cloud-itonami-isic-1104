# cloud-itonami-isic-1104: Soft Drink and Bottled Water Manufacturing Coordination Actor

**ISIC Rev. 4 1104** — Manufacture of soft drinks; production of mineral waters and other bottled waters

A distributed actor for autonomous, compliant coordination of soft-drink and bottled-water plant operations: water/ingredient intake → mixing (syrup/flavor/water blending) → carbonation (CO2 injection) → filling → Brix/CO2-volumes/microbial-load/mineral-content/fill-volume inspection → preservative/additive labeling → finished-product logistics. Sealed LLM advisor; independent Governor enforcement; append-only audit ledger. **Not equipment control.** Mixing-tank, carbonator, and filling-line equipment operation remain exclusive to licensed plant staff, and food-safety-certification decisions (e.g. issuing or renewing a plant's food-safety certification) remain exclusive to human inspectors and regulators.

## Scope

This actor coordinates **plant-operations workflow** for soft-drink and bottled-water manufacturing (carbonated soft drinks, still flavored drinks, natural mineral water, other bottled/purified water):
- Production batch logging (water/ingredient intake, mixing/carbonation/filling parameters, Brix, CO2 volumes, evidence checklist)
- Equipment maintenance scheduling (mixing tanks, carbonators, filling lines, fill-volume meters)
- Food-safety concern escalation (microbial contamination, mineral-content mislabeling, excess preservative residue)
- Finished-product shipment coordination

**Out of scope:**
- Direct mixing/carbonation/filling-line equipment control (plant staff exclusive)
- Food-safety-certification authority (human inspector/regulator only — this actor never issues or renews a plant's food-safety certification, it only logs observed values and, when warranted, raises a flag)
- Regulatory interpretation (proposals cite jurisdiction specifications; the Governor enforces only published requirements)
- Any actual sale/distribution transaction (this actor performs coordination and compliance-logging only, never executes a sale)

## Design

### Governor (Independent Compliance Layer)

The Governor is the separation-of-powers enforcement. It never trusts the advisor's confidence for anything safety- or compliance-relevant, and it always wins over the advisor.

- **Hard HOLD** (un-overridable):
  - Operation outside the closed allowlist (`:op-not-allowed`) — includes any proposal that would touch mixing/carbonation/filling-line control or food-safety-certification authority decisions
  - Proposal asserting an `:effect` other than `:propose` (`:effect-not-propose`)
  - Plant/batch record not independently verified/registered before any proposal is made against it (`:batch-not-registered`) — applies to every proposal op, not only shipment coordination
  - No jurisdiction citation (`:no-spec-basis`) — can't verify requirements without one
  - Evidence checklist incomplete (`:evidence-incomplete`)
  - Carbonation level outside the declared product's tolerance band (`:carbonation-out-of-tolerance`) — crossing the band risks either a flat, off-spec product or an over-pressurized container safety hazard
  - Brix (sugar content) outside the declared style's window (`:brix-out-of-range`)
  - Added-preservative residue exceeds the product's spoilage/quality ceiling (`:preservative-exceeds-max`)
  - Microbial load (total-plate-count) exceeds the product's regulatory action level (`:microbial-load-exceeded`)
  - Mineral content below the minimum for a legal "mineral water" label claim (`:mineral-content-below-minimum`)
  - Contamination detected on the batch's own inspection — foreign material / off-flavor / spoilage marker (`:contamination-detected`)
  - Filling-line fill-volume metering calibration overdue (`:filling-line-calibration-overdue`)
  - Finished-product fill-volume variance excessive (`:fill-volume-variance-excessive`) — standard-of-fill
  - Additive/preservative labeling mismatch — residue crosses the jurisdiction's declaration threshold without a `:preservatives` declaration (`:additive-label-mismatch`)
  - Plant clean-in-place (CIP) sanitation score insufficient (`:sanitation-score-insufficient`)
  - Unresolved food-safety flag (`:food-safety-flag-unresolved`)
  - Batch already processed / shipment already finalized (double-commit guards)
- **Escalate** (human sign-off always required):
  - `:log-production-batch` / `:coordinate-shipment` — real actuation events, always require plant-operator sign-off even when the Governor is otherwise clean
  - `:flag-food-safety-concern` — a food-safety concern (microbial contamination, mineral-content mislabeling, excess preservative residue) is never auto-resolved by advisor confidence alone
  - Low advisor confidence (below `governor/confidence-floor`, 0.6)
- **Commit** (advisor proposal approved; Governor clean; not a mandatory-escalation op):
  - Routine, low-stakes proposals only — in this actor's current allowlist that is effectively `:schedule-maintenance` when clean

### Operations (Proposals)

Closed allowlist — the advisor may **only** ever propose these four operation types, all `:effect :propose`:

- **`:log-production-batch`** — Log water/ingredient intake → mixing → carbonation → filling batch (Brix, CO2 volumes, mineral content) into production records (always requires human sign-off)
- **`:schedule-maintenance`** — Propose equipment maintenance for mixing tanks/carbonators/filling lines/fill-volume meters (routine, low risk)
- **`:flag-food-safety-concern`** — Surface a food-safety or compliance concern (e.g. microbial contamination, mineral-content mislabeling, excess preservative residue); always escalates
- **`:coordinate-shipment`** — Coordinate outbound shipment of finished product (always requires human sign-off)

Any proposal for an operation outside this allowlist — most importantly anything that would amount to direct mixing/carbonation/filling-line control, or a food-safety-certification-authority decision — is refused unconditionally by the Governor (`:op-not-allowed`), regardless of advisor confidence.

## Testing

```bash
# Run full test suite
clojure -M:test

# Check code quality
clojure -M:lint

# Run demo simulation
clojure -M:run
```

## Standalone Use

This repo is **forkable outside the workspace**. If cloning standalone (not in the kotoba-lang monorepo), override `:local/root` paths in `deps.edn`:

```clojure
{:deps {io.github.kotoba-lang/langchain {:git/url "https://github.com/kotoba-lang/langchain" :git/tag "v0.1.0"}
        io.github.kotoba-lang/langgraph {:git/url "https://github.com/kotoba-lang/langgraph" :git/tag "v0.1.0"}}}
```

## License

AGPL-3.0-or-later. Forking/contribution welcome; see `CONTRIBUTING.md`.

## Security

Report security issues to the issue tracker or private disclosure; see `SECURITY.md`.

---

Part of **cloud-itonami**: autonomous actor fleet for regulated industries. See [github.com/cloud-itonami](https://github.com/cloud-itonami).
