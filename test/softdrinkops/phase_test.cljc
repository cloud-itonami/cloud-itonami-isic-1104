(ns softdrinkops.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [softdrinkops.phase :as phase]))

;; ──────────────────────── Phase Validity ──────────────────────

(deftest valid-phase-test
  (testing "intake is valid"
    (is (true? (phase/valid-phase? :intake))))

  (testing "carbonation is valid"
    (is (true? (phase/valid-phase? :carbonation))))

  (testing "archived is valid"
    (is (true? (phase/valid-phase? :archived))))

  (testing "invalid phase returns false"
    (is (false? (phase/valid-phase? :invalid)))))

;; ──────────────────────── Phase Transitions ──────────────────────

(deftest can-transition-test
  (testing "intake -> mixing is valid (forward progression)"
    (is (true? (phase/can-transition? :intake :mixing))))

  (testing "intake -> carbonation is valid (skip mixing)"
    (is (true? (phase/can-transition? :intake :carbonation))))

  (testing "mixing -> intake is invalid (backward)"
    (is (false? (phase/can-transition? :mixing :intake))))

  (testing "carbonation -> archived is valid (forward to end)"
    (is (true? (phase/can-transition? :carbonation :archived))))

  (testing "archived -> intake is invalid (backward from end)"
    (is (false? (phase/can-transition? :archived :intake))))

  (testing "same phase is invalid"
    (is (false? (phase/can-transition? :carbonation :carbonation))))

  (testing "invalid phases return false"
    (is (false? (phase/can-transition? :invalid :carbonation)))
    (is (false? (phase/can-transition? :carbonation :invalid)))))
