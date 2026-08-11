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
