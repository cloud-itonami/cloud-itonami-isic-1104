(ns softdrinkops.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [softdrinkops.governor :as governor]))

(def ^:private now-ms #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))
(def ^:private ten-days-ago (- now-ms (* 10 24 60 60 1000)))
(def ^:private hundred-days-ago (- now-ms (* 100 24 60 60 1000)))

(def ^:private clean-batch
  {:product-type :beverage/carbonated-soft-drink
   :jurisdiction :us/fda
   :co2-volumes 3.5
   :brix-percent 10.0
   :preservative-ppm 50
   :microbial-load-cfu-per-ml 20
   :mineral-content-mg-per-l 0
   :fill-volume-variance-ml 5
   :contamination-detected? false
   :filling-line-last-calibration-date ten-days-ago
   :sanitation-score 85
   ;; preservative-ppm in clean-batch (50ppm) is well above the 1ppm
   ;; US/EU/JP preservative-declaration threshold, so the fixture must
   ;; declare :preservatives by default -- otherwise every test built on
   ;; this fixture would spuriously trip :additive-label-mismatch
   ;; regardless of what it is actually trying to exercise.
   ;; additive-label-violation-test below overrides this explicitly for
   ;; both the mismatch and match cases.
   :declared-additives #{:preservatives}
   :evidence-checklist [:water-source-record :mixing-log :carbonation-log :brix-test
                        :co2-volumes-test :microbial-test :mineral-content-test :fill-volume-check]})

;; ──────────────────────── Batch Registration (generalized) ──────────────────────

(deftest batch-not-registered-violation-test
  (testing "log-production-batch against an unregistered batch is a hard violation"
    (let [req {:op :log-production-batch :subject "batch-ghost"}
          prop {:cites [{:spec "21 CFR 165"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop {})]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :batch-not-registered) (:violations result)))))

  (testing "schedule-maintenance against an unregistered batch is also a hard violation"
    (let [req {:op :schedule-maintenance :subject "batch-ghost"}
          prop {:cites [] :value {} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop {})]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :batch-not-registered) (:violations result)))))

  (testing "a registered batch does not trigger this rule"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :schedule-maintenance :subject batch-id}
          prop {:cites [] :value {} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :batch-not-registered) (:violations result)))))))

;; ──────────────────────── Hard Violations ──────────────────────

(deftest spec-basis-violation-test
  (testing "proposal with no jurisdiction citation is a hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [] :value {:jurisdiction nil}}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :no-spec-basis) (:violations result)))))

  (testing "proposal with proper citation passes spec basis check"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "21 CFR 165"}] :value {:jurisdiction :us/fda}}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result))))))

;; ──────────────────────── Carbonation Tolerance Violations ──────────────────────

