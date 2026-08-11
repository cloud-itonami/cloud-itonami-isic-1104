(ns softdrinkops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Drives the REAL actor stack of this repo -- `softdrinkops.store` (seed +
  append-only ledger) -> `softdrinkops.operation/run-operation` ->
  `softdrinkops.governor/check` + `softdrinkops.governor/hold-fact` -- over a
  fixed scenario, then renders whatever that run actually produced. There is
  no mock governor, no hand-written verdict and no hand-typed hold text: every
  rule name and every Japanese detail string on the page comes out of
  `softdrinkops.governor`'s own violation maps, and every batch number comes
  out of `softdrinkops.store/seed-db`.

  Three things about THIS repo shape the renderer, and are worth stating so
  the page is not read as claiming more than it does:

  1. This repo has no langgraph StateGraph. `softdrinkops.advisor` is still a
     skeleton and `softdrinkops.sim` prints \"not yet implemented\". The real
     driver surface available today is `operation/run-operation`, which is
     exactly what this namespace calls -- the same call shape the repo's own
     `test/softdrinkops/operation_test.cljc` uses.
  2. `operation/run-operation` returns `{:ok? true :facts []}` on a clean
     verdict. It emits NO commit fact. The audit ledger can therefore only
     ever contain `:governor-hold` facts, and the status column below has no
     `:committed` / `:approval-granted` branch -- such a branch would be
     unreachable. The page says so rather than implying a richer ledger.
  3. Human sign-off is not modelled by this repo either. Where the scenario
     needs a signed-off commit (to reach the double-commit guards), it calls
     `store/log-batch` / `store/finalize-shipment` -- the store functions whose
     own docstrings say they are \"used once a proposal commits\" -- and the
     page labels those two rows as operator actions taken OUTSIDE the actor,
     not as actor output.

  Determinism: nothing time-varying reaches the page. Calibration dates exist
  in the seed (the Governor measures them against the current clock) but are
  never rendered, so two consecutive runs are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [softdrinkops.facts :as facts]
            [softdrinkops.governor :as governor]
            [softdrinkops.operation :as operation]
            [softdrinkops.store :as store]))

;; ───────────────────────────── driving the real actor ─────────────────────────

(def ^:private operator-context
  "The `context` map `operation/run-operation` expects: an actor id and the
  hold-fact constructor it calls when the Governor refuses. Passing
  `governor/hold-fact` here is what makes every hold row on the page real
  Governor output."
  {:actor-id "op-1"
   :actor-role :plant-operations-coordinator
   :hold-fact-fn governor/hold-fact})

(defn- propose!
  "Drive ONE proposal through the real OperationActor and append whatever
  facts it emits to the store's own append-only ledger via
  `store/append-fact`. Returns `[store' step]`."
  [st label request proposal]
  (let [result (operation/run-operation request operator-context proposal st governor/check)
        st' (reduce store/append-fact st (:facts result))]
    [st' {:kind :proposal
          :label label
          :request request
          :proposal proposal
          :result result}]))

(defn- commit-batch-log
  "Apply a signed-off `:log-production-batch` to the plant record. `log-batch`
  takes the batch data because it is also the registration entry point; here
  the batch already exists, so its own current record is passed straight
  back -- `log-batch` adds the one-way `:processed?` flag."
  [st batch-id]
  (store/log-batch st batch-id (store/production-batch st batch-id)))

(defn- sign-off!
  "Record a human operator sign-off. This is NOT actor output -- the Governor
  escalated, a person said yes, and the plant record moved. `apply-fn` is the
  `softdrinkops.store` state change that sign-off authorises."
  [st label batch-id applied apply-fn]
  [(apply-fn st batch-id)
   {:kind :sign-off :label label :subject batch-id :applied applied}])

(defn- cites
  "A citation vector in the shape `governor/spec-basis-violations` checks for."
  [spec]
  [{:spec spec}])

