(ns softdrinkops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it previously had NO demo
  page and no generator at all. This namespace drives the REAL actor stack
  (`softdrinkops.operation/run-operation` -> `softdrinkops.governor/check`
  over a `softdrinkops.store` value) and renders whatever comes back.

  WHY THE SCENARIO IS AUTHORED HERE RATHER THAN TAKEN FROM `sim`. This
  repo's own driver `softdrinkops.sim` is still a stub -- `clojure -M:dev:run`
  prints \"not yet implemented\" and exercises nothing -- so there was no
  existing scenario to reuse. The seed batches were therefore authored, and
  they live in `softdrinkops.store/demo-batches` (NOT in this namespace) so
  that every subject id fed to the actor is a batch that literally exists
  in the store and is greppable from one place.

  WHAT IS REAL AND WHAT IS SCRIPTED, precisely:

    * Every verdict on the page is the Governor's, computed at run time.
      This namespace never re-implements a rule, never hard-codes a rule
      name against a batch, and never decides `ok?`/`hard?`/`escalate?`
      itself -- it calls `operation/run-operation` and reads the returned
      `:verdict` and `:facts`.
    * Every audit fact on the page has a domain-layer shape:
      `governor/hold-fact` for refusals, `operation/commit-fact` and
      `operation/approval-fact` for the other two dispositions.
    * The parameter table's pass/fail colouring calls the same
      `softdrinkops.registry` / `softdrinkops.facts` predicates the
      Governor calls; the limits beside each measured value are read from
      `facts/product-types`, `governor/fill-volume-variance-max-ml`,
      `governor/sanitation-score-min` and
      `registry/calibration-window-days`. No threshold is typed in here.
    * The human sign-off IS scripted -- this repo has no approval
      machinery (no langgraph state graph, no resume). The Governor decides
      that a human is required (`:escalate?`); `run-step` then applies the
      demo operator's approval and the resulting store transition. An
      approval is structurally impossible on a hard hold: `run-step` only
      consults `:approve-with` when the real verdict says `(not :hard?)`.
    * The `action-gate-rows` table is a static description of this actor's
      own fixed op contract (documentation of code, not telemetry) and is
      the only hand-written content on the page.

  DETERMINISM. No timestamp, no random value and no wall-clock reading
  reaches the page. The single clock read (`System/currentTimeMillis`) is
  passed to `store/seed-db`, which expresses calibration dates as offsets
  from it, so both the Governor's 90-day verdicts and the rendered \"N days
  since calibration\" figures are the same constants on every run. Two
  consecutive runs are byte-identical; verify by diffing them.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [softdrinkops.store :as store]
            [softdrinkops.facts :as facts]
            [softdrinkops.registry :as registry]
            [softdrinkops.governor :as governor]
            [softdrinkops.operation :as operation]))

(def ^:private actor-context
  "The `context` map `operation/run-operation` expects: an actor id and the
  Governor's own hold-fact constructor. Passing `governor/hold-fact` here
  is what keeps the refusal facts on the page the Governor's own output."
  {:actor-id "softdrinkops-1" :hold-fact-fn governor/hold-fact})

(def ^:private operator-id "op-1")

(defn- cites-for
  "A citation naming the jurisdiction the proposal is made under, taken
  from `facts/jurisdictions` rather than typed out here. The Governor only
  checks that a citation is PRESENT (`no-spec-basis`); it does not verify
  the text."
  [jurisdiction-id]
  [{:spec (:name (facts/jurisdiction-by-id jurisdiction-id))}])

(defn- proposal-for
  "A well-formed advisor proposal for `batch-id`, at `confidence`."
  [db batch-id confidence]
  (let [j (:jurisdiction (store/production-batch db batch-id))]
    {:cites (cites-for j)
     :value {:jurisdiction j}
     :effect :propose
     :confidence confidence}))

