(ns softdrinkops.registry
  "Pure validation functions for soft-drink/bottled-water-manufacturing
  production parameters. These are called by the Governor to independently
  verify physical/operational constraints -- the advisor's confidence is
  NOT sufficient to override these checks.

  All functions here are pure arithmetic/set/boolean predicates with no
  host-clock or I/O calls, so this namespace stays trivially portable
  across Clojure/ClojureScript. Callers that need the current time (see
  `filling-line-calibration-overdue?`) obtain it themselves via a
  `:clj`/`:cljs` reader-conditional at the call site (see
  `softdrinkops.governor`).")

(defn carbonation-out-of-tolerance?
  "Independently verify that the batch's actual carbonation level (volumes
  of CO2) falls within tolerance of the product's declared target. Sits
  outside the tolerance band and the batch risks either a flat, off-spec
  product or an over-pressurized container safety hazard -- a decision
  this actor never makes on its own; it only proposes logging the
  observed value so a human can act."
  [actual-volumes target-volumes tolerance-volumes]
  (or (< actual-volumes (- target-volumes tolerance-volumes))
      (> actual-volumes (+ target-volumes tolerance-volumes))))

(defn brix-out-of-range?
  "Independently verify that the batch's actual Brix (sugar content) falls
  within the product's declared-style window. Outside the window
  indicates the finished beverage no longer matches its declared style
  (regular vs. diet/zero vs. plain water, etc.) -- a style/label
  misclassification with real consumer-facing consequences."
  [actual-percent min-percent max-percent]
  (or (< actual-percent min-percent)
      (> actual-percent max-percent)))

(defn preservative-exceeds-max?
  "Independently verify that the batch's added-preservative residue
  (ppm, e.g. sodium benzoate / potassium sorbate) does not exceed the
  product's maximum allowable level."
  [actual-ppm max-ppm]
  (> actual-ppm max-ppm))

(defn microbial-load-exceeds-max?
  "Independently verify that the batch's actual total-plate-count
  (CFU/mL) does not exceed the product's maximum allowable level.
  Microbial load above the regulatory/product action level is one of the
  most serious food-safety hazards specific to soft-drink and
  bottled-water production -- a hard, un-overridable stop."
  [actual-cfu-per-ml max-cfu-per-ml]
  (> actual-cfu-per-ml max-cfu-per-ml))

(defn mineral-content-below-minimum?
  "Independently verify that the batch's actual total-dissolved-solids
  mineral content does not fall below the product's minimum required
  level for a legal \"mineral water\" label claim."
  [actual-mg-per-l min-mg-per-l]
  (< actual-mg-per-l min-mg-per-l))

(defn filling-line-calibration-overdue?
  "Independently verify that the mixing/carbonation/filling-line
  fill-volume metering equipment was calibrated within the last 90 days.
  `last-calibration-epoch-ms` and `now-epoch-ms` are both epoch
  milliseconds -- callers obtain `now` via a `:clj`/`:cljs`
  reader-conditional, keeping this namespace free of any host-clock
  call."
  [last-calibration-epoch-ms now-epoch-ms]
  (> (- now-epoch-ms last-calibration-epoch-ms)
     (* 90 24 60 60 1000)))

(defn fill-volume-variance-excessive?
  "Independently verify that a batch's finished-product fill-volume
  variance (drift from the product's standard-of-fill target, in mL)
  does not exceed the maximum tolerance. Excessive variance indicates the
  filling-line filler is out of calibration or the standard-of-fill was
  not met."
  [actual-variance-ml max-variance-ml]
  (> actual-variance-ml max-variance-ml))

(defn additive-label-mismatch?
  "True when the batch's actual preservative residue crosses the
  jurisdiction's preservative-declaration threshold but `:preservatives`
  is NOT present in `declared-additives` (mislabeling / under-declaration
  risk -- a genuine food-safety/consumer-protection hazard for additive-
  sensitive consumers). Declaring preservatives when not strictly
  required is conservative and never a risk."
  [preservative-ppm threshold-ppm declared-additives]
  (and (some? preservative-ppm) (some? threshold-ppm)
       (>= preservative-ppm threshold-ppm)
       (not (contains? (set declared-additives) :preservatives))))

(defn contamination-detected?
  "Independently verify a batch's contamination-detection result (foreign
  material -- glass/metal fragments from the filling line -- or a
  positive off-flavor/spoilage-marker screen). Any detection is a genuine
  physical/quality hazard -- this predicate simply coerces the raw fact
  to a boolean so the Governor's check functions stay uniform in shape
  with every other independently-verified physical constraint in this
  namespace."
  [actual-detected?]
  (boolean actual-detected?))

(defn sanitation-score-insufficient?
  "Independently verify that the plant's clean-in-place (CIP) sanitation
  score meets the minimum required. Score is 0-100, assessed by a
  third-party auditor against food-safety sanitation standards (a
  significant concern specific to preventing microbial contamination
  during mixing, carbonation, and filling)."
  [actual-score min-score-required]
  (< actual-score min-score-required))
