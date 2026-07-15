(ns softdrinkops.facts
  "Reference facts for soft-drink and bottled-water manufacturing: product-
  style production parameters (carbonation/Brix-sugar-content/preservative/
  microbial-load/fill-volume/mineral-content windows), jurisdiction
  additive-declaration and evidence-checklist requirements. This namespace
  contains pure lookup functions for regulatory/food-safety compliance
  checks -- the Governor calls these to independently validate proposals;
  the advisor's confidence is never sufficient on its own."
  (:require [clojure.set :as set]))

(def product-types
  "Valid soft-drink/bottled-water product categories and their safe
  production windows. `co2-volumes-target/co2-volumes-tolerance` is the
  carbonation-level window (volumes of CO2 dissolved per volume of product,
  the standard beverage-industry carbonation metric) -- crossing the
  tolerance risks either a flat, off-spec product or an over-pressurized
  container safety hazard. `brix-min/max-percent` is the finished-product
  sugar-content (degrees Brix) window that defines the style (regular vs.
  diet/zero vs. still spring water with no sugar at all).
  `preservative-max-ppm` is the maximum allowable added-preservative
  residue (e.g. sodium benzoate / potassium sorbate; natural mineral water
  and other bottled waters permit none at all under most jurisdictions'
  bottled-water standards, hence 0 for the water styles below).
  `microbial-load-max-cfu-per-ml` is the maximum allowable total-plate-
  count in the finished, sealed product -- source waters (mineral/bottled)
  carry a stricter ceiling than mixed/carbonated soft drinks because they
  have no thermal-processing or preservative barrier.
  `fill-volume-target/tolerance-ml` is the standard-of-fill packaging
  window (net-contents accuracy, e.g. US NIST Handbook 133 / 21 CFR
  Part 101). `mineral-content-min-mg-per-l` is the minimum total-dissolved-
  solids (TDS) mineral content a finished product must contain to legally
  carry a \"mineral water\" label claim -- soft drinks and plain bottled/
  purified water carry no such minimum (0)."
  {:beverage/carbonated-soft-drink
   {:id :beverage/carbonated-soft-drink
    :name "炭酸清涼飲料水"
    :co2-volumes-target 3.5
    :co2-volumes-tolerance 0.3
    :brix-min-percent 8.0
    :brix-max-percent 12.0
    :preservative-max-ppm 200
    :microbial-load-max-cfu-per-ml 100
    :fill-volume-target-ml 500
    :fill-volume-tolerance-ml 10
    :mineral-content-min-mg-per-l 0}

   :beverage/still-flavored
   {:id :beverage/still-flavored
    :name "非炭酸清涼飲料水(果汁・フレーバー飲料)"
    :co2-volumes-target 0.0
    :co2-volumes-tolerance 0.1
    :brix-min-percent 5.0
    :brix-max-percent 15.0
    :preservative-max-ppm 200
    :microbial-load-max-cfu-per-ml 100
    :fill-volume-target-ml 500
    :fill-volume-tolerance-ml 10
    :mineral-content-min-mg-per-l 0}

   :water/mineral
   {:id :water/mineral
    :name "ミネラルウォーター(natural mineral water)"
    :co2-volumes-target 0.0
    :co2-volumes-tolerance 0.1
    :brix-min-percent 0.0
    :brix-max-percent 0.5
    :preservative-max-ppm 0
    :microbial-load-max-cfu-per-ml 20
    :fill-volume-target-ml 500
    :fill-volume-tolerance-ml 10
    :mineral-content-min-mg-per-l 250}

   :water/bottled
   {:id :water/bottled
    :name "ボトルドウォーター(purified/spring water, other bottled waters)"
    :co2-volumes-target 0.0
    :co2-volumes-tolerance 0.1
    :brix-min-percent 0.0
    :brix-max-percent 0.5
    :preservative-max-ppm 0
    :microbial-load-max-cfu-per-ml 20
    :fill-volume-target-ml 500
    :fill-volume-tolerance-ml 10
    :mineral-content-min-mg-per-l 0}})

(defn product-type-by-id [id]
  (get product-types id))

