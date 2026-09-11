(ns softdrinkops.governor
  "SoftDrinkOps Governor -- the independent compliance layer that earns
  the SoftDrinkOpsAdvisor the right to commit. The LLM has no notion of:
    - Whether a batch's actual carbonation level (CO2 volumes) stayed
      within the declared product's tolerance band (risking either a
      flat, off-spec product or an over-pressurized container safety
      hazard)
    - Whether the batch's Brix (sugar content) falls within the declared
      style's window (regular/diet/plain-water label accuracy)
    - Whether added-preservative residue exceeds the product's
      spoilage/quality ceiling
    - Whether the batch's total-plate-count (microbial load) exceeds the
      product's regulatory action level
    - Whether the batch's mineral content meets the minimum required for
      a legal \"mineral water\" label claim
    - Whether contamination (foreign material / off-flavor / spoilage
      marker) was detected in the batch
    - Whether the filling-line fill-volume metering equipment calibration
      is current
    - Whether finished-product fill-volume variance is within the
      standard-of-fill tolerance
    - Whether preservative/additive labeling is complete and accurate
    - Whether plant clean-in-place (CIP) sanitation score is passed
    - Whether an open food-safety concern (e.g. microbial contamination,
      mineral-content mislabeling) has been resolved
    - Whether the plant/batch record was independently verified and
      registered before any proposal is made against it

  This MUST be a separate system able to *reject* a proposal and fall back
  to HOLD.

  Unlike direct mixing/carbonation/filling-line control (NEVER done by
  this actor -- mixing-tank, carbonator, and filling-line equipment
  operation remain exclusive to plant staff) OR food-safety-certification
  authority decisions (NEVER done by this actor -- issuing or renewing a
  plant's food-safety certification is exclusively a human inspector/
  regulator decision), the Governor operates on batch metadata:
  provenance, production parameters, sanitation records, and food-safety
  flags. This is plant-operations coordination, not process control and
  not certification authority.

  CRITICAL: Any proposal involving food-safety concerns (microbial-load
  excess, contamination detection, mineral-content mislabeling,
  sanitation failures, preservative-label mismatch) ALWAYS escalates to
  human operator for final sign-off. The LLM's confidence is never
  sufficient for food-safety decisions.

  Hard violations (always HOLD, no override):
    1. Operation outside the closed allowlist (includes any proposal
       that would touch mixing/carbonation/filling-line control or
       food-safety-certification authority decisions)
    2. Proposal asserting an `:effect` other than `:propose`
    3. Plant/batch record not independently verified/registered before
       any proposal is made against it
    4. No jurisdiction citation (jurisdiction unknown -> can't verify reqs)
    5. Evidence incomplete (missing required-evidence per jurisdiction)
    6. Carbonation level out of the declared product's tolerance band
    7. Brix (sugar content) out of the declared style's window
    8. Added-preservative residue exceeds the product's maximum
    9. Microbial load (total-plate-count) exceeds the product's
       regulatory action level
   10. Mineral content below the minimum for a \"mineral water\" label claim
   11. Contamination detected (foreign material / off-flavor / spoilage
       marker)
   12. Filling-line fill-volume metering calibration overdue
   13. Fill-volume variance excessive (standard-of-fill drift)
   14. Additive/preservative labeling mismatch (food-safety / labeling
       violation)
   15. Plant clean-in-place (CIP) sanitation score insufficient
   16. Food-safety flag unresolved (open concern, escalate required)
   17. Batch already processed / shipment already finalized (double-commit guards)

  Soft gates (always escalate for human):
    - Low confidence
    - Real actuation (`:log-production-batch`, `:coordinate-shipment`)
    - `:flag-food-safety-concern` (never auto-resolved by confidence alone)

  This design mirrors `wineops.governor` and `sugarops.governor` but
  specializes on soft-drink/bottled-water-manufacturing-specific
  concerns: carbonation-tolerance/pressurization-safety boundaries,
  Brix style accuracy, microbial-load food-safety risk, and
  mineral-content label accuracy -- rather than wine-specific ABV-
  tolerance/vintage-date-label or sugar-refining-specific SO2/
  polarization/color/ash-content purity grading."
  (:require [softdrinkops.facts :as facts]
            [softdrinkops.registry :as registry]
            [softdrinkops.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Logging a batch into production records (`:log-production-batch`) and
  coordinating shipment of finished product (`:coordinate-shipment`) are
  the two real-world actuation events this actor performs. Both require
  plant-operator sign-off."
  #{:log-production-batch :coordinate-shipment})

(def always-escalate-ops
  "Operations that always require human sign-off, even when the Governor's
  hard checks are clean and confidence is high: the two high-stakes
  actuation events (`high-stakes`) plus `:flag-food-safety-concern` --
  a food-safety concern (microbial-load excess, contamination,
  mineral-content mislabeling) is never auto-resolved by advisor
  confidence alone, it always needs a human look."
  (conj high-stakes :flag-food-safety-concern))

(def allowed-ops
  "Closed allowlist of proposal operations this actor may ever make. Any
  proposal for an operation outside this set -- most importantly direct
  mixing/carbonation/filling-line control (mixing-tank, carbonator, or
  filling-line equipment operation) or food-safety-certification
  authority decisions (e.g. issuing or renewing a plant's food-safety
  certification) -- is a hard, permanent block: this actor coordinates
  plant operations, it does not operate equipment and it has no
  certification authority."
  #{:log-production-batch :schedule-maintenance :flag-food-safety-concern :coordinate-shipment})

;; ────────────────────────── Checks ──────────────────────────

(defn- op-not-allowed-violations
  "HARD, permanent block: any proposal outside the closed operation
  allowlist (e.g. direct mixing/carbonation/filling-line control, or a
  food-safety-certification-authority decision) is refused unconditionally
  -- this actor has no authority to make such a proposal at all, let
  alone commit it."
  [{:keys [op]} _proposal]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowed
      :detail (str op " はこのactorの許可された提案種別 (log-production-batch/"
                  "schedule-maintenance/flag-food-safety-concern/coordinate-shipment) "
                  "に含まれない -- 混合/炭酸注入/充填ライン制御や食品安全認証の権限はこのactorに無い")}]))

(defn- effect-not-propose-violations
  "HARD invariant: this actor's proposals are always `:effect :propose` --
  it never claims direct write/actuation authority for itself. A proposal
  asserting any other effect is refused unconditionally."
  [_request proposal]
  (when-let [effect (:effect proposal)]
    (when (not= effect :propose)
      [{:rule :effect-not-propose
        :detail (str "この actor の提案は :propose 以外の :effect を持てない (got " effect ")")}])))

(defn- batch-not-registered-violations
  "HARD invariant: a plant/batch record must be independently verified/
  registered in the store BEFORE ANY proposal (not just shipment
  coordination) can be made against it -- this actor coordinates
  operations for an already-registered batch, it never invents or
  self-registers one from an unverified proposal."
  [{:keys [op subject]} st]
  (when (contains? allowed-ops op)
    (when-not (store/production-batch st subject)
      [{:rule :batch-not-registered
        :detail (str subject " は工場に登録されたバッチ記録が無い -- 提案は進められない")}])))

(defn- spec-basis-violations
  "A proposal with no jurisdiction citation is a HARD violation -- never
  invent a jurisdiction's food-safety/labeling requirements."
  [{:keys [op]} proposal]
  (when (contains?
         #{:log-production-batch :coordinate-shipment :flag-food-safety-concern}
         op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :jurisdiction) (nil? (:jurisdiction value))))
        [{:rule :no-spec-basis
          :detail "公式仕様の引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:log-production-batch`, verify the batch's evidence checklist is
  complete per jurisdiction requirements."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when-not (and b
                     (facts/required-evidence-satisfied?
                      (:jurisdiction b)
                      (:evidence-checklist b)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(water-source-record/mixing-log/carbonation-log/brix-test/co2-volumes-test/microbial-test等)が充足していない状態での提案"}]))))

(defn- carbonation-out-of-tolerance-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  actual carbonation level falls within the declared product's tolerance
  band via `registry/carbonation-out-of-tolerance?`. Evaluated
  UNCONDITIONALLY."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:co2-volumes b)
                 (registry/carbonation-out-of-tolerance?
                  (:co2-volumes b)
                  (:co2-volumes-target p)
                  (:co2-volumes-tolerance p)))
        [{:rule :carbonation-out-of-tolerance
          :detail (str subject " の炭酸ガス圧(" (:co2-volumes b)
                      " vol)が製品規格の許容誤差(target " (:co2-volumes-target p)
                      " vol ±" (:co2-volumes-tolerance p)
                      " vol)を外れる -- バッチ登録提案は進められない")}]))))

