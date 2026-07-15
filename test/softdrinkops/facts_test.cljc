(ns softdrinkops.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [softdrinkops.facts :as facts]))

;; ──────────────────────── Product Type Lookups ──────────────────────

(deftest product-type-by-id-test
  (testing "carbonated soft drink product type exists"
    (let [p (facts/product-type-by-id :beverage/carbonated-soft-drink)]
      (is (some? p))
      (is (= (:id p) :beverage/carbonated-soft-drink))
      (is (= (:co2-volumes-target p) 3.5))
      (is (= (:brix-max-percent p) 12.0))))

  (testing "still flavored drink product type exists"
    (let [p (facts/product-type-by-id :beverage/still-flavored)]
      (is (some? p))
      (is (= (:co2-volumes-target p) 0.0))
      (is (= (:brix-max-percent p) 15.0))))

  (testing "mineral water product type exists with a mineral-content minimum"
    (let [p (facts/product-type-by-id :water/mineral)]
      (is (some? p))
      (is (= (:mineral-content-min-mg-per-l p) 250))
      (is (= (:preservative-max-ppm p) 0))))

  (testing "other bottled water product type exists with no mineral-content minimum"
    (let [p (facts/product-type-by-id :water/bottled)]
      (is (some? p))
      (is (= (:mineral-content-min-mg-per-l p) 0))
      (is (= (:microbial-load-max-cfu-per-ml p) 20))))

  (testing "nonexistent product type returns nil"
    (is (nil? (facts/product-type-by-id :beverage/nonexistent)))))

;; ──────────────────────── Jurisdiction Lookups ──────────────────────

(deftest jurisdiction-by-id-test
  (testing "JP MHLW jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :jp/mhlw)]
      (is (some? j))
      (is (true? (:preservative-declaration-required j)))
      (is (= (:preservative-declaration-threshold-ppm j) 1))))

  (testing "US FDA jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :us/fda)]
      (is (some? j))
      (is (= (:preservative-declaration-threshold-ppm j) 1))))

  (testing "EU DG-SANTE jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :eu/dg-sante)]
      (is (some? j))
      (is (= (:preservative-declaration-threshold-ppm j) 1))))

  (testing "nonexistent jurisdiction returns nil"
    (is (nil? (facts/jurisdiction-by-id :xx/unknown)))))

;; ──────────────────────── Preservative Declaration ──────────────────────

(deftest preservative-declaration-required-test
  (testing "preservative residue above threshold requires declaration"
    (is (true? (facts/preservative-declaration-required? :us/fda 15))))

  (testing "preservative residue at threshold requires declaration"
    (is (true? (facts/preservative-declaration-required? :us/fda 1))))

  (testing "preservative residue below threshold does not require declaration"
    (is (false? (facts/preservative-declaration-required? :us/fda 0))))

  (testing "accepts a resolved jurisdiction map"
    (let [j (facts/jurisdiction-by-id :jp/mhlw)]
      (is (false? (facts/preservative-declaration-required? j 0)))
      (is (true? (facts/preservative-declaration-required? j 1))))))

