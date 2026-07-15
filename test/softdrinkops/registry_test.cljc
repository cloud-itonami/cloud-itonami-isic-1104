(ns softdrinkops.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [softdrinkops.registry :as registry]))

;; ──────────────────────── Carbonation Tolerance ──────────────────────

(deftest carbonation-out-of-tolerance-test
  (testing "carbonation at target with no tolerance returns false"
    (is (false? (registry/carbonation-out-of-tolerance? 3.5 3.5 0.3))))

  (testing "carbonation within tolerance range returns false"
    (is (false? (registry/carbonation-out-of-tolerance? 3.7 3.5 0.3))))

  (testing "carbonation below tolerance returns true (violation)"
    (is (true? (registry/carbonation-out-of-tolerance? 3.1 3.5 0.3))))

  (testing "carbonation above tolerance returns true (violation)"
    (is (true? (registry/carbonation-out-of-tolerance? 3.9 3.5 0.3)))))

;; ──────────────────────── Brix Range ──────────────────────

(deftest brix-out-of-range-test
  (testing "Brix within range returns false (no violation)"
    (is (false? (registry/brix-out-of-range? 10.0 8.0 12.0))))

  (testing "Brix below minimum returns true (violation)"
    (is (true? (registry/brix-out-of-range? 5.0 8.0 12.0))))

  (testing "Brix above maximum returns true (violation)"
    (is (true? (registry/brix-out-of-range? 14.0 8.0 12.0)))))

;; ──────────────────────── Preservative Residue ──────────────────────

(deftest preservative-exceeds-max-test
  (testing "preservative within max returns false (no violation)"
    (is (false? (registry/preservative-exceeds-max? 100 200))))

  (testing "preservative at max returns false"
    (is (false? (registry/preservative-exceeds-max? 200 200))))

  (testing "preservative exceeding max returns true (violation)"
    (is (true? (registry/preservative-exceeds-max? 250 200)))))

;; ──────────────────────── Microbial Load ──────────────────────

(deftest microbial-load-exceeds-max-test
  (testing "microbial load within limit returns false (no violation)"
    (is (false? (registry/microbial-load-exceeds-max? 50 100))))

  (testing "microbial load at limit returns false"
    (is (false? (registry/microbial-load-exceeds-max? 100 100))))

  (testing "microbial load exceeding limit returns true (violation)"
    (is (true? (registry/microbial-load-exceeds-max? 150 100)))))

;; ──────────────────────── Mineral Content ──────────────────────

(deftest mineral-content-below-minimum-test
  (testing "mineral content at minimum returns false (no violation)"
    (is (false? (registry/mineral-content-below-minimum? 250 250))))

  (testing "mineral content above minimum returns false"
    (is (false? (registry/mineral-content-below-minimum? 400 250))))

  (testing "mineral content below minimum returns true (violation)"
    (is (true? (registry/mineral-content-below-minimum? 100 250)))))

;; ──────────────────────── Filling-Line Calibration ──────────────────────

(deftest filling-line-calibration-overdue-test
  (testing "recent calibration returns false (no violation)"
    ;; Assume calibrated 30 days ago
    (let [now #?(:clj (System/currentTimeMillis) :cljs (.now js/Date))
          thirty-days-ago (- now (* 30 24 60 60 1000))]
      (is (false? (registry/filling-line-calibration-overdue? thirty-days-ago now)))))

  (testing "overdue calibration returns true (violation)"
    (let [now #?(:clj (System/currentTimeMillis) :cljs (.now js/Date))
          hundred-days-ago (- now (* 100 24 60 60 1000))]
      (is (true? (registry/filling-line-calibration-overdue? hundred-days-ago now))))))

;; ──────────────────────── Fill Volume Variance ──────────────────────

(deftest fill-volume-variance-excessive-test
  (testing "variance within tolerance returns false (no violation)"
    (is (false? (registry/fill-volume-variance-excessive? 10 15))))

  (testing "variance at tolerance returns false"
    (is (false? (registry/fill-volume-variance-excessive? 15 15))))

  (testing "variance exceeding tolerance returns true (violation)"
    (is (true? (registry/fill-volume-variance-excessive? 16 15)))))

;; ──────────────────────── Additive Labeling ──────────────────────

(deftest additive-label-mismatch-test
  (testing "preservative below threshold returns false (no risk) regardless of declaration"
    (is (false? (registry/additive-label-mismatch? 0 1 #{}))))

  (testing "preservative at/above threshold with preservatives declared returns false (no risk)"
    (is (false? (registry/additive-label-mismatch? 15 1 #{:preservatives}))))

  (testing "preservative at/above threshold without preservatives declared returns true (risk)"
    (is (true? (registry/additive-label-mismatch? 15 1 #{})))))

;; ──────────────────────── Contamination ──────────────────────

(deftest contamination-detected-test
  (testing "no detection returns false"
    (is (false? (registry/contamination-detected? false)))
    (is (false? (registry/contamination-detected? nil))))

  (testing "detection returns true"
    (is (true? (registry/contamination-detected? true)))))

;; ──────────────────────── Sanitation Score ──────────────────────

(deftest sanitation-score-insufficient-test
  (testing "score at minimum returns false (no violation)"
    (is (false? (registry/sanitation-score-insufficient? 75 75))))

  (testing "score above minimum returns false"
    (is (false? (registry/sanitation-score-insufficient? 85 75))))

  (testing "score below minimum returns true (violation)"
    (is (true? (registry/sanitation-score-insufficient? 74 75)))))
