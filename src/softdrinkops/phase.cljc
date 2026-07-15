(ns softdrinkops.phase
  "Phase machine: the states a soft-drink/bottled-water production batch
  transits through.

  State machine:
    :intake -> :mixing -> :carbonation -> :filling -> :inspection ->
    :audit -> :archived

  `:intake` is water/ingredient receiving; `:mixing` is syrup/flavor/
  water blending; `:carbonation` is CO2 injection (skipped in effect for
  still products, but every batch passes through the state); `:filling`
  is finished-product filling (never directly controlled by this actor --
  mixing-tank, carbonator, and filling-line equipment operation remain
  exclusive to plant staff); `:inspection` is Brix/CO2-volumes/microbial/
  mineral-content/fill-volume inspection; `:audit` is compliance audit;
  `:archived` is the terminal state.

  Each transition can accept a proposal and yield an audit fact.")

(def all-phases
  "All valid phases in the soft-drink/bottled-water production workflow."
  [:intake :mixing :carbonation :filling :inspection :audit :archived])

(def phase-sequence
  "Ordered phases representing normal batch progression."
  [:intake :mixing :carbonation :filling :inspection :audit :archived])

(defn valid-phase?
  "Check if a phase is valid."
  [phase]
  (contains? (set all-phases) phase))

(defn- index-of
  "Portable (Clojure/ClojureScript) index lookup -- `.indexOf` is a
  JVM-only `java.util.List` method that ClojureScript's PersistentVector
  does not implement, so it is avoided here even though `phase-sequence`
  is a plain vector. Returns -1 when `x` is not found, matching
  `java.util.List/indexOf`'s contract."
  [coll x]
  (or (first (keep-indexed (fn [i v] (when (= v x) i)) coll)) -1))

(defn can-transition?
  "Check if a transition from one phase to another is valid
  (must be forward-only in the sequence, no backtracking). Always returns a
  boolean (never nil), including when either phase is invalid."
  [from-phase to-phase]
  (boolean
   (and (valid-phase? from-phase) (valid-phase? to-phase)
        (let [from-idx (index-of phase-sequence from-phase)
              to-idx (index-of phase-sequence to-phase)]
          (and (>= from-idx 0) (>= to-idx 0) (< from-idx to-idx))))))