(defn- brix-out-of-range-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  Brix (sugar content) falls within the product's declared-style window
  via `registry/brix-out-of-range?`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:brix-percent b)
                 (registry/brix-out-of-range?
                  (:brix-percent b)
                  (:brix-min-percent p)
                  (:brix-max-percent p)))
        [{:rule :brix-out-of-range
          :detail (str subject " の糖度(" (:brix-percent b)
                      " Brix)が製品規格の範囲外 -- バッチ登録提案は進められない")}]))))

(defn- preservative-exceeds-max-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  added-preservative residue does not exceed the product's maximum via
  `registry/preservative-exceeds-max?`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:preservative-ppm b)
                 (registry/preservative-exceeds-max?
                  (:preservative-ppm b)
                  (:preservative-max-ppm p)))
        [{:rule :preservative-exceeds-max
          :detail (str subject " の保存料残留量(" (:preservative-ppm b)
                      " ppm)が製品規格上限(" (:preservative-max-ppm p)
                      " ppm)を超過 -- バッチ登録提案は進められない")}]))))

(defn- microbial-load-exceeded-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  microbial load (total-plate-count) does not exceed the product's
  maximum via `registry/microbial-load-exceeds-max?`. Evaluated
  UNCONDITIONALLY -- this is the single most serious food-safety hazard
  specific to soft-drink/bottled-water production."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:microbial-load-cfu-per-ml b)
                 (registry/microbial-load-exceeds-max?
                  (:microbial-load-cfu-per-ml b)
                  (:microbial-load-max-cfu-per-ml p)))
        [{:rule :microbial-load-exceeded
          :detail (str subject " の生菌数(" (:microbial-load-cfu-per-ml b)
                      " CFU/mL)が規制上限(" (:microbial-load-max-cfu-per-ml p)
                      " CFU/mL)を超過 -- バッチ登録提案は進められない")}]))))