(defn run-demo!
  "Runs the seeded plant records through a scenario that reaches every
  disposition this actor can produce, and 16 of the Governor's 17 hard rules.

  The one hard rule deliberately NOT exercised is `:batch-not-registered`:
  reaching it requires driving a subject id that is absent from the seed, and
  this console only ever drives batch ids that `store/seed-db` actually
  registered. The rule is listed in the gate table below so the omission is
  visible rather than silent.

  Returns `{:store st :steps [...]}` where `st` is the store as the run left
  it (ledger included) and `steps` is the ordered transcript."
  []
  (let [seed (store/seed-db (System/currentTimeMillis))
        b1 "batch-1104-01"
        b8 "batch-1104-08"]
    (loop [st seed
           steps []
           todo
           [;; A. the only auto-commit path this actor has: a clean, routine
            ;; maintenance proposal, well above the confidence floor.
            [:propose "clean maintenance proposal"
             {:op :schedule-maintenance :subject b8}
             {:cites (cites "Filling-Line-Maintenance-Manual")
              :value {:jurisdiction :us/fda}
              :effect :propose
              :confidence 0.92}]

            ;; ... and the same proposal below the confidence floor.
            [:propose "same proposal, low advisor confidence"
             {:op :schedule-maintenance :subject b8}
             {:cites (cites "Filling-Line-Maintenance-Manual")
              :value {:jurisdiction :us/fda}
              :effect :propose
              :confidence 0.41}]

            ;; B. proposals that fail on their own shape, before any
            ;; production parameter is looked at.
            [:propose "batch log with no jurisdiction citation"
             {:op :log-production-batch :subject b8}
             {:cites []
              :value {:jurisdiction :us/fda}
              :effect :propose
              :confidence 0.88}]

            [:propose "direct filling-line actuation (outside the allowlist)"
             {:op :actuate-filling-line :subject b1}
             {:cites (cites "Filling-Line-Manual")
              :value {:jurisdiction :jp/mhlw}
              :effect :propose
              :confidence 0.99}]

            [:propose "plant food-safety certification (outside the allowlist)"
             {:op :certify-food-safety :subject b1}
             {:cites (cites "HACCP-Plan")
              :value {:jurisdiction :jp/mhlw}
              :effect :propose
              :confidence 0.99}]

            [:propose "maintenance proposal claiming direct write authority"
             {:op :schedule-maintenance :subject b1}
             {:cites (cites "Filling-Line-Maintenance-Manual")
              :value {:jurisdiction :jp/mhlw}
              :effect :commit
              :confidence 0.93}]

            ;; C. a full, clean actuation arc on batch-1104-01, including both
            ;; double-commit guards.
            [:propose "log a clean batch (actuation)"
             {:op :log-production-batch :subject b1}
             {:cites (cites "食品衛生法 清涼飲料水の規格基準")
              :value {:jurisdiction :jp/mhlw}
              :effect :propose
              :confidence 0.94}]
            [:sign-off "operator signs off the batch log" b1
             "store/log-batch" commit-batch-log]
            [:propose "the same batch log, proposed again"
             {:op :log-production-batch :subject b1}
             {:cites (cites "食品衛生法 清涼飲料水の規格基準")
              :value {:jurisdiction :jp/mhlw}
              :effect :propose
              :confidence 0.94}]

            [:propose "coordinate the outbound shipment (actuation)"
             {:op :coordinate-shipment :subject b1}
             {:cites (cites "食品衛生法 清涼飲料水の規格基準")
              :value {:jurisdiction :jp/mhlw}
              :effect :propose
              :confidence 0.90}]
            [:sign-off "operator signs off the shipment" b1
             "store/finalize-shipment" store/finalize-shipment]
            [:propose "the same shipment, coordinated again"
             {:op :coordinate-shipment :subject b1}
             {:cites (cites "食品衛生法 清涼飲料水の規格基準")
              :value {:jurisdiction :jp/mhlw}
              :effect :propose
              :confidence 0.90}]

            ;; D. hard holds the Governor derives from the batches' own
            ;; production parameters.
            [:propose "log a mineral water under the TDS floor"
             {:op :log-production-batch :subject "batch-1104-02"}
             {:cites (cites "Directive 2009/54/EC")
              :value {:jurisdiction :eu/dg-sante}
              :effect :propose
              :confidence 0.91}]

            [:propose "log a bottled water over the microbial action level"
             {:op :log-production-batch :subject "batch-1104-03"}
             {:cites (cites "21 CFR 165.110")
              :value {:jurisdiction :us/fda}
              :effect :propose
              :confidence 0.89}]

            [:propose "raise the food-safety concern on that water"
             {:op :flag-food-safety-concern :subject "batch-1104-03"}
             {:cites (cites "21 CFR 165.110")
              :value {:jurisdiction :us/fda}
              :effect :propose
              :confidence 0.97}]

            [:propose "log a soft drink off both carbonation and Brix"
             {:op :log-production-batch :subject "batch-1104-04"}
             {:cites (cites "21 CFR 165.110")
              :value {:jurisdiction :us/fda}
              :effect :propose
              :confidence 0.86}]

            [:propose "log a still drink with thin evidence and an undeclared preservative"
             {:op :log-production-batch :subject "batch-1104-05"}
             {:cites (cites "食品衛生法 清涼飲料水の規格基準")
              :value {:jurisdiction :jp/mhlw}
              :effect :propose
              :confidence 0.83}]

            [:propose "log a batch with detected contamination"
             {:op :log-production-batch :subject "batch-1104-06"}
             {:cites (cites "21 CFR 110")
              :value {:jurisdiction :us/fda}
              :effect :propose
              :confidence 0.90}]

            [:propose "log a mineral water carrying added preservative"
             {:op :log-production-batch :subject "batch-1104-07"}
             {:cites (cites "食品衛生法 清涼飲料水の規格基準")
              :value {:jurisdiction :jp/mhlw}
              :effect :propose
              :confidence 0.92}]]]
      (if-let [[kind & args] (first todo)]
        (let [[st' step] (if (= kind :propose)
                           (apply propose! st args)
                           (apply sign-off! st args))]
          (recur st' (conj steps step) (rest todo)))
        {:store st :steps steps}))))

;; ───────────────────────────────── rendering ──────────────────────────────────

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-list [ks]
  (if (seq ks) (str/join ", " (map name ks)) "—"))

(defn- disposition
  "The disposition the real Governor reached for one step. `run-operation`
  omits `:verdict` entirely when the verdict was clean, so `:ok?` is checked
  first."
  [{:keys [result]}]
  (let [v (:verdict result)]
    (cond
      (:ok? result) :commit
      (:hard? v) :hard-hold
      :else :escalate)))

(def ^:private disposition-cell
  {:commit "<span class=\"ok\">COMMIT — Governor clean, no sign-off required</span>"
   :escalate "<span class=\"warn\">ESCALATE — human sign-off required</span>"
   :hard-hold "<span class=\"critical\">HARD HOLD — un-overridable</span>"})

(defn- step-row [step]
  (if (= :sign-off (:kind step))
    (format (str "        <tr class=\"human\"><td>%s</td><td>—</td><td>%s</td>"
                 "<td colspan=\"2\"><span class=\"human-tag\">operator action, outside the actor</span>"
                 " plant record moved by <code>%s</code></td></tr>")
            (esc (:label step)) (esc (:subject step)) (esc (:applied step)))
    (let [{:keys [label request result]} step
          d (disposition step)
          rules (->> result :verdict :violations (map :rule))]
      (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
              (esc label)
              (esc (name (:op request)))
              (esc (:subject request))
              (disposition-cell d)
              (if (seq rules)
                (str "<code>" (esc (kw-list rules)) "</code>")
                (if (= :escalate d)
                  "<span class=\"muted\">no rule violated — escalation is the gate itself</span>"
                  "<span class=\"muted\">—</span>"))))))

(defn- last-fact-for [ledger batch-id]
  (last (filter #(= (:subject %) batch-id) ledger)))

(defn- status-cell
  "`:governor-hold` is the ONLY fact type this actor ever appends —
  `operation/run-operation` returns `:facts []` on a clean verdict — so there
  is deliberately no `:committed` / `:approval-granted` branch here. A hold
  with a non-empty `:basis` is a hard rule firing; a hold with an empty
  `:basis` is the escalation gate."
  [ledger batch-id]
  (let [f (last-fact-for ledger batch-id)]
    (cond
      (nil? f) "<span class=\"muted\">no proposal in this run</span>"
      (seq (:basis f)) (str "<span class=\"critical\">HARD hold · "
                            (esc (kw-list (:basis f))) "</span>")
      :else "<span class=\"warn\">escalated to operator</span>")))

(defn- plant-state-cell [{:keys [processed? shipment-finalized?]}]
  (cond
    shipment-finalized? "<span class=\"ok\">logged &amp; shipment finalized</span>"
    processed? "<span class=\"ok\">logged, shipment open</span>"
    :else "<span class=\"muted\">not yet logged</span>"))

(defn- batch-row [ledger [id b]]
  (let [p (facts/product-type-by-id (:product-type b))
        j (facts/jurisdiction-by-id (:jurisdiction b))]
    (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td>"
                 "<td class=\"num\">%s</td><td class=\"num\">%s</td><td class=\"num\">%s</td>"
                 "<td class=\"num\">%s</td><td class=\"num\">%s</td><td class=\"num\">%s</td>"
                 "<td>%s</td><td>%s</td></tr>")
            (esc id)
            (esc (:name p))
            (esc (:name j))
            (esc (:co2-volumes b))
            (esc (:brix-percent b))
            (esc (:preservative-ppm b))
            (esc (:microbial-load-cfu-per-ml b))
            (esc (:mineral-content-mg-per-l b))
            (esc (:fill-volume-variance-ml b))
            (esc (:sanitation-score b))
            (str (plant-state-cell b) "<br>" (status-cell ledger id)))))

(defn- gate-row
  "One row of the action gate, derived from the Governor's own vars rather
  than from prose: `governor/allowed-ops`, `governor/high-stakes`,
  `governor/always-escalate-ops` and `governor/confidence-floor`."
  [op]
  (format "        <tr><td><code>%s</code></td><td>%s</td></tr>"
          (esc (name op))
          (cond
            (contains? governor/high-stakes op)
            "<span class=\"warn\">ALWAYS human sign-off — real actuation event</span>"
            (contains? governor/always-escalate-ops op)
            "<span class=\"warn\">ALWAYS human sign-off — never auto-resolved by confidence</span>"
            :else
            (str "<span class=\"ok\">auto-commit when the Governor is clean and confidence &ge; "
                 (esc governor/confidence-floor) "</span>"))))

(defn- hold-detail-item
  "One HARD-hold entry. Both the rule name and the explanatory sentence are
  read straight out of the Governor's violation map — nothing here is written
  by hand."
  [{:keys [op subject violations]}]
  (str "      <li><code>" (esc (name op)) "</code> &middot; <strong>" (esc subject) "</strong>\n"
       "        <ul>\n"
       (str/join "\n"
                 (for [{:keys [rule detail]} violations]
                   (str "          <li><code>" (esc (name rule)) "</code> — " (esc detail) "</li>")))
       "\n        </ul>\n      </li>"))

(defn- ledger-row [{:keys [t op subject basis confidence] disp :disposition}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td class=\"num\">%s</td></tr>"
          (esc (name t))
          (esc (name op))
          (esc subject)
          (esc (name disp))
          (if (seq basis) (str "<code>" (esc (kw-list basis)) "</code>")
              "<span class=\"muted\">escalation gate (no rule violated)</span>")
          (esc confidence)))

(def ^:private css
  (str "body{font:14px/1.6 system-ui,-apple-system,'Hiragino Sans','Noto Sans JP',sans-serif;"
       "margin:0;color:#1a1a1c;background:#f2f4f7}"
       ".bar{background:#0031d8;color:#fff;padding:1.1rem 2rem}"
       ".bar h1{margin:0;font-size:1.1rem;font-weight:600}"
       ".bar .badge{display:inline-block;margin-top:.4rem;font-size:.76rem;opacity:.9}"
       "main{max-width:1180px;margin:1.4rem auto;padding:0 1rem}"
       ".card{background:#fff;border-radius:8px;padding:1.1rem 1.3rem;margin-bottom:1.1rem;"
       "box-shadow:0 1px 3px rgba(0,0,0,.09)}"
       ".card h2{margin:0 0 .3rem;font-size:1rem}"
       ".muted{color:#6b6b70;font-size:.82rem}"
       "p.muted{margin:.2rem 0 .8rem}"
       "table{border-collapse:collapse;width:100%;font-size:.83rem}"
       "th,td{text-align:left;padding:.4rem .5rem;border-bottom:1px solid #ececf0;vertical-align:top}"
       "th{font-weight:600;color:#4a4a52;background:#fafafc}"
       "td.num{text-align:right;font-variant-numeric:tabular-nums}"
       ".ok{color:#00662a}.warn{color:#8a5b00}.critical{color:#b91414;font-weight:600}"
       "tr.human td{background:#fbfaf4}"
       ".human-tag{display:inline-block;background:#8a5b00;color:#fff;border-radius:3px;"
       "padding:.02rem .3rem;font-size:.72rem;margin-right:.35rem}"
       "code{background:#f0f0f4;padding:.08rem .28rem;border-radius:3px;"
       "font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.79rem}"
       "ul{margin:.2rem 0 .2rem 1.1rem;padding:0}li{margin:.15rem 0}"
       "footer{max-width:1180px;margin:0 auto 2rem;padding:0 1rem;color:#6b6b70;font-size:.78rem}"))

(defn render
  "Renders the console from the `{:store :steps}` map `run-demo!` returned."
  [{:keys [store steps]}]
  (let [ledger (vec (store/audit-trail store))
        batches (store/all-batches store)
        hard-holds (->> steps
                        (filter #(= :proposal (:kind %)))
                        (filter #(= :hard-hold (disposition %)))
                        (map #(-> % :result :facts first)))
        fired-rules (->> hard-holds (mapcat :basis) set)
        proposals (filter #(= :proposal (:kind %)) steps)]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\">\n"
     "<head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<meta name=\"color-scheme\" content=\"light\">"
     "<title>Operator console · cloud-itonami-isic-1104 · softdrinkops</title>"
     "<style>" css "</style></head>\n<body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Soft drink &amp; bottled water manufacturing coordination (ISIC 1104) — Operator Console</h1>\n"
     "  <div class=\"badge\">read-only sample · generated at build time by <code>softdrinkops.render-html</code> from the real Governor · not equipment control · not certification authority</div>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Production batches</h2>\n"
     "    <p class=\"muted\">Registered plant records from <code>softdrinkops.store/seed-db</code>. Every number below is a documented batch key — no presentation-only field. Filling-line calibration dates are in the seed and are read by the Governor, but are not shown here because they are measured against the current clock and would make this page non-reproducible.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Product</th><th>Jurisdiction</th><th>CO₂ vol</th><th>Brix %</th><th>Preserv. ppm</th><th>CFU/mL</th><th>TDS mg/L</th><th>Fill var. mL</th><th>CIP</th><th>Plant state / last Governor disposition</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial batch-row ledger) batches)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate</h2>\n"
     "    <p class=\"muted\">Derived from <code>softdrinkops.governor</code>'s own <code>allowed-ops</code>, <code>high-stakes</code>, <code>always-escalate-ops</code> and <code>confidence-floor</code> — not from prose. Anything outside this closed allowlist (direct mixing / carbonation / filling-line control, or a food-safety-certification decision) is refused unconditionally as <code>op-not-allowed</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Proposal op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map gate-row (sort-by name governor/allowed-ops))) "\n"
     "        <tr><td><code>anything else</code></td><td><span class=\"critical\">HARD HOLD · op-not-allowed — the actor has no authority to make the proposal at all</span></td></tr>\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>This run</h2>\n"
     "    <p class=\"muted\">"
     (count proposals) " proposals driven through <code>operation/run-operation</code>, "
     (count hard-holds) " of them HARD-held by the Governor on "
     (count fired-rules) " distinct rules. Rows shaded in amber are human operator actions taken outside the actor — the Governor escalated, a person signed off, and <code>store/log-batch</code> / <code>store/finalize-shipment</code> moved the plant record.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Step</th><th>Op</th><th>Batch</th><th>Disposition</th><th>Rules fired</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map step-row steps)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>HARD holds, in the Governor's own words</h2>\n"
     "    <p class=\"muted\">Rule names and explanations are read out of the <code>:violations</code> maps <code>softdrinkops.governor</code> produced during this run.</p>\n"
     "    <ul>\n"
     (str/join "\n" (map hold-detail-item hard-holds)) "\n"
     "    </ul>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">The append-only ledger as <code>store/append-fact</code> left it. Every row is a <code>:governor-hold</code>, because <code>operation/run-operation</code> returns <code>:facts []</code> on a clean verdict and emits no commit fact — so the two operator sign-offs above have no ledger row. That is a real gap in this actor, stated rather than papered over.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Batch</th><th>Disposition</th><th>Basis</th><th>Confidence</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "<footer>Regenerate with <code>clojure -M:dev:render-html</code>. Output is byte-identical across runs. Hard rules exercised by this scenario: "
     (esc (count fired-rules))
     "; <code>batch-not-registered</code> is not exercised, because reaching it requires driving a batch id the seed never registered.</footer>\n"
     "</body>\n</html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        run (run-demo!)
        html (render run)
        f (java.io.File. ^String out)]
    (some-> (.getParentFile f) .mkdirs)
    (spit f html)
    (println "wrote" out
             "(" (count (store/audit-trail (:store run))) "ledger facts,"
             (count (filter #(= :proposal (:kind %)) (:steps run))) "proposals,"
             (count (store/all-batches (:store run))) "batches )")))
