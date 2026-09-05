(ns softdrinkops.store
  "Store abstraction for soft-drink/bottled-water-manufacturing production
  batches. Current implementation operates on plain data (`{:batches
  {batch-id batch-map} :facts [...]}`); production should migrate this
  seam to Datomic/kotoba-server (the same seam point all cloud-itonami
  actors use) while keeping the same pure-function surface.

  A production batch is the minimal unit of work: one mixing/carbonation/
  filling run of a soft-drink or bottled-water product, tracked from
  water/ingredient intake through mixing, carbonation, filling, and
  shipment.
  Representative batch keys:
    - :product-type keyword product id (see `softdrinkops.facts/product-types`)
    - :jurisdiction keyword jurisdiction id (see `softdrinkops.facts/jurisdictions`)
    - :co2-volumes / :brix-percent / :preservative-ppm /
      :microbial-load-cfu-per-ml / :fill-volume-ml finished-product actuals
    - :mineral-content-mg-per-l finished-product total-dissolved-solids
      mineral content
    - :fill-volume-variance-ml finished-product fill-volume drift from
      the product's standard-of-fill target
    - :contamination-detected? true if filling-line inspection or an
      off-flavor/spoilage-marker screen flagged a concern
    - :sanitation-score 0-100 plant clean-in-place (CIP) hygiene score
    - :filling-line-last-calibration-date epoch-ms of last fill-volume
      metering equipment calibration
    - :declared-additives set of declared additive/preservative keywords
    - :evidence-checklist evidence items present for the batch
    - :safety-concern-raised? / :safety-concern-resolved? food-safety flag
    - :processed? true once a `:log-production-batch` proposal commits
    - :shipment-finalized? true once a `:coordinate-shipment` proposal commits

  The ledger (`:facts`) is a separate append-only vector of audit facts,
  kept alongside `:batches` in the same store value.")

(def full-evidence-checklist
  "The evidence items every jurisdiction in `softdrinkops.facts/jurisdictions`
  currently requires (all three lists are identical today). Kept here so seed
  records state the checklist once instead of re-typing it per batch; the
  Governor still resolves the requirement from the jurisdiction, never from
  this vector."
  [:water-source-record
   :mixing-log
   :carbonation-log
   :brix-test
   :co2-volumes-test
   :microbial-test
   :mineral-content-test
   :fill-volume-check])

(defn seed-db
  "Reference plant records for a demo/simulation run: eight independently
  verified and registered production batches plus an empty audit ledger,
  in the same `{:batches {...} :facts [...]}` shape the rest of this
  namespace operates on.

  Every key used here is one of the batch keys documented in this
  namespace's docstring -- no field is invented for presentation.

  `now-epoch-ms` is supplied by the caller rather than read from the host
  clock, so this namespace (like `softdrinkops.registry`) stays free of
  `System/currentTimeMillis` / `js/Date.now`; `softdrinkops.governor` keeps
  the single host-clock call site in the actor stack. Calibration dates are
  expressed as offsets from it because
  `registry/filling-line-calibration-overdue?` measures against the current
  time, so a fixed absolute epoch would silently flip from current to
  overdue as the file aged.

  The records are deliberately mixed: some are clean, some carry a real
  production-parameter defect (carbonation drift, Brix drift, preservative
  residue over the product ceiling, microbial load over the action level,
  mineral content under the \"mineral water\" floor, detected contamination,
  overdue filling-line calibration, excessive fill-volume variance,
  incomplete evidence, an undeclared preservative, an insufficient CIP
  sanitation score, an unresolved food-safety flag), so that a driver can
  exercise the Governor's hard rules against real batch metadata rather
  than against hand-written verdicts. None is pre-marked `:processed?` or
  `:shipment-finalized?` -- those flags are set only by `log-batch` /
  `finalize-shipment` once a proposal has actually been signed off."
  [now-epoch-ms]
  (let [days-ago (fn [n] (- now-epoch-ms (* n 24 60 60 1000)))]
    {:batches
     {"batch-1104-01"
      {:product-type :beverage/carbonated-soft-drink
       :jurisdiction :jp/mhlw
       :co2-volumes 3.5
       :brix-percent 10.2
       :preservative-ppm 45
       :microbial-load-cfu-per-ml 20
       :mineral-content-mg-per-l 0
       :fill-volume-ml 500
       :fill-volume-variance-ml 4
       :contamination-detected? false
       :sanitation-score 88
       :filling-line-last-calibration-date (days-ago 10)
       :declared-additives #{:preservatives}
       :evidence-checklist full-evidence-checklist
       :safety-concern-raised? false}

      "batch-1104-02"
      {:product-type :water/mineral
       :jurisdiction :eu/dg-sante
       :co2-volumes 0.0
       :brix-percent 0.1
       :preservative-ppm 0
       :microbial-load-cfu-per-ml 8
       :mineral-content-mg-per-l 180
       :fill-volume-ml 500
       :fill-volume-variance-ml 3
       :contamination-detected? false
       :sanitation-score 90
       :filling-line-last-calibration-date (days-ago 20)
       :declared-additives #{}
       :evidence-checklist full-evidence-checklist
       :safety-concern-raised? false}

      "batch-1104-03"
      {:product-type :water/bottled
       :jurisdiction :us/fda
       :co2-volumes 0.0
       :brix-percent 0.2
       :preservative-ppm 0
       :microbial-load-cfu-per-ml 65
       :mineral-content-mg-per-l 40
       :fill-volume-ml 500
       :fill-volume-variance-ml 5
       :contamination-detected? false
       :sanitation-score 82
       :filling-line-last-calibration-date (days-ago 15)
       :declared-additives #{}
       :evidence-checklist full-evidence-checklist
       :safety-concern-raised? true
       :safety-concern-resolved? false}

      "batch-1104-04"
      {:product-type :beverage/carbonated-soft-drink
       :jurisdiction :us/fda
       :co2-volumes 2.6
       :brix-percent 14.5
       :preservative-ppm 40
       :microbial-load-cfu-per-ml 30
       :mineral-content-mg-per-l 0
       :fill-volume-ml 500
       :fill-volume-variance-ml 6
       :contamination-detected? false
       :sanitation-score 84
       :filling-line-last-calibration-date (days-ago 8)
       :declared-additives #{:preservatives}
       :evidence-checklist full-evidence-checklist
       :safety-concern-raised? false}

      "batch-1104-05"
      {:product-type :beverage/still-flavored
       :jurisdiction :jp/mhlw
       :co2-volumes 0.0
       :brix-percent 11.0
       :preservative-ppm 120
       :microbial-load-cfu-per-ml 40
       :mineral-content-mg-per-l 0
       :fill-volume-ml 500
       :fill-volume-variance-ml 7
       :contamination-detected? false
       :sanitation-score 68
       :filling-line-last-calibration-date (days-ago 30)
       :declared-additives #{}
       :evidence-checklist [:water-source-record :mixing-log :carbonation-log
                            :brix-test :co2-volumes-test :fill-volume-check]
       :safety-concern-raised? false}

      "batch-1104-06"
      {:product-type :beverage/carbonated-soft-drink
       :jurisdiction :us/fda
       :co2-volumes 3.4
       :brix-percent 9.5
       :preservative-ppm 30
       :microbial-load-cfu-per-ml 15
       :mineral-content-mg-per-l 0
       :fill-volume-ml 500
       :fill-volume-variance-ml 22
       :contamination-detected? true
       :sanitation-score 79
       :filling-line-last-calibration-date (days-ago 120)
       :declared-additives #{:preservatives}
       :evidence-checklist full-evidence-checklist
       :safety-concern-raised? false}

      "batch-1104-07"
      {:product-type :water/mineral
       :jurisdiction :jp/mhlw
       :co2-volumes 0.0
       :brix-percent 0.2
       :preservative-ppm 12
       :microbial-load-cfu-per-ml 10
       :mineral-content-mg-per-l 320
       :fill-volume-ml 500
       :fill-volume-variance-ml 5
       :contamination-detected? false
       :sanitation-score 91
       :filling-line-last-calibration-date (days-ago 12)
       :declared-additives #{:preservatives}
       :evidence-checklist full-evidence-checklist
       :safety-concern-raised? false}

      "batch-1104-08"
      {:product-type :beverage/still-flavored
       :jurisdiction :us/fda
       :co2-volumes 0.0
       :brix-percent 9.0
       :preservative-ppm 60
       :microbial-load-cfu-per-ml 25
       :mineral-content-mg-per-l 0
       :fill-volume-ml 500
       :fill-volume-variance-ml 5
       :contamination-detected? false
       :sanitation-score 86
       :filling-line-last-calibration-date (days-ago 5)
       :declared-additives #{:preservatives}
       :evidence-checklist full-evidence-checklist
       :safety-concern-raised? false}}
     :facts []}))

(defn all-batches
  "All registered batches as a deterministically ordered seq of
  `[batch-id batch-map]` pairs (sorted by id), for drivers and renderers
  that must not depend on hash-map iteration order."
  [st]
  (sort-by key (get st :batches {})))

(defn production-batch
  "Retrieve a batch by id, or nil if it does not exist / is not yet
  registered."
  [st batch-id]
  (get-in st [:batches batch-id]))

(defn batch-already-processed?
  "True only if the batch exists and has already been marked processed."
  [st batch-id]
  (true? (:processed? (production-batch st batch-id))))

(defn batch-shipment-finalized?
  "True only if the batch exists and its shipment has already been
  finalized."
  [st batch-id]
  (true? (:shipment-finalized? (production-batch st batch-id))))

(defn log-batch
  "Register/update `batch-data` under `batch-id` and mark it processed
  (one-way flag). Used once a `:log-production-batch` proposal commits."
  [st batch-id batch-data]
  (assoc-in st [:batches batch-id] (assoc batch-data :processed? true)))

(defn finalize-shipment
  "Mark an existing batch's shipment as finalized (one-way flag). Used once
  a `:coordinate-shipment` proposal commits."
  [st batch-id]
  (assoc-in st [:batches batch-id :shipment-finalized?] true))

(defn audit-trail
  "Return the append-only audit ledger (empty vector if none yet)."
  [st]
  (get st :facts []))

(defn append-fact
  "Append `fact` to the store's audit ledger."
  [st fact]
  (update st :facts (fnil conj []) fact))

(defn all-batches
  "All registered batches as a vector of `[batch-id batch]` pairs ordered by
  batch id. Map iteration order is unspecified, so any projection built off
  the store (e.g. the build-time operator console in
  `softdrinkops.render-html`) must go through this rather than reading
  `:batches` directly -- otherwise rendered row order would drift between
  runs."
  [st]
  (vec (sort-by key (get st :batches {}))))

;; ───────────────────────────── Demo seed ─────────────────────────────
;;
;; Seed batches for this repo's demo drivers. Deliberately kept in the
;; store namespace rather than in a driver: the Governor's
;; `batch-not-registered` rule refuses ANY proposal against an id that is
;; not registered here, so a driver that invented its own ids would only
;; ever demonstrate that one rule. Keeping the ids here means every subject
;; a driver can legitimately name is greppable from a single place.
;;
;; This repo previously had no seed at all (`softdrinkops.sim` is still a
;; stub that prints "not yet implemented"), so these batches are authored
;; rather than inherited. Each batch below is a SETUP only -- no verdict is
;; asserted here; every disposition is computed at run time by
;; `softdrinkops.governor/check`.

(def demo-evidence-checklist
  "The full evidence set required by every jurisdiction in
  `softdrinkops.facts/jurisdictions` (`:jp/mhlw`, `:us/fda` and
  `:eu/dg-sante` currently list the same eight items). Named here so a seed
  batch cannot silently drift out of step with the requirement the Governor
  actually checks in `evidence-incomplete-violations`."
  [:water-source-record
   :mixing-log
   :carbonation-log
   :brix-test
   :co2-volumes-test
   :microbial-test
   :mineral-content-test
   :fill-volume-check])

(def ^:private day-ms (* 24 60 60 1000))

(defn- days-ago [now-epoch-ms n] (- now-epoch-ms (* n day-ms)))

(defn demo-batches
  "Seed production batches keyed by batch id.

  Every key used below is one of the batch keys documented in this
  namespace's docstring, and every `:product-type` / `:jurisdiction` value
  resolves in `softdrinkops.facts/product-types` and
  `softdrinkops.facts/jurisdictions` -- the Governor looks both up in those
  catalogs, so an unresolvable id would simply skip the spec comparisons
  instead of failing loudly.

  `now-epoch-ms` is supplied by the caller rather than read from the host
  clock here, keeping this namespace free of `System/currentTimeMillis` the
  same way `softdrinkops.registry` is. Only
  `:filling-line-last-calibration-date` is clock-relative, and it has to
  be: the Governor's calibration rule measures a 90-day window against
  *now*, so a fixed epoch would silently flip every batch to overdue once
  the seed itself aged past 90 days. Expressing it as an offset keeps each
  batch's verdict stable whenever the demo is run."
  [now-epoch-ms]
  (let [;; Inside every window of :beverage/carbonated-soft-drink (CO2
        ;; 3.5±0.3, Brix 8.0-12.0, preservative <=200ppm, plate count
        ;; <=100 CFU/mL, no mineral minimum). 50ppm preservative is well
        ;; over the 1ppm declaration threshold every jurisdiction sets, so
        ;; :preservatives must be declared or the batch would additionally
        ;; trip :additive-label-mismatch.
        soft-drink {:product-type :beverage/carbonated-soft-drink
                    :co2-volumes 3.5
                    :brix-percent 10.0
                    :preservative-ppm 50
                    :declared-additives #{:preservatives}
                    :microbial-load-cfu-per-ml 20
                    :mineral-content-mg-per-l 0
                    :fill-volume-variance-ml 5
                    :contamination-detected? false
                    :sanitation-score 85
                    :filling-line-last-calibration-date (days-ago now-epoch-ms 12)
                    :safety-concern-raised? false
                    :safety-concern-resolved? false
                    :evidence-checklist demo-evidence-checklist}
        ;; Natural mineral water: no carbonation, no sugar, NO permitted
        ;; preservative at all (:preservative-max-ppm 0), a stricter 20
        ;; CFU/mL plate-count ceiling, and a 250 mg/L TDS floor for the
        ;; "mineral water" label claim.
        mineral-water (assoc soft-drink
                             :product-type :water/mineral
                             :co2-volumes 0.0
                             :brix-percent 0.0
                             :preservative-ppm 0
                             :declared-additives #{}
                             :microbial-load-cfu-per-ml 5
                             :mineral-content-mg-per-l 320
                             :fill-volume-variance-ml 4
                             :sanitation-score 92
                             :filling-line-last-calibration-date (days-ago now-epoch-ms 20))]
    {;; clean carbonated soft drink -- clears the full log + shipment lifecycle
     "batch-jp-2401" (assoc soft-drink :jurisdiction :jp/mhlw)
     ;; clean natural mineral water -- logged, shipment not yet coordinated
     "batch-us-2402" (assoc mineral-water :jurisdiction :us/fda)
     ;; plate count 45 CFU/mL against the bottled-water ceiling of 20
     "batch-us-2403" (assoc mineral-water :jurisdiction :us/fda
                            :microbial-load-cfu-per-ml 45)
     ;; 4.6 volumes CO2 against a 3.5±0.3 band -- over-pressurization risk
     "batch-eu-2404" (assoc soft-drink :jurisdiction :eu/dg-sante
                            :co2-volumes 4.6)
     ;; 180 mg/L TDS against the 250 mg/L "mineral water" label floor
     "batch-jp-2405" (assoc mineral-water :jurisdiction :jp/mhlw
                            :mineral-content-mg-per-l 180)
     ;; foreign material / off-flavour screen positive AND an open,
     ;; unresolved food-safety flag -- two independent hard rules
     "batch-us-2406" (assoc soft-drink :jurisdiction :us/fda
                            :contamination-detected? true
                            :safety-concern-raised? true
                            :safety-concern-resolved? false)
     ;; microbial and mineral-content tests missing from the checklist
     "batch-eu-2407" (assoc soft-drink :jurisdiction :eu/dg-sante
                            :evidence-checklist [:water-source-record :mixing-log
                                                 :carbonation-log :brix-test
                                                 :co2-volumes-test :fill-volume-check])
     ;; fill-volume metering last calibrated 200 days ago (90-day window)
     "batch-jp-2408" (assoc soft-drink :jurisdiction :jp/mhlw
                            :filling-line-last-calibration-date (days-ago now-epoch-ms 200))
     ;; 120ppm preservative (under the 200ppm product ceiling) but nothing
     ;; declared -- an under-declaration, not an overdose
     "batch-us-2409" (assoc soft-drink :jurisdiction :us/fda
                            :preservative-ppm 120
                            :declared-additives #{})
     ;; clean-in-place hygiene score 62 against a floor of 75
     "batch-eu-2410" (assoc soft-drink :jurisdiction :eu/dg-sante
                            :sanitation-score 62)
     ;; still flavoured drink at 22.0 Brix against its 5.0-15.0 style window
     "batch-jp-2411" (assoc soft-drink :jurisdiction :jp/mhlw
                            :product-type :beverage/still-flavored
                            :co2-volumes 0.0
                            :brix-percent 22.0)
     ;; 260ppm preservative residue over the 200ppm product ceiling
     ;; (declared, so this is an overdose and not a labelling gap)
     "batch-us-2412" (assoc soft-drink :jurisdiction :us/fda
                            :preservative-ppm 260)
     ;; 24mL standard-of-fill drift against a 15mL tolerance
     "batch-eu-2413" (assoc soft-drink :jurisdiction :eu/dg-sante
                            :fill-volume-variance-ml 24)}))

(defn seed-db
  "A fresh store seeded with `demo-batches` and an empty audit ledger."
  [now-epoch-ms]
  {:batches (demo-batches now-epoch-ms)
   :facts []})