(defn- mineral-content-below-minimum-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  mineral content meets the product's minimum required for a legal
  \"mineral water\" label claim via
  `registry/mineral-content-below-minimum?`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:mineral-content-mg-per-l b)
                 (registry/mineral-content-below-minimum?
                  (:mineral-content-mg-per-l b)
                  (:mineral-content-min-mg-per-l p)))
        [{:rule :mineral-content-below-minimum
          :detail (str subject " のミネラル分(" (:mineral-content-mg-per-l b)
                      " mg/L)がミネラルウォーター表示の最低要件(" (:mineral-content-min-mg-per-l p)
                      " mg/L)を下回る -- バッチ登録提案は進められない")}]))))

(defn- contamination-detected-violations
  "For `:log-production-batch`, INDEPENDENTLY verify the batch's own
  contamination-detection result via `registry/contamination-detected?`.
  A detection on THIS batch's own inspection is a hard, physical/quality-
  hazard block -- distinct from `food-safety-flag-unresolved-violations`
  below, which covers a separately-raised, not-yet-resolved concern."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (registry/contamination-detected? (:contamination-detected? b)))
        [{:rule :contamination-detected
          :detail (str subject " で異物混入または異臭/腐敗マーカーが検出された -- バッチ登録提案は進められない")}]))))

(defn- now-epoch-ms
  "Current time in epoch milliseconds, portable across Clojure/
  ClojureScript. Isolated to this single call site so the rest of the
  namespace (and all of `softdrinkops.registry`) stays free of host-clock
  calls."
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (js/Date.now)))

(defn- filling-line-calibration-overdue-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the
  mixing/carbonation/filling-line fill-volume metering equipment's
  calibration is current (recalibration required every 90 days)."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (:filling-line-last-calibration-date b)
                 (registry/filling-line-calibration-overdue? (:filling-line-last-calibration-date b) (now-epoch-ms)))
        [{:rule :filling-line-calibration-overdue
          :detail (str subject " の充填ライン計量機校正が期限切れ -- バッチ登録提案は進められない")}]))))