(deftest preservative-declaration-complete-test
  (testing "declaration present when required passes"
    (is (true? (facts/preservative-declaration-complete? :us/fda 15 #{:preservatives}))))

  (testing "declaration missing when required fails"
    (is (false? (facts/preservative-declaration-complete? :us/fda 15 #{}))))

  (testing "declaration not required when residue below threshold passes regardless"
    (is (true? (facts/preservative-declaration-complete? :us/fda 0 #{}))))

  (testing "declaring preservatives even when not required is conservative and passes"
    (is (true? (facts/preservative-declaration-complete? :us/fda 0 #{:preservatives})))))

;; ──────────────────────── Evidence Completeness ──────────────────────

(deftest required-evidence-satisfied-test
  (testing "complete evidence checklist passes"
    (let [j (facts/jurisdiction-by-id :us/fda)
          evidence [:water-source-record :mixing-log :carbonation-log :brix-test
                    :co2-volumes-test :microbial-test :mineral-content-test :fill-volume-check]]
      (is (true? (facts/required-evidence-satisfied? j evidence)))))

  (testing "incomplete evidence fails"
    (let [j (facts/jurisdiction-by-id :us/fda)
          evidence [:water-source-record :mixing-log]]
      (is (false? (facts/required-evidence-satisfied? j evidence))))))

;; ──────────────────────── Production Safety Predicates ──────────────────────

(deftest co2-volumes-in-tolerance-test
  (testing "carbonation at target passes"
    (let [p (facts/product-type-by-id :beverage/carbonated-soft-drink)]
      (is (true? (facts/co2-volumes-in-tolerance? 3.5 p)))))

  (testing "carbonation at lower tolerance boundary passes"
    (let [p (facts/product-type-by-id :beverage/carbonated-soft-drink)]
      (is (true? (facts/co2-volumes-in-tolerance? 3.2 p)))))

  (testing "carbonation below tolerance fails"
    (let [p (facts/product-type-by-id :beverage/carbonated-soft-drink)]
      (is (false? (facts/co2-volumes-in-tolerance? 3.1 p)))))

  (testing "carbonation above tolerance fails"
    (let [p (facts/product-type-by-id :beverage/carbonated-soft-drink)]
      (is (false? (facts/co2-volumes-in-tolerance? 3.9 p))))))

(deftest brix-in-range-test
  (testing "Brix within style window passes"
    (let [p (facts/product-type-by-id :beverage/carbonated-soft-drink)]
      (is (true? (facts/brix-in-range? 10.0 p)))))

  (testing "Brix below style window fails"
    (let [p (facts/product-type-by-id :beverage/carbonated-soft-drink)]
      (is (false? (facts/brix-in-range? 5.0 p)))))

  (testing "Brix above style window fails"
    (let [p (facts/product-type-by-id :beverage/carbonated-soft-drink)]
      (is (false? (facts/brix-in-range? 14.0 p))))))

(deftest preservative-within-max-test
  (testing "preservative at or below max passes"
    (let [p (facts/product-type-by-id :beverage/carbonated-soft-drink)]
      (is (true? (facts/preservative-within-max? 200 p)))
      (is (true? (facts/preservative-within-max? 50 p)))))

  (testing "preservative above max fails"
    (let [p (facts/product-type-by-id :beverage/carbonated-soft-drink)]
      (is (false? (facts/preservative-within-max? 250 p))))))

(deftest microbial-load-within-max-test
  (testing "microbial load at or below max passes"
    (let [p (facts/product-type-by-id :beverage/carbonated-soft-drink)]
      (is (true? (facts/microbial-load-within-max? 100 p)))
      (is (true? (facts/microbial-load-within-max? 50 p)))))

  (testing "microbial load above max fails"
    (let [p (facts/product-type-by-id :beverage/carbonated-soft-drink)]
      (is (false? (facts/microbial-load-within-max? 150 p))))))

(deftest fill-volume-in-range-test
  (testing "fill volume at target passes"
    (let [p (facts/product-type-by-id :beverage/carbonated-soft-drink)]
      (is (true? (facts/fill-volume-in-range? 500 p)))))

  (testing "fill volume within tolerance passes"
    (let [p (facts/product-type-by-id :beverage/carbonated-soft-drink)]
      (is (true? (facts/fill-volume-in-range? 505 p)))))

  (testing "fill volume outside tolerance fails"
    (let [p (facts/product-type-by-id :beverage/carbonated-soft-drink)]
      (is (false? (facts/fill-volume-in-range? 480 p))))))

(deftest mineral-content-meets-minimum-test
  (testing "mineral content at minimum passes"
    (let [p (facts/product-type-by-id :water/mineral)]
      (is (true? (facts/mineral-content-meets-minimum? 250 p)))))

  (testing "mineral content above minimum passes"
    (let [p (facts/product-type-by-id :water/mineral)]
      (is (true? (facts/mineral-content-meets-minimum? 400 p)))))

  (testing "mineral content below minimum fails"
    (let [p (facts/product-type-by-id :water/mineral)]
      (is (false? (facts/mineral-content-meets-minimum? 100 p))))))