(deftest carbonation-violation-test
  (testing "batch with carbonation out of tolerance triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :co2-volumes 4.2)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Filling-Line-Manual"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :carbonation-out-of-tolerance) (:violations result)))))

  (testing "batch with carbonation in tolerance passes"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Filling-Line-Manual"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result)))))

  (testing "still-flavored drink has a much tighter carbonation tolerance than carbonated soft drink"
    (let [batch-id "batch-002"
          store {:batches {batch-id (assoc clean-batch
                                            :product-type :beverage/still-flavored
                                            :co2-volumes 0.3)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Filling-Line-Manual"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :carbonation-out-of-tolerance) (:violations result))))))

;; ──────────────────────── Brix Range Violations ──────────────────────

(deftest brix-violation-test
  (testing "batch with Brix out of the declared style's window triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :brix-percent 20.0)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "21 CFR 101"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :brix-out-of-range) (:violations result))))))

;; ──────────────────────── Preservative Violations ──────────────────────

(deftest preservative-violation-test
  (testing "batch with preservative residue exceeding the product's limit triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :preservative-ppm 250)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "21 CFR 172"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :preservative-exceeds-max) (:violations result)))))

  (testing "mineral water permits no added preservative at all (max 0)"
    (let [batch-id "batch-002"
          store {:batches {batch-id (assoc clean-batch
                                            :product-type :water/mineral
                                            :co2-volumes 0.0
                                            :brix-percent 0.0
                                            :mineral-content-mg-per-l 300
                                            :microbial-load-cfu-per-ml 10
                                            :preservative-ppm 1)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "21 CFR 172"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :preservative-exceeds-max) (:violations result))))))

;; ──────────────────────── Microbial Load Violations ──────────────────────

(deftest microbial-load-violation-test
  (testing "batch with microbial load exceeding the product's limit triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :microbial-load-cfu-per-ml 150)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "21 CFR 165"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :microbial-load-exceeded) (:violations result)))))

  (testing "the same microbial load that is fine for carbonated soft drink exceeds mineral water's stricter limit"
    (let [batch-id "batch-002"
          store {:batches {batch-id (assoc clean-batch :microbial-load-cfu-per-ml 50)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "21 CFR 165"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result))))
    (let [batch-id "batch-003"
          store {:batches {batch-id (assoc clean-batch
                                            :product-type :water/mineral
                                            :co2-volumes 0.0
                                            :brix-percent 0.0
                                            :preservative-ppm 0
                                            :declared-additives #{}
                                            :mineral-content-mg-per-l 300
                                            :microbial-load-cfu-per-ml 50)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "21 CFR 165"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :microbial-load-exceeded) (:violations result))))))

;; ──────────────────────── Mineral Content Violations ──────────────────────

(deftest mineral-content-violation-test
  (testing "mineral water batch below the labeling minimum mineral content triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch
                                            :product-type :water/mineral
                                            :co2-volumes 0.0
                                            :brix-percent 0.0
                                            :preservative-ppm 0
                                            :declared-additives #{}
                                            :microbial-load-cfu-per-ml 10
                                            :mineral-content-mg-per-l 100)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Directive 2009/54/EC"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :mineral-content-below-minimum) (:violations result))))))

;; ──────────────────────── Contamination Violations ──────────────────────

(deftest contamination-violation-test
  (testing "batch with detected contamination triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :contamination-detected? true)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "21 CFR 110"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :contamination-detected) (:violations result))))))

;; ──────────────────────── Filling-Line Calibration Violations ──────────────────────

(deftest filling-line-calibration-violation-test
  (testing "batch with overdue filling-line calibration triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :filling-line-last-calibration-date hundred-days-ago)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "21 CFR 101"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :filling-line-calibration-overdue) (:violations result))))))

;; ──────────────────────── Fill Volume Variance Violations ──────────────────────

(deftest fill-volume-variance-violation-test
  (testing "batch with excessive fill-volume variance triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :fill-volume-variance-ml 25)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "21 CFR 101"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :fill-volume-variance-excessive) (:violations result))))))

;; ──────────────────────── Additive Labeling Violations ──────────────────────

(deftest additive-label-violation-test
  (testing "preservative residue above declaration threshold without a declaration triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :preservative-ppm 15 :declared-additives #{})}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "21 CFR 101.22"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :additive-label-mismatch) (:violations result)))))

  (testing "preservative residue above declaration threshold WITH a declaration passes"
    (let [batch-id "batch-002"
          store {:batches {batch-id (assoc clean-batch :preservative-ppm 15 :declared-additives #{:preservatives})}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "21 CFR 101.22"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :additive-label-mismatch) (:violations result)))))))

;; ──────────────────────── Sanitation Score Violations ──────────────────────

(deftest sanitation-score-violation-test
  (testing "batch with insufficient sanitation score triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :sanitation-score 60)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "21 CFR 110"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :sanitation-score-insufficient) (:violations result))))))

;; ──────────────────────── Food-Safety Flag Violations ──────────────────────

(deftest food-safety-flag-unresolved-violation-test
  (testing "batch with an unresolved food-safety flag triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch
                                            :safety-concern-raised? true
                                            :safety-concern-resolved? false)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "21 CFR 110"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :food-safety-flag-unresolved) (:violations result)))))

  (testing "batch with a resolved food-safety flag does not trigger this rule"
    (let [batch-id "batch-002"
          store {:batches {batch-id (assoc clean-batch
                                            :safety-concern-raised? true
                                            :safety-concern-resolved? true)}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "21 CFR 110"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :food-safety-flag-unresolved) (:violations result)))))))

;; ──────────────────────── Escalation (Low Confidence) ──────────────────────

(deftest low-confidence-escalation-test
  (testing "low confidence proposal escalates even when hard checks pass"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :schedule-maintenance :subject batch-id}
          prop {:cites [{:spec "Equipment-Manual"}] :value {:jurisdiction :us/fda} :confidence 0.5}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── High Stakes Escalation ──────────────────────

(deftest high-stakes-escalation-test
  (testing "log-production-batch escalates even when all checks pass"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "21 CFR 165"}] :value {:jurisdiction :us/fda} :confidence 0.95}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── Flag Food-Safety Concern Always Escalates ──────────────────────

(deftest flag-food-safety-concern-escalation-test
  (testing "flag-food-safety-concern escalates even when Governor checks are clean and confidence is high"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :flag-food-safety-concern :subject batch-id}
          prop {:cites [{:spec "Plant-HACCP-Plan"}] :value {:jurisdiction :us/fda} :confidence 0.99}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── Already Processed Violation ──────────────────────

(deftest already-processed-violation-test
  (testing "batch already processed triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id
                           {:product-type :beverage/carbonated-soft-drink
                            :processed? true}}}
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "21 CFR 165"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :already-processed) (:violations result))))))

;; ──────────────────────── Already Shipment Finalized Violation ──────────────────────

(deftest already-shipment-finalized-violation-test
  (testing "batch shipment already finalized triggers hard violation"
    (let [batch-id "batch-001"
          store {:batches {batch-id (assoc clean-batch :shipment-finalized? true)}}
          req {:op :coordinate-shipment :subject batch-id}
          prop {:cites [{:spec "Shipment-Manual"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :already-shipment-finalized) (:violations result))))))

;; ──────────────────────── Op-Not-Allowed (Closed Allowlist) ──────────────────────

(deftest op-not-allowed-violation-test
  (testing "direct filling-line control is a hard, permanent block"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :actuate-filling-line :subject batch-id}
          prop {:cites [{:spec "Filling-Line-Manual"}] :value {:jurisdiction :us/fda} :confidence 0.99}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :op-not-allowed) (:violations result)))))

  (testing "food-safety-certification authority is a hard, permanent block"
    (let [batch-id "batch-002"
          store {:batches {batch-id clean-batch}}
          req {:op :certify-food-safety :subject batch-id}
          prop {:cites [{:spec "Plant-HACCP-Plan"}] :value {:jurisdiction :us/fda} :confidence 0.99}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :op-not-allowed) (:violations result))))))

;; ──────────────────────── Effect Not Propose ──────────────────────

(deftest effect-not-propose-violation-test
  (testing "a proposal asserting a non-:propose effect is a hard, permanent block"
    (let [batch-id "batch-001"
          store {:batches {batch-id clean-batch}}
          req {:op :schedule-maintenance :subject batch-id}
          prop {:cites [{:spec "Equipment-Manual"}] :value {:jurisdiction :us/fda} :effect :commit :confidence 0.9}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :effect-not-propose) (:violations result))))))