(defn- fill-volume-variance-excessive-violations
  "For `:log-production-batch`, INDEPENDENTLY verify the fill-volume
  variance."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (:fill-volume-variance-ml b)
                 (registry/fill-volume-variance-excessive? (:fill-volume-variance-ml b) 15))
        [{:rule :fill-volume-variance-excessive
          :detail (str subject " の充填量分散(" (:fill-volume-variance-ml b)
                      "mL)が許容範囲(15mL)を超過 -- バッチ登録提案は進められない")}]))))

(defn- additive-label-mismatch-violations
  "For `:log-production-batch`, INDEPENDENTLY verify preservative/additive
  declaration completeness and accuracy via
  `registry/additive-label-mismatch?`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          j (when b (facts/jurisdiction-by-id (:jurisdiction b)))
          threshold (when j (:preservative-declaration-threshold-ppm j))]
      (when (and b threshold (:preservative-ppm b)
                 (registry/additive-label-mismatch? (:preservative-ppm b) threshold (:declared-additives b)))
        [{:rule :additive-label-mismatch
          :detail (str subject " の保存料/添加物宣言が不完全 -- バッチ登録提案は進められない")}]))))

(defn- sanitation-score-insufficient-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the plant's
  clean-in-place (CIP) sanitation score meets minimum requirements."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (:sanitation-score b)
                 (registry/sanitation-score-insufficient? (:sanitation-score b) 75))
        [{:rule :sanitation-score-insufficient
          :detail (str subject " の工場衛生スコア(" (:sanitation-score b)
                      ")が最低要件(75)を下回る -- バッチ登録提案は進められない")}]))))

(defn- food-safety-flag-unresolved-violations
  "An unresolved food-safety flag is a HARD, un-overridable hold.
  Food-safety concerns (suspected microbial contamination, mineral-
  content mislabeling, excess preservative residue) raised during
  production or inspection MUST be resolved before the batch can be
  logged. Evaluated UNCONDITIONALLY at `:log-production-batch`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and (true? (:safety-concern-raised? b))
                 (not (true? (:safety-concern-resolved? b))))
        [{:rule :food-safety-flag-unresolved
          :detail (str subject " は未解決の食品安全フラグがある -- バッチ登録提案は進められない")}]))))

(defn- already-processed-violations
  "For `:log-production-batch`, refuse to process the SAME batch twice, off
  a dedicated `:processed?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (when (store/batch-already-processed? st subject)
      [{:rule :already-processed
        :detail (str subject " は既に登録済み")}])))

(defn- already-shipment-finalized-violations
  "For `:coordinate-shipment`, refuse to finalize the SAME batch's shipment
  twice, off a dedicated `:shipment-finalized?` fact."
  [{:keys [op subject]} st]
  (when (= op :coordinate-shipment)
    (when (store/batch-shipment-finalized? st subject)
      [{:rule :already-shipment-finalized
        :detail (str subject " は既に出荷確定済み")}])))

(defn check
  "Censors a SoftDrinkOpsAdvisor proposal against the Governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}.

  Stakes (high-stakes actuation vs. always-escalate) are read off the
  REQUEST's `:op` -- not off the proposal -- since the operation being
  proposed (not the advisor's self-reported stake) is what determines
  whether a human must sign off."
  [request _context proposal st]
  (let [hard (into []
                   (concat (op-not-allowed-violations request proposal)
                           (effect-not-propose-violations request proposal)
                           (batch-not-registered-violations request st)
                           (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (carbonation-out-of-tolerance-violations request st)
                           (brix-out-of-range-violations request st)
                           (preservative-exceeds-max-violations request st)
                           (microbial-load-exceeded-violations request st)
                           (mineral-content-below-minimum-violations request st)
                           (contamination-detected-violations request st)
                           (filling-line-calibration-overdue-violations request st)
                           (fill-volume-variance-excessive-violations request st)
                           (additive-label-mismatch-violations request st)
                           (sanitation-score-insufficient-violations request st)
                           (food-safety-flag-unresolved-violations request st)
                           (already-processed-violations request st)
                           (already-shipment-finalized-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        actuation? (boolean (high-stakes (:op request)))
        escalate-op? (boolean (always-escalate-ops (:op request)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not escalate-op?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? escalate-op?))
     :high-stakes? actuation?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
