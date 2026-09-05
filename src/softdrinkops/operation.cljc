(ns softdrinkops.operation
  "OperationActor -- the pure-function driver for a single proposal.

  Contracts a proposal through Governor validation, and if passed, yields
  the audit facts (facts committed to the ledger).")

(defn run-operation
  "Drive a single proposal through Governor validation.
  Returns {:ok? bool :facts [..] :verdict ..}."
  [request context proposal store governor-fn]
  (let [verdict (governor-fn request context proposal store)]
    (if (:ok? verdict)
      {:ok? true
       :facts []}
      {:ok? false
       :facts [((:hold-fact-fn context) request context verdict)]
       :verdict verdict})))

;; ─────────────────────── Disposition audit facts ───────────────────────
;;
;; `run-operation` returns the Governor's own hold fact when a proposal is
;; refused, but yields no fact of its own when a proposal clears or when a
;; human signs off on an escalation -- the two remaining dispositions in
;; this actor's contract. The constructors below give those the same fixed
;; shape as `softdrinkops.governor/hold-fact` so that a driver (the
;; simulation, or the build-time operator console in
;; `softdrinkops.render-html`) records them rather than inventing a fact
;; shape of its own. Neither constructor makes a decision: each takes the
;; already-computed request/verdict and only names it.

(defn commit-fact
  "Audit fact for a proposal the Governor cleared outright (`:ok?` true):
  no hard violation, confidence at or above the floor, and an operation
  that is not on the always-escalate list."
  [request context proposal]
  {:t :committed
   :op (:op request)
   :actor (:actor-id context)
   :subject (:subject request)
   :disposition :auto-commit
   :confidence (:confidence proposal)})

(defn approval-fact
  "Audit fact for an escalated proposal a human operator signed off on.
  `verdict` is the Governor's own verdict for the proposal, so
  `:high-stakes?` is copied off the real verdict rather than re-derived.
  Only ever appropriate when `(not (:hard? verdict))` -- a hard violation
  is un-overridable and no approval may follow it."
  [request operator-id verdict]
  {:t :operator-approval
   :op (:op request)
   :actor operator-id
   :subject (:subject request)
   :disposition :approved-and-committed
   :high-stakes? (:high-stakes? verdict)})
