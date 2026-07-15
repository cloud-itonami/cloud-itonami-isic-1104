# Contributing to cloud-itonami-isic-1104

Thank you for your interest in contributing to the Soft Drink and Bottled
Water Manufacturing Operations actor.

## Scope

This repository is a specialization of the cloud-itonami architecture for ISIC
1104 (manufacture of soft drinks; production of mineral waters and other
bottled waters). Contributions should:

1. Extend or correct the **Governor rules** (food-safety constraints)
2. Add **product types** or **jurisdictional requirements** to the facts registry
3. Improve **test coverage** for soft-drink/bottled-water-manufacturing-specific scenarios
4. Clarify **documentation** and ADRs

## Prohibited Changes

Do **not**:

- Add direct mixing/carbonation/filling-line control (mixing-tank, carbonator,
  and filling-line operation remains exclusive to plant staff)
- Add food-safety-certification authority (issuing or renewing a plant's
  food-safety certification remains exclusive to human inspectors and
  regulators)
- Modify the Governor to allow LLM confidence to override food-safety hard holds
- Add JVM-only code (all source must be `.cljc` / portable)
- Change the AGPL-3.0-or-later license

## Process

1. Open an issue describing your proposed change
2. Link to the relevant ADR (ADR-2607152500 or later)
3. Submit a pull request against `main`
4. Ensure all tests pass: `clojure -M:test`
5. Run linter: `clojure -M:lint`

## Code Style

- Use `.cljc` for all source (no `.clj` or `.cljs` only)
- Follow Clojure conventions (kebab-case, docstrings on public fns)
- Governor rules must be pure, side-effect-free predicates
- Test all new facts and registry entries

## Questions?

File an issue or reach out to the maintainers.