(defn- run-step
  "Drive ONE proposal through the real actor and fold the result into `db`.

  Appends the Governor's own facts verbatim, then records the disposition:
    * `:ok?`   -> an `operation/commit-fact` (the Governor cleared it)
    * hard     -> nothing further; the Governor's `:governor-hold` fact
                  already carries `:basis` (the rule names) and
                  `:violations` (the rule details)
    * escalate -> if, and only if, the caller supplied `:approve-with`
                  (the store transition the sign-off authorises), the demo
                  operator approves: the transition is applied and an
                  `operation/approval-fact` is appended. With no
                  `:approve-with` the Governor's soft-gate hold stands as
                  the terminal record and the proposal is left sitting in
                  the operator's queue -- which is what an open food-safety
                  concern should look like.

  `:approve-with` is only ever consulted when the REAL verdict says the
  hold is not hard, so no scripted approval can bypass a hard rule."
  [db request proposal & {:keys [approve-with]}]
  (let [{:keys [ok? facts verdict]}
        (operation/run-operation request actor-context proposal db governor/check)

        db' (reduce store/append-fact db facts)]
    (cond
      ok?
      (store/append-fact db' (operation/commit-fact request actor-context proposal))

      (:hard? verdict) db'

      approve-with
      (-> (approve-with db')
          (store/append-fact (operation/approval-fact request operator-id verdict)))

      ;; escalated and NOT approved -- stays in the operator's queue
      :else db')))

(defn- log-batch-with
  "Store transition for an approved `:log-production-batch`: promote the
  already-registered batch to processed, preserving its measured fields."
  [batch-id]
  (fn [db] (store/log-batch db batch-id (store/production-batch db batch-id))))

(defn run-demo!
  "Run a scenario over a freshly seeded store and return the resulting
  store. Every subject named below is a key of `store/demo-batches`.

  Per-batch intent -- SETUP only; every disposition is the Governor's:

    batch-jp-2401  clean carbonated soft drink. Full clean lifecycle:
                   maintenance auto-commits, batch logging and shipment
                   each escalate and are approved, then both double-commit
                   guards fire on the repeat attempts.
    batch-us-2402  clean natural mineral water: logged with sign-off, not
                   yet shipped. Also used for the authority boundary --
                   `:actuate-filling-line` is not this actor's to propose.
    batch-us-2403  plate count over the bottled-water ceiling.
    batch-eu-2404  carbonation over the product's tolerance band.
    batch-jp-2405  TDS under the \"mineral water\" label floor.
    batch-us-2406  contamination screen positive AND an unresolved
                   food-safety flag; also raises the concern once with no
                   citation, and once properly (left unapproved on purpose,
                   so the page ends with an item still in the queue).
    batch-eu-2407  evidence checklist short of the jurisdiction's list.
    batch-jp-2408  fill-volume metering calibration overdue; also used for
                   the `:effect :propose` invariant.
    batch-us-2409  preservative under the ceiling but undeclared.
    batch-eu-2410  CIP hygiene score under the floor; also used for the
                   low-confidence soft gate.
    batch-jp-2411  Brix outside the declared style's window.
    batch-us-2412  preservative residue over the product ceiling.
    batch-eu-2413  standard-of-fill drift over tolerance."
  [now]
  (let [db (store/seed-db now)
        ;; A proposal at the given confidence for a batch, resolved against
        ;; the CURRENT db so the jurisdiction citation is the batch's own.
        p (fn [db* id conf] (proposal-for db* id conf))]
    (as-> db $
      ;; ---- batch-jp-2401: clean carbonated soft drink, full lifecycle ----
      ;; maintenance scheduling is neither high-stakes nor food-safety, so a
      ;; clean, confident proposal commits with no human in the loop.
      (run-step $ {:op :schedule-maintenance :subject "batch-jp-2401"}
                (p $ "batch-jp-2401" 0.91))
      ;; logging a batch is one of the two real actuation events -> always human
      (run-step $ {:op :log-production-batch :subject "batch-jp-2401"}
                (p $ "batch-jp-2401" 0.93)
                :approve-with (log-batch-with "batch-jp-2401"))
      ;; shipping finished product is the other -> always human
      (run-step $ {:op :coordinate-shipment :subject "batch-jp-2401"}
                (p $ "batch-jp-2401" 0.90)
                :approve-with #(store/finalize-shipment % "batch-jp-2401"))
      ;; double-commit guards, now that the flags are actually set
      (run-step $ {:op :log-production-batch :subject "batch-jp-2401"}
                (p $ "batch-jp-2401" 0.93))
      (run-step $ {:op :coordinate-shipment :subject "batch-jp-2401"}
                (p $ "batch-jp-2401" 0.90))

      ;; ---- batch-us-2402: clean mineral water, logged, not yet shipped ----
      (run-step $ {:op :log-production-batch :subject "batch-us-2402"}
                (p $ "batch-us-2402" 0.88)
                :approve-with (log-batch-with "batch-us-2402"))
      ;; authority boundary: driving the filling line is not a proposal this
      ;; actor may make at all, at any confidence.
      (run-step $ {:op :actuate-filling-line :subject "batch-us-2402"}
                (p $ "batch-us-2402" 0.99))

      ;; ---- one hard batch-parameter rule per batch ----
      (run-step $ {:op :log-production-batch :subject "batch-us-2403"}
                (p $ "batch-us-2403" 0.92))
      (run-step $ {:op :log-production-batch :subject "batch-eu-2404"}
                (p $ "batch-eu-2404" 0.92))
      (run-step $ {:op :log-production-batch :subject "batch-jp-2405"}
                (p $ "batch-jp-2405" 0.92))

      ;; ---- batch-us-2406: contamination + an open food-safety flag ----
      (run-step $ {:op :log-production-batch :subject "batch-us-2406"}
                (p $ "batch-us-2406" 0.92))
      ;; a food-safety concern raised with no citation at all
      (run-step $ {:op :flag-food-safety-concern :subject "batch-us-2406"}
                (assoc (p $ "batch-us-2406" 0.95) :cites []))
      ;; ... and the same concern raised properly: never auto-resolved by
      ;; confidence, so it waits for a human. Left UNAPPROVED on purpose --
      ;; an open food-safety concern is exactly what should still be sitting
      ;; in an operator's queue at the bottom of this page.
      (run-step $ {:op :flag-food-safety-concern :subject "batch-us-2406"}
                (p $ "batch-us-2406" 0.95))

      (run-step $ {:op :log-production-batch :subject "batch-eu-2407"}
                (p $ "batch-eu-2407" 0.92))
      (run-step $ {:op :log-production-batch :subject "batch-jp-2408"}
                (p $ "batch-jp-2408" 0.92))
      ;; the effect invariant: the actor proposes, it never claims a commit
      (run-step $ {:op :schedule-maintenance :subject "batch-jp-2408"}
                (assoc (p $ "batch-jp-2408" 0.97) :effect :commit))

      (run-step $ {:op :log-production-batch :subject "batch-us-2409"}
                (p $ "batch-us-2409" 0.92))
      (run-step $ {:op :log-production-batch :subject "batch-eu-2410"}
                (p $ "batch-eu-2410" 0.92))
      ;; low confidence on an otherwise unremarkable op -> soft gate
      (run-step $ {:op :schedule-maintenance :subject "batch-eu-2410"}
                (p $ "batch-eu-2410" 0.42))

      (run-step $ {:op :log-production-batch :subject "batch-jp-2411"}
                (p $ "batch-jp-2411" 0.92))
      (run-step $ {:op :log-production-batch :subject "batch-us-2412"}
                (p $ "batch-us-2412" 0.92))
      (run-step $ {:op :log-production-batch :subject "batch-eu-2413"}
                (p $ "batch-eu-2413" 0.92)))))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- hard-hold?
  "A `:governor-hold` fact carries `:basis` only for hard violations:
  `governor/check` returns `:violations` for hard rules alone, and
  `:escalate?` is defined as `(and (not hard?) ...)`. So a hold with an
  empty basis is precisely the soft gate, not a rule breach."
  [fact]
  (and (= :governor-hold (:t fact)) (seq (:basis fact))))

(defn- rules-of [fact] (map name (:basis fact)))

(defn- disposition-cell [fact]
  (cond
    (nil? fact) "<span class=\"muted\">no activity</span>"
    (= :committed (:t fact)) "<span class=\"ok\">auto-committed</span>"
    (= :operator-approval (:t fact)) "<span class=\"ok\">approved &amp; committed</span>"
    (hard-hold? fact) (str "<span class=\"critical\">HARD hold &middot; "
                           (esc (str/join ", " (rules-of fact))) "</span>")
    (= :governor-hold (:t fact)) "<span class=\"warn\">escalated &middot; awaiting operator</span>"
    :else "<span class=\"muted\">in progress</span>"))

(defn- lifecycle-cell [{:keys [processed? shipment-finalized?]}]
  (cond
    shipment-finalized? "<span class=\"ok\">logged &amp; shipment finalized</span>"
    processed? "<span class=\"warn\">logged, shipment not finalized</span>"
    :else "<span class=\"muted\">not logged</span>"))

(defn- last-fact-for [ledger batch-id]
  (last (filter #(= (:subject %) batch-id) ledger)))

(defn- batch-row [ledger [id b]]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id)
          (esc (:name (facts/product-type-by-id (:product-type b))))
          (esc (:name (facts/jurisdiction-by-id (:jurisdiction b))))
          (lifecycle-cell b)
          (disposition-cell (last-fact-for ledger id))))

(def ^:private day-ms (* 24 60 60 1000))

(defn- ok-warn
  "Wrap `actual` in the state class `bad?` implies. `bad?` is always the
  Governor's OWN predicate (`softdrinkops.registry` / `softdrinkops.facts`)
  applied to the same two arguments the Governor passes it, called here
  only for COLOURING a cell -- the hold/commit decision on this page comes
  from the Governor's verdict, never from this function."
  [bad? actual]
  (format "<span class=\"%s\">%s</span>" (if bad? "critical" "ok") (esc actual)))

(defn- measured
  "One `actual / limit` cell: the value read off the batch, coloured by the
  Governor's own predicate, beside the limit it was checked against."
  [bad? actual limit-label]
  (format "%s <span class=\"muted\">/ %s</span>"
          (ok-warn bad? actual) (esc limit-label)))

(defn- param-row
  "One batch's measured parameters beside the limit each is checked
  against. Actuals are read off the batch record; per-product limits come
  from `facts/product-types`; the three cross-product limits come from
  `governor/fill-volume-variance-max-ml`, `governor/sanitation-score-min`
  and `registry/calibration-window-days`. Every pass/fail call below is the
  Governor's own predicate, not a comparison written here."
  [now [id b]]
  (let [p (facts/product-type-by-id (:product-type b))
        cal-days (quot (- now (:filling-line-last-calibration-date b)) day-ms)
        j (facts/jurisdiction-by-id (:jurisdiction b))
        evidence-n (count (:evidence-checklist b))
        required-n (count (:required-evidence j))]
    ;; 12 cells, matching the 12 column headers in `render` -- `format`
    ;; silently DROPS surplus arguments, so a short format string here
    ;; would lose a column without erroring.
    (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td>"
                 "<td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td>"
                 "<td>%s</td><td>%s</td></tr>")
            (esc id)
            ;; CO2 volumes vs the product's target ± tolerance
            (measured (registry/carbonation-out-of-tolerance?
                       (:co2-volumes b) (:co2-volumes-target p) (:co2-volumes-tolerance p))
                      (:co2-volumes b)
                      (str (:co2-volumes-target p) "±" (:co2-volumes-tolerance p)))
            ;; Brix vs the declared style's window
            (measured (registry/brix-out-of-range?
                       (:brix-percent b) (:brix-min-percent p) (:brix-max-percent p))
                      (:brix-percent b)
                      (str (:brix-min-percent p) "–" (:brix-max-percent p)))
            ;; preservative residue vs the product ceiling
            (measured (registry/preservative-exceeds-max?
                       (:preservative-ppm b) (:preservative-max-ppm p))
                      (:preservative-ppm b)
                      (str "≤" (:preservative-max-ppm p)))
            ;; declared additives, coloured by the label-mismatch rule
            ;; (sorted -- :declared-additives is a set)
            (ok-warn (registry/additive-label-mismatch?
                      (:preservative-ppm b)
                      (:preservative-declaration-threshold-ppm j)
                      (:declared-additives b))
                     (if (seq (:declared-additives b))
                       (str/join ", " (sort (map name (:declared-additives b))))
                       "none declared"))
            ;; plate count vs the product ceiling
            (measured (registry/microbial-load-exceeds-max?
                       (:microbial-load-cfu-per-ml b) (:microbial-load-max-cfu-per-ml p))
                      (:microbial-load-cfu-per-ml b)
                      (str "≤" (:microbial-load-max-cfu-per-ml p)))
            ;; TDS vs the mineral-water label floor
            (measured (registry/mineral-content-below-minimum?
                       (:mineral-content-mg-per-l b) (:mineral-content-min-mg-per-l p))
                      (:mineral-content-mg-per-l b)
                      (str "≥" (:mineral-content-min-mg-per-l p)))
            ;; standard-of-fill drift vs the Governor's tolerance
            (measured (registry/fill-volume-variance-excessive?
                       (:fill-volume-variance-ml b) governor/fill-volume-variance-max-ml)
                      (:fill-volume-variance-ml b)
                      (str "≤" governor/fill-volume-variance-max-ml))
            ;; CIP hygiene vs the Governor's floor
            (measured (registry/sanitation-score-insufficient?
                       (:sanitation-score b) governor/sanitation-score-min)
                      (:sanitation-score b)
                      (str "≥" governor/sanitation-score-min))
            ;; days since calibration vs the registry's window
            (measured (registry/filling-line-calibration-overdue?
                       (:filling-line-last-calibration-date b) now)
                      (str cal-days "d")
                      (str "≤" registry/calibration-window-days "d"))
            ;; evidence items present vs the jurisdiction's required list
            (ok-warn (not (facts/required-evidence-satisfied?
                           (:jurisdiction b) (:evidence-checklist b)))
                     (format "%s/%s" evidence-n required-n))
            ;; contamination screen, and any open food-safety flag
            (str (ok-warn (registry/contamination-detected? (:contamination-detected? b))
                          (if (:contamination-detected? b) "contamination" "clean"))
                 (when (and (:safety-concern-raised? b)
                            (not (:safety-concern-resolved? b)))
                   " <span class=\"critical\">· flag open</span>")))))

(defn- ledger-row [{:keys [t op subject disposition high-stakes?] :as f}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
          (esc (name t))
          (esc (name op))
          (esc subject)
          (cond
            (hard-hold? f)
            (str "<span class=\"critical\">HARD hold &middot; "
                 (esc (str/join ", " (rules-of f))) "</span>")
            (= :governor-hold t)
            "<span class=\"warn\">soft gate &middot; human required</span>"
            :else
            (str "<span class=\"ok\">" (esc (name (or disposition :n-a))) "</span>"
                 (when high-stakes?
                   " <span class=\"muted\">(high-stakes actuation)</span>")))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract, read off
  ;; `softdrinkops.governor` (`allowed-ops`, `high-stakes`,
  ;; `always-escalate-ops`, `confidence-floor`). This is DOCUMENTATION OF
  ;; FIXED BEHAVIOUR, not runtime telemetry -- unlike every other table on
  ;; this page, no part of it is derived from the run above.
  ["        <tr><td><code>:log-production-batch</code></td><td><span class=\"warn\">ALWAYS human sign-off &middot; never auto at any confidence &middot; every batch-parameter rule recomputed independently</span></td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"warn\">ALWAYS human sign-off &middot; never auto at any confidence</span></td></tr>"
   "        <tr><td><code>:flag-food-safety-concern</code></td><td><span class=\"warn\">ALWAYS human sign-off &middot; a food-safety concern is never resolved by advisor confidence alone</span></td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"ok\">auto-commits when the Governor is clean and confidence is at or above the floor</span></td></tr>"
   "        <tr><td><em>anything else</em></td><td><span class=\"critical\">HARD block &middot; outside the closed allowlist &middot; mixing-tank / carbonator / filling-line control and food-safety certification are not this actor's to propose</span></td></tr>"])

(def ^:private unreached-rule
  ;; Honest gap statement. `batch-not-registered` is the one hard rule this
  ;; console does not exercise, because reaching it requires feeding the
  ;; actor a batch id that does not exist -- and every id on this page is a
  ;; real key of `store/demo-batches`. It is covered by
  ;; `run-operation-shipment-batch-not-registered-test` instead.
  "batch-not-registered")

(defn render
  "Render the operator console from a store `db` that has already been
  through `run-demo!`. `now` is the same clock value the seed was built
  with; only fixed offsets from it reach the page."
  [db now]
  (let [ledger (vec (store/audit-trail db))
        batches (store/all-batches db)
        reached (->> ledger (filter hard-hold?) (mapcat :basis) (map name) distinct sort)]
    (str
     "<html><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<title>cloud-itonami-isic-1104 &middot; soft-drink &amp; bottled-water manufacturing</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Soft drink &amp; bottled water manufacturing (ISIC 1104) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · batch logging and shipment always human-approved</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Production batches</h2>\n"
     "    <p class=\"muted\">Build-time snapshot — generated from <code>softdrinkops.store</code> by <code>softdrinkops.render-html</code> (<code>clojure -M:dev:render-html</code>), driving the real <code>softdrinkops.operation/run-operation</code> against <code>softdrinkops.governor/check</code>. Every disposition below is the Governor's own verdict.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Product type</th><th>Jurisdiction</th><th>Lifecycle</th><th>Last disposition</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial batch-row ledger) batches)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Independently recomputed parameters</h2>\n"
     "    <p class=\"muted\">Measured value / limit. Actuals are read off the batch record; per-product limits come from <code>softdrinkops.facts/product-types</code>. The Governor recomputes every one of these itself — the advisor's confidence never substitutes for them. Colour is the Governor's own predicate; the hold decision is its verdict.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>CO₂ vol</th><th>Brix %</th><th>Preservative ppm</th><th>Declared additives</th><th>Plate count CFU/mL</th><th>TDS mg/L</th><th>Fill drift mL</th><th>CIP score</th><th>Since calibration</th><th>Evidence</th><th>Screens</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial param-row now) batches)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (SoftDrinkOps Governor)</h2>\n"
     "    <p class=\"muted\">Fixed contract, not telemetry — described from <code>softdrinkops.governor</code>'s <code>allowed-ops</code>, <code>always-escalate-ops</code> and <code>confidence-floor</code>. Hard violations cannot be overridden by any approval; the two actuation events (batch logging, shipment) and any food-safety flag always require a human.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Hard rules reached in this run</h2>\n"
     "    <p class=\"muted\">Rule names taken from the <code>:basis</code> of the Governor's own hold facts below — nothing here is typed by hand.</p>\n"
     "    <p>" (str/join " · " (map #(str "<code>" (esc %) "</code>") reached)) "</p>\n"
     "    <p class=\"muted\">Not exercised here: <code>" (esc unreached-rule) "</code> — reaching it needs a batch id that does not exist, and every id on this page is a real key of <code>store/demo-batches</code>. It is covered in <code>test/softdrinkops/operation_test.cljc</code> instead.</p>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log. <code>governor-hold</code> facts are the Governor's verbatim output; a hold carrying rule names is a hard, un-overridable block, a hold with none is the soft gate that routes the proposal to a human.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Batch</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "<footer>\n"
     "  <p>Open occupation blueprint — no invented usage or revenue metrics. This actor coordinates plant operations; it does not operate mixing tanks, carbonators or filling lines, and it holds no food-safety-certification authority.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        ;; The single clock read in the whole generator: the seed's
        ;; calibration offsets and the rendered "N days since calibration"
        ;; figures are both measured against this one value, so both are
        ;; constants on every run.
        now (System/currentTimeMillis)
        db (run-demo! now)
        ledger (store/audit-trail db)
        html (render db now)]
    (spit out html)
    (println "wrote" out
             "(" (count ledger) "ledger facts,"
             (count (filter hard-hold? ledger)) "hard holds )")))