(def jurisdictions
  "Soft-drink/bottled-water-manufacturing jurisdictions and their
  additive-declaration and evidence-checklist requirements. Added
  preservatives (sodium benzoate / potassium sorbate etc.) are a
  regulated, label-relevant additive -- the >=1 ppm \"must appear in the
  ingredient list\" declaration threshold used here reflects the
  conservative, widely-adopted convention across US (FDA), EU, and Japan
  (each requires disclosure of any functionally-added preservative
  regardless of trace level, unlike wine's higher naturally-occurring-SO2
  sulfite threshold). Japan's soft-drink/bottled-water safety-standard
  authority is 厚生労働省 (Ministry of Health, Labour and Welfare) under the
  食品衛生法 (Food Sanitation Act) 清涼飲料水の規格基準 (\"Standards for Soft
  Drinks\"), with labeling overseen by 消費者庁 (Consumer Affairs Agency) --
  distinct from the mineral-water-specific quality-labeling guideline
  (ミネラルウォーター類の品質表示ガイドライン)."
  {:jp/mhlw
   {:id :jp/mhlw
    :name "日本 (食品衛生法・清涼飲料水の規格基準, 厚生労働省/消費者庁)"
    :preservative-declaration-required true
    :preservative-declaration-threshold-ppm 1
    :required-evidence
    [:water-source-record
     :mixing-log
     :carbonation-log
     :brix-test
     :co2-volumes-test
     :microbial-test
     :mineral-content-test
     :fill-volume-check]}

   :us/fda
   {:id :us/fda
    :name "United States (FDA 21 CFR Part 165 / Part 110 / Part 101)"
    :preservative-declaration-required true
    :preservative-declaration-threshold-ppm 1
    :required-evidence
    [:water-source-record
     :mixing-log
     :carbonation-log
     :brix-test
     :co2-volumes-test
     :microbial-test
     :mineral-content-test
     :fill-volume-check]}

   :eu/dg-sante
   {:id :eu/dg-sante
    :name "European Union (Reg (EC) 1333/2008 / Directive 2009/54/EC)"
    :preservative-declaration-required true
    :preservative-declaration-threshold-ppm 1
    :required-evidence
    [:water-source-record
     :mixing-log
     :carbonation-log
     :brix-test
     :co2-volumes-test
     :microbial-test
     :mineral-content-test
     :fill-volume-check]}})

(defn jurisdiction-by-id [id]
  (get jurisdictions id))

(defn preservative-declaration-required?
  "True when `preservative-ppm` crosses the jurisdiction's
  preservative-declaration threshold and therefore the finished product
  must list the preservative in its ingredient declaration. `jurisdiction`
  may be a resolved jurisdiction map or a raw jurisdiction id."
  [jurisdiction preservative-ppm]
  (let [j (if (map? jurisdiction) jurisdiction (jurisdiction-by-id jurisdiction))]
    (boolean
     (and j preservative-ppm
          (>= preservative-ppm (:preservative-declaration-threshold-ppm j))))))

(defn preservative-declaration-complete?
  "Verify that when preservative declaration is required for
  `preservative-ppm` under `jurisdiction`, `:preservatives` is present in
  `declared`. Declaring preservatives even when not strictly required is
  conservative and always passes."
  [jurisdiction preservative-ppm declared]
  (if (preservative-declaration-required? jurisdiction preservative-ppm)
    (contains? (set declared) :preservatives)
    true))

(defn required-evidence-satisfied?
  "Verify that every item in the jurisdiction's `:required-evidence` list
  is present in `evidence`. `jurisdiction` may be a resolved jurisdiction
  map (as returned by `jurisdiction-by-id`) or a raw jurisdiction id --
  both call conventions are in use (tests pass a resolved map; the
  Governor passes the raw id straight off batch metadata)."
  [jurisdiction evidence]
  (let [j (if (map? jurisdiction) jurisdiction (jurisdiction-by-id jurisdiction))]
    (if-not j
      false
      (set/subset? (set (:required-evidence j)) (set evidence)))))

(defn co2-volumes-in-tolerance?
  "Positive-sense convenience predicate: does `volumes` fall within
  `product`'s carbonation-level tolerance window (inclusive) around its
  declared target? Crossing the window risks either a flat, off-spec
  product or an over-pressurized container safety hazard, which this
  actor never decides on its own -- it only proposes logging the observed
  value."
  [volumes product]
  (boolean
   (and (some? product)
        (let [target (:co2-volumes-target product)
              tol (:co2-volumes-tolerance product)]
          (and (>= volumes (- target tol))
               (<= volumes (+ target tol)))))))

(defn brix-in-range?
  "Positive-sense convenience predicate: does `percent` fall within
  `product`'s Brix (sugar-content) window (inclusive) -- the window that
  defines the beverage's declared style (regular/diet/plain water)?"
  [percent product]
  (boolean
   (and (some? product)
        (>= percent (:brix-min-percent product))
        (<= percent (:brix-max-percent product)))))

(defn preservative-within-max?
  "Positive-sense convenience predicate: does `ppm` stay at or below
  `product`'s maximum allowable added-preservative residue?"
  [ppm product]
  (boolean
   (and (some? product)
        (<= ppm (:preservative-max-ppm product)))))

(defn microbial-load-within-max?
  "Positive-sense convenience predicate: does `cfu-per-ml` stay at or
  below `product`'s maximum allowable total-plate-count?"
  [cfu-per-ml product]
  (boolean
   (and (some? product)
        (<= cfu-per-ml (:microbial-load-max-cfu-per-ml product)))))

(defn fill-volume-in-range?
  "Positive-sense convenience predicate: does `ml` fall within `product`'s
  standard-of-fill window (target +/- tolerance, inclusive)?"
  [ml product]
  (boolean
   (and (some? product)
        (let [target (:fill-volume-target-ml product)
              tol (:fill-volume-tolerance-ml product)]
          (and (>= ml (- target tol))
               (<= ml (+ target tol)))))))

(defn mineral-content-meets-minimum?
  "Positive-sense convenience predicate: does `mg-per-l` meet or exceed
  `product`'s minimum required total-dissolved-solids mineral content for
  a legal \"mineral water\" label claim?"
  [mg-per-l product]
  (boolean
   (and (some? product)
        (>= mg-per-l (:mineral-content-min-mg-per-l product)))))
