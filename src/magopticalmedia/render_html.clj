(ns magopticalmedia.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Drives the REAL MagOpticalMediaOperationActor (`magopticalmedia.operation/
  build` -> a compiled langgraph-clj StateGraph) over the REAL seeded store
  (`magopticalmedia.store/sample-data!`), through the REAL Magnetic and
  Optical Media Plant Operations Governor (`magopticalmedia.governor/check`)
  and the REAL rollout phase gate (`magopticalmedia.phase/gate`), and renders
  whatever those produced. Nothing on the page is written by hand:

    - every table row is read back out of the store after the run
      (`store/ledger`, `store/all-batches`, `store/all-equipment`,
      `store/all-maintenance`, `store/shipment`, `store/safety-concerns`,
      `store/maintenance-history`, `store/shipment-history`,
      `store/get-records`),
    - every HARD-hold rule name and every violation detail string is the
      governor's own `:violations` entry off the ledger fact -- never a
      literal in this namespace,
    - the phase gate table is derived from `magopticalmedia.phase/phases`,
      and the governor configuration / ground-truth bound tables from the
      public vars of `magopticalmedia.governor` and
      `magopticalmedia.registry`.

  Subject provenance (the demo may not invent subjects): every batch and
  equipment id driven below is either seeded by `store/sample-data!`
  (`batch-001` `batch-002` `batch-003` `coat-001` `stamp-002`) or created by
  an op inside this demo itself -- `batch-004` exists only because the `t01`
  `:log-production-batch` op committed it, and every `mnt-*` / `ship-*` /
  `concern-*` subject is the draft record its own op registers via
  `magopticalmedia.registry`. Product types, models, substrate thicknesses,
  quantities and defect rates are the seed's own values or values inside the
  registry's own declared bounds -- no invented vocabulary.

  Approver attribution is DERIVED, not asserted: `approver-attribution`
  re-checks the real store at render time for an approver key across every
  register, so the disclosure the page prints tracks the code instead of
  going stale the day the store starts persisting it.

  Deterministic: no clock, no randomness, no network. Re-running writes a
  byte-identical file.

  Run: `clojure -M:render-html [out-file]`
  (default out-file `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [magopticalmedia.governor :as governor]
            [magopticalmedia.operation :as op]
            [magopticalmedia.phase :as phase]
            [magopticalmedia.registry :as registry]
            [magopticalmedia.store :as store]))

;; ----------------------------- the run -----------------------------

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase phase/default-phase})

(def ^:private scenarios
  "One entry = one coordination request driven through the real actor.
  `:approval`, when present, is the human decision handed back to the
  paused graph (`interrupt-before #{:request-approval}`). Where an
  `:approval` is offered against a PERMANENTLY blocked proposal it is
  deliberately never consumed -- the page shows that the graph never
  paused, which is the point of a permanent block."
  [{:tid "t01"
    :exercises "Intake of a NEW production batch. Governor-clean, and :log-production-batch is the one op in phase 3's :auto set -> auto-commit. batch-004 exists for the rest of this page only because this op created it."
    :request {:op :log-production-batch :effect :propose :subject "batch-004"
              :patch {:product-type :blu-ray
                      :model "BD-R-25GB"
                      :substrate-thickness-mm 1.2
                      :quantity-units 4000.0
                      :defect-rate-percent 1.1
                      :last-assessed "2026-07-20"}}}

   {:tid "t02"
    :exercises "Maintenance window against the verified + registered coating line. Never auto-eligible at any phase -> escalates; the human plant supervisor approves."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "coat-001"
                      :maintenance-type :nozzle-inspection
                      :scheduled-date "2026-08-01"
                      :actuate-equipment? false}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t03"
    :exercises "Solvent-coating chemical-hazard concern. Always high-stakes, so the governor escalates regardless of confidence; the human approves."
    :request {:op :flag-safety-concern :effect :propose :subject "concern-1"
              :value {:equipment-id "coat-001" :severity :moderate
                      :description "コーティング工程の溶剤蒸気濃度上昇兆候、換気系統の点検要"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t04"
    :exercises "Shipment against a verified + registered CD batch with headroom. Escalates; the human shipping approver approves and batch-001's shipped-units advances."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-1"
              :value {:batch-id "batch-001" :units 500.0
                      :destination "buyer-warehouse-north"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t05"
    :exercises "Governor-clean shipment the human VETOES. Distinct from a HARD hold: the governor cleared it, a person did not."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-2"
              :value {:batch-id "batch-001" :units 100.0
                      :destination "buyer-warehouse-west"}}
    :approval {:status :rejected :by "coord-1"}}

   {:tid "t06"
    :exercises "Shipment against batch-004 -- the batch t01 just created, which carries no verified?/registered? ground truth of its own. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-3"
              :value {:batch-id "batch-004" :units 10.0
                      :destination "buyer-warehouse-north"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t07"
    :exercises "Shipment against the seeded UNVERIFIED / unregistered magnetic-tape batch. Same rule as t06 from the other direction: a seeded batch whose QC claims were never inspected. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-4"
              :value {:batch-id "batch-003" :units 100.0
                      :destination "buyer-warehouse-south"}}}

   {:tid "t08"
    :exercises "Shipment whose claimed units would push batch-002 past its own recorded production quantity. The governor recomputes from the batch's own fields, never the proposal's claim. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-5"
              :value {:batch-id "batch-002" :units 300.0
                      :destination "buyer-warehouse-east"}}}

   {:tid "t09"
    :exercises "Shipment stating NO unit quantity at all. Headroom cannot be recomputed, so it is not headroom -- the same rule fires on un-checkable input rather than falling through as 'not over'. HARD hold."
    :request {:op :coordinate-shipment :effect :propose :subject "ship-6"
              :value {:batch-id "batch-001"
                      :destination "buyer-warehouse-east"}}}

   {:tid "t10"
    :exercises "Maintenance against the seeded data-stamping line, which is neither inspected nor on file. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-2"
              :value {:equipment-id "stamp-002"
                      :maintenance-type :calibration
                      :scheduled-date "2026-08-05"
                      :actuate-equipment? false}}}

   {:tid "t11"
    :exercises "A maintenance proposal that tries to ACTUATE the coating line rather than draft a window. Permanent scope boundary -- an approval is offered here and is never consumed, because the graph never pauses. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-3"
              :value {:equipment-id "coat-001" :maintenance-type :force-run
                      :scheduled-date "2026-09-01"
                      :actuate-equipment? true}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t12"
    :exercises "The SAME maintenance window as t02, scheduled twice. Guarded off a dedicated :scheduled? fact, never a :status value. HARD hold."
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "coat-001"
                      :maintenance-type :nozzle-inspection
                      :scheduled-date "2026-08-01"
                      :actuate-equipment? false}}}

   {:tid "t13"
    :exercises "A batch patch declaring a product type outside the closed known set. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-003"
              :patch {:product-type :unobtainium}}}

   {:tid "t14"
    :exercises "A batch patch with a substrate thickness far outside any physically plausible disc/tape/card reading. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-003"
              :patch {:substrate-thickness-mm 42.0}}}

   {:tid "t15"
    :exercises "A batch patch claiming a coating/stamping defect rate above 100%. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-003"
              :patch {:defect-rate-percent 480.0}}}

   {:tid "t16"
    :exercises "A patch trying to self-issue a content-replication licensing / source-identification authorization mark (IFPI SID-style). Authority this actor never holds -- permanent, and the approval offered here is never consumed. HARD hold."
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:issue-certification? true}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t17"
    :exercises "A mis-wired caller whose own request :effect is not :propose -- checked before anything else. HARD hold."
    :request {:op :log-production-batch :effect :direct-write :subject "batch-001"
              :patch {:product-type :cd}}}

   {:tid "t18"
    :exercises "An op outside the closed allowlist. Both the op allowlist and the proposal-effect allowlist reject it, so this row carries two violations. HARD hold."
    :request {:op :actuate-stamping-line :effect :propose :subject "batch-001"}}])

(defn- drive!
  "Runs one scenario through the real compiled graph and returns the
  scenario enriched with what the graph actually did."
  [actor {:keys [tid request approval] :as scenario}]
  (let [r1 (g/run* actor {:request request :context coordinator} {:thread-id tid})
        paused? (= :interrupted (:status r1))
        r2 (when (and approval paused?)
             (g/run* actor {:approval approval} {:thread-id tid :resume? true}))
        final (:state (or r2 r1))
        audit (:audit final [])]
    (assoc scenario
           :verdict (:verdict final)
           :paused? paused?
           :audit audit
           :escalation (first (filter #(= :approval-requested (:t %)) audit))
           :human (when r2 (:status approval))
           :disposition (:disposition final))))

(defn run-demo!
  "Seeds a MemStore, builds the real actor, drives every scenario.
  Returns {:db store :runs [..]}."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]
    {:db db :runs (mapv #(drive! actor %) scenarios)}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- fmt
  "Render a stored value, or an em dash when the domain model has no value
  for that field on that record."
  [v]
  (if (nil? v) "—" (esc v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- flag [v]
  (if (true? v)
    "<span class=\"ok\">true</span>"
    (str "<span class=\"muted\">" (if (nil? v) "—" (esc v)) "</span>")))

(defn- yes-no [b]
  (if b "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>"))

(defn- codes
  "Render a SEQUENCE of keywords in the order the code produced it -- used
  for `:basis`, whose order is the governor's own evaluation order."
  [coll]
  (str/join " " (map code coll)))

(defn- kw-codes
  "Render a SET of keywords. Sorted, because a set has no order and an
  unsorted render would make the output non-deterministic."
  [coll]
  (str/join " " (map code (sort-by str coll))))

(defn- tr [& cells] (str "<tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "<div class=\"tw\"><table><thead><tr>"
       (apply str (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead><tbody>\n"
       (str/join "\n" rows)
       "\n</tbody></table></div>"))

(defn- card [title note body]
  (str "<section class=\"card\"><h2>" (esc title) "</h2>"
       (when note (str "<p class=\"muted\">" note "</p>"))
       body "</section>"))

;; ----------------------------- derived facts -----------------------------

(defn- ledger-of [db] (vec (store/ledger db)))

(defn- holds
  "The HARD `:governor-hold` facts the run actually wrote to the ledger."
  [db]
  (filterv #(= :governor-hold (:t %)) (ledger-of db)))

(defn- stored-shipments
  "Committed shipment drafts, joined from the append-only shipment history
  back to each stored shipment record."
  [db]
  (vec (keep #(store/shipment db (get % "shipment_id")) (store/shipment-history db))))

(defn- deep-key-names
  "Every key name appearing anywhere inside `x`, at any depth, as strings.
  Used to ask the store a question rather than assert an answer about it."
  [x]
  (cond
    (map? x) (into (into #{} (map #(if (keyword? %) (name %) (str %))) (keys x))
                   (mapcat deep-key-names (vals x)))
    (sequential? x) (into #{} (mapcat deep-key-names x))
    :else #{}))

(defn- approver-attribution
  "DERIVED honest disclosure about where a human approver's id actually
  lands after a `:request-approval` handoff.

  This is a MEASUREMENT, not a claim. Every register the store exposes is
  scanned at render time for an approver-shaped key, and the ledger for an
  `:approval-granted` fact. Whatever the answer is on the day the page is
  built, the page says that -- so the disclosure cannot go stale the day
  the store starts (or stops) persisting it.

  Returns `{:approvers :on-record? :on-ledger? :registers}`."
  [db runs]
  (let [registers {"batches"             (store/all-batches db)
                   "equipment"           (store/all-equipment db)
                   "maintenance"         (store/all-maintenance db)
                   "shipments"           (stored-shipments db)
                   "safety-concerns"     (vec (store/safety-concerns db))
                   "maintenance-history" (vec (store/maintenance-history db))
                   "shipment-history"    (vec (store/shipment-history db))
                   "records"             (vec (vals (store/get-records db)))}
        approver? #(contains? #{"approved-by" "approved_by" "approver"
                                "approved_by_id" "approverid"}
                              (str/lower-case %))
        hit? (fn [coll] (boolean (some approver? (deep-key-names coll))))]
    {:approvers (vec (sort (into #{} (comp (mapcat :audit)
                                           (filter #(= :approval-granted (:t %)))
                                           (keep :by))
                                 runs)))
     :on-record? (boolean (some hit? (vals registers)))
     :on-ledger? (boolean (some #(some? (:by %)) (ledger-of db)))
     :registers (mapv (fn [[k v]] [k (count v) (hit? v)]) (sort-by key registers))}))

;; ----------------------------- sections -----------------------------

(defn- stat [label value]
  (str "<div class=\"stat\"><span class=\"n num\">" (esc value) "</span>"
       "<span class=\"l\">" (esc label) "</span></div>"))

(defn- summary-section [db runs]
  (let [led (ledger-of db)
        n (fn [t] (count (filter #(= t (:t %)) led)))]
    (card "Run summary"
          (str "Every number below is a count over the actor's own append-only ledger "
               "after driving " (count runs) " requests through "
               (code "magopticalmedia.operation/build") ".")
          (str "<div class=\"stats\">"
               (stat "requests driven" (count runs))
               (stat "ledger facts" (count led))
               (stat "commits" (n :committed))
               (stat "governor HARD holds" (n :governor-hold))
               (stat "distinct hold rules" (count (into #{} (mapcat :basis) (holds db))))
               (stat "human approvals" (count (filter #(= :approved (:human %)) runs)))
               (stat "human rejections" (count (filter #(= :rejected (:human %)) runs)))
               "</div>"
               "<p class=\"muted\">Note: <code>:approval-granted</code> is emitted to the graph's "
               "in-memory <code>:audit</code> channel only — <code>magopticalmedia.operation</code> "
               "never appends it to the store ledger, so it is not a fact this page counts. An "
               "approved request is visible as the <code>:committed</code> fact it produced.</p>"))))

(defn- verdict-cell [{:keys [verdict]}]
  (cond
    (nil? verdict) "<span class=\"muted\">—</span>"
    (:hard? verdict)
    (str "<span class=\"critical\">HARD</span> "
         (str/join " " (map code (map :rule (:violations verdict)))))
    (:escalate? verdict)
    (str "<span class=\"warn\">escalate</span>"
         (when (:high-stakes? verdict) " <span class=\"muted\">high-stakes</span>"))
    :else (str "<span class=\"ok\">clean</span> <span class=\"muted\">conf "
               (esc (:confidence verdict)) "</span>")))

(defn- human-cell [{:keys [approval human paused?]}]
  (cond
    (= :approved human) "<span class=\"ok\">approved</span>"
    (= :rejected human) "<span class=\"critical\">rejected</span>"
    (and approval (not paused?))
    "<span class=\"muted\">never offered (no interrupt)</span>"
    :else "<span class=\"muted\">—</span>"))

(defn- disposition-cell [{:keys [disposition]}]
  (case disposition
    :commit "<span class=\"ok\">commit</span>"
    :hold "<span class=\"critical\">hold</span>"
    :escalate "<span class=\"warn\">escalate</span>"
    (str "<span class=\"muted\">" (fmt disposition) "</span>")))

(defn- timeline-section [runs]
  (card "Request timeline"
        (str "One row = one <code>langgraph.graph/run*</code> over the compiled actor. The "
             "governor column is the verdict map the governor itself returned; the human column "
             "is the decision handed back to the graph while it was paused at "
             (code ":request-approval") ".")
        (table ["Thread" "Op" "Subject" "Governor" "Human" "Final" "What this exercises"]
               (for [{:keys [tid request escalation exercises] :as r} runs]
                 (tr (code tid)
                     (code (:op request))
                     (code (:subject request))
                     (verdict-cell r)
                     (human-cell r)
                     (str (disposition-cell r)
                          (when-let [reason (:reason escalation)]
                            (str " <span class=\"muted\">after escalation "
                                 (code reason) "</span>")))
                     (str "<span class=\"muted\">" (esc exercises) "</span>"))))))

(defn- holds-section [db]
  (let [hs (holds db)]
    (card "Governor HARD holds"
          (str "Each row is a <code>:governor-hold</code> fact on the append-only ledger. The rule "
               "name and the detail text are the governor's own " (code ":violations")
               " entries — this page holds no rule text of its own. A HARD hold is never "
               "overridable: it never reaches a human at all.")
          (table ["Rule" "Op" "Subject" "Confidence" "Governor's own detail"]
                 (for [h hs
                       v (:violations h)]
                   (tr (str "<span class=\"critical\">" (esc (:rule v)) "</span>")
                       (code (:op h))
                       (code (:subject h))
                       (fmt (:confidence h))
                       (esc (:detail v))))))))

(defn- rule-coverage-section [db]
  (let [fired (into #{} (mapcat :basis) (holds db))]
    (card "HARD-hold rule coverage"
          (str "Which of this governor's rules this run actually made fire. A rule listed as not "
               "exercised is not a claim that it works — it is a gap in this page's scenario set.")
          (table ["Rule" "Exercised by this run"]
                 (for [r (sort-by str fired)]
                   (tr (code r) "<span class=\"ok\">yes</span>"))))))

(defn- rejections-section [db]
  (let [rs (filterv #(= :approval-rejected (:t %)) (ledger-of db))]
    (when (seq rs)
      (card "Human rejections"
            (str "A governor-clean proposal a person declined. Written to the ledger by the same "
                 (code ":hold") " node, but with basis " (code ":approver-rejected")
                 " — not a compliance violation.")
            (table ["Op" "Subject" "Basis" "Confidence"]
                   (for [r rs]
                     (tr (code (:op r)) (code (:subject r))
                         (codes (:basis r)) (fmt (:confidence r)))))))))

(defn- attribution-section [{:keys [approvers on-record? on-ledger? registers]}]
  (card "Approver attribution — what the SSoT does and does not hold"
        (str "Re-checked against the real store at render time: every register below is scanned "
             "for an approver-shaped key, and the ledger for a fact carrying "
             (code ":by") ". This disclosure is derived, so it cannot drift away from the code.")
        (str (table ["Question" "Answer"]
                    [(tr "approver id(s) on this run's <code>:approval-granted</code> audit facts"
                         (if (seq approvers) (str/join " " (map code approvers)) "—"))
                     (tr "carried on any committed record in the SSoT" (yes-no on-record?))
                     (tr "carried on any fact in the store's audit ledger" (yes-no on-ledger?))])
             (table ["Register scanned" "Records" "Approver key present"]
                    (for [[nm cnt hit?] registers]
                      (tr (code nm) (esc cnt)
                          (if hit? "<span class=\"ok\">yes</span>"
                              "<span class=\"muted\">no</span>"))))
             "<p>"
             (cond
               (empty? approvers)
               "This run produced no human approval, so there is no approver to attribute."

               (and on-record? on-ledger?)
               (str "The approver is persisted on the committed record <em>and</em> on the audit "
                    "ledger. The approver ids shown elsewhere on this page are retrievable from "
                    "the SSoT, not only from the run timeline.")

               on-record?
               (str "The approver is persisted on the committed record, but not as a ledger fact "
                    "— the approver ids above are joined from the run's own "
                    "<code>:approval-granted</code> audit fact.")

               :else
               (str "<strong>The approver is not retained in the record.</strong> "
                    "<code>magopticalmedia.operation</code>'s <code>:request-approval</code> node "
                    "attaches the approver at <code>:payload</code> (as "
                    "<code>:approved-by</code>), but <code>store/commit-record!</code> "
                    "destructures <code>{:keys [effect path value]}</code> and never reads "
                    "<code>:payload</code>; the <code>:commit</code> node likewise appends only "
                    "its <code>:committed</code> fact to the ledger, never the "
                    "<code>:approval-granted</code> fact that carries <code>:by</code>. So the "
                    "approver reaches neither the SSoT record nor the store ledger. The approver "
                    "ids above are <em>audit only — not retained in record</em>, joined back from "
                    "the run's own audit trail, which really does carry them. This page states "
                    "the gap plainly rather than printing an approver as though the store held "
                    "one, and re-measures it on every build."))
             "</p>")))

(defn- phase-section []
  (let [ph phase/default-phase
        {:keys [label writes auto]} (get phase/phases ph)]
    (card (str "Rollout phase gate — phase " ph " (" label ")")
          (str "Derived from " (code "magopticalmedia.phase/phases") ". A governor HOLD always "
               "stays a HOLD; an op that may write but is not auto-eligible escalates to a human "
               "even when the governor is clean.")
          (table ["Op" "May write in this phase" "May auto-commit when governor-clean"]
                 (for [o (sort-by str governor/allowed-ops)]
                   (tr (code o)
                       (if (contains? writes o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"critical\">no — HOLD (:phase-disabled)</span>")
                       (if (contains? auto o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"warn\">no — always human approval</span>")))))))

(defn- governor-section []
  (card "Governor configuration"
        (str "Read straight off the public vars of " (code "magopticalmedia.governor") ".")
        (table ["Setting" "Value"]
               [(tr "confidence floor" (code governor/confidence-floor))
                (tr "allowed ops" (kw-codes governor/allowed-ops))
                (tr "allowed proposal effects" (kw-codes governor/allowed-proposal-effects))
                (tr "always-human stakes" (kw-codes governor/high-stakes))])))

(defn- bounds-section []
  (card "Independent ground-truth bounds"
        (str "The values " (code "magopticalmedia.registry") " uses to re-derive the truth itself, "
             "rather than believing the advisor's rationale.")
        (table ["Bound" "Value"]
               [(tr "valid product types" (kw-codes registry/valid-product-types))
                (tr "substrate thickness (mm)"
                    (str (code registry/substrate-thickness-mm-min) " … "
                         (code registry/substrate-thickness-mm-max)))
                (tr "defect rate (%)"
                    (str (code registry/defect-rate-min-percent) " … "
                         (code registry/defect-rate-max-percent)))])))

(defn- last-fact-for [led subject]
  (last (filter #(= subject (:subject %)) led)))

(defn- subject-status [led subject]
  (let [f (last-fact-for led subject)]
    (cond
      (nil? f) "<span class=\"muted\">no ledger activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-rejected (:t f)) "<span class=\"critical\">rejected by approver</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold</span> " (codes (:basis f)))
      :else (str "<span class=\"muted\">" (esc (:t f)) "</span>"))))

(defn- remaining [b]
  (let [q (:quantity-units b) s (:shipped-units b 0.0)]
    (when (and (number? q) (number? s)) (- (double q) (double s)))))

(defn- batches-section [db]
  (let [led (ledger-of db)]
    (card "Production batches"
          (str "Read back from " (code "magopticalmedia.store/all-batches") " after the run. "
               (code "batch-001") " " (code "batch-002") " " (code "batch-003")
               " are seeded by " (code "store/sample-data!") "; " (code "batch-004")
               " exists because the <code>t01</code> intake op committed it. A field the record "
               "does not carry shows as —; " (code "batch-004") " has no "
               (code ":shipped-units") " of its own yet, so <em>Remaining</em> uses the same "
               (code "0.0") " default " (code "magopticalmedia.registry")
               " itself applies when it recomputes headroom.")
          (table ["Batch" "Product type" "Model" "Substrate (mm)" "Quantity (units)"
                  "Shipped (units)" "Remaining" "Defect rate (%)" "verified?" "registered?"
                  "ready?" "Last assessed" "Ledger status"]
                 (for [b (store/all-batches db)]
                   (tr (code (:id b)) (fmt (:product-type b)) (fmt (:model b))
                       (fmt (:substrate-thickness-mm b)) (fmt (:quantity-units b))
                       (fmt (:shipped-units b)) (fmt (remaining b))
                       (fmt (:defect-rate-percent b))
                       (flag (:verified? b)) (flag (:registered? b))
                       (yes-no (registry/batch-ready? b))
                       (fmt (:last-assessed b))
                       (subject-status led (:id b))))))))

(defn- equipment-section [db]
  (card "Coating / stamping-line equipment"
        (str "Read back from " (code "magopticalmedia.store/all-equipment")
             ". Equipment ids are never a request <code>:subject</code> in this domain (a "
             "maintenance draft id is), so no ledger-status column is shown for them — "
             (code ":last-scheduled-maintenance-date")
             " is the field the commit path actually writes onto an equipment record.")
        (table ["Unit" "Kind" "verified?" "registered?" "ready?" "Last maintenance"
                "Last scheduled maintenance" "Maintenance drafts on file"]
               (for [e (store/all-equipment db)]
                 (tr (code (:id e)) (fmt (:kind e))
                     (flag (:verified? e)) (flag (:registered? e))
                     (yes-no (registry/equipment-ready? e))
                     (fmt (:last-maintenance-date e))
                     (fmt (:last-scheduled-maintenance-date e))
                     (esc (count (filter #(= (:id e) (:equipment-id %))
                                         (store/all-maintenance db)))))))))

(defn- maintenance-section [db]
  (let [ms (store/all-maintenance db)]
    (card "Maintenance schedule drafts"
          (str "Committed drafts from " (code "magopticalmedia.store/all-maintenance")
               ". The maintenance number is minted by "
               (code "magopticalmedia.registry/register-maintenance")
               " at commit time. Nothing here actuates any coating, molding or stamping line.")
          (if (seq ms)
            (table ["Draft" "Equipment" "Type" "Scheduled date" "actuate-equipment?"
                    "scheduled?" "Maintenance number"]
                   (for [m ms]
                     (tr (code (:id m)) (code (:equipment-id m)) (fmt (:maintenance-type m))
                         (fmt (:scheduled-date m)) (flag (:actuate-equipment? m))
                         (flag (:scheduled? m)) (fmt (:maintenance-number m)))))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- shipments-section [db]
  (let [ships (stored-shipments db)]
    (card "Shipment coordination drafts"
          (str "Committed drafts, joined from " (code "magopticalmedia.store/shipment-history")
               " back to each stored shipment record. This is a draft a coordinator keeps — it "
               "dispatches no freight carrier.")
          (if (seq ships)
            (table ["Draft" "Batch" "Units" "Destination" "Shipment number"]
                   (for [s ships]
                     (tr (code (:id s)) (code (:batch-id s)) (fmt (:units s))
                         (fmt (:destination s)) (fmt (:shipment-number s)))))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- certificates-section [db]
  (let [hist (concat (store/maintenance-history db) (store/shipment-history db))]
    (card "Draft certificates (all unsigned)"
          (str "Every certificate " (code "magopticalmedia.registry")
               " mints is UNSIGNED and " (code "issued_by_registry") " false — signature is the "
               "human supervisor's / shipping approver's act, never this actor's, and never a "
               "content-replication licensing or source-identification authorization mark.")
          (if (seq hist)
            (table ["Record" "Kind" "Immutable"]
                   (for [r (sort-by #(get % "record_id") hist)]
                     (tr (code (get r "record_id")) (fmt (get r "kind"))
                         (flag (get r "immutable")))))
            "<p class=\"muted\">none minted in this run</p>"))))

(defn- concerns-section [db]
  (let [cs (store/safety-concerns db)]
    (card "Safety concerns"
          (str "The append-only safety-concern log ("
               (code "magopticalmedia.store/safety-concerns")
               "). A concern may be raised against any equipment, verified or not — it is never "
               "blocked on an administrative technicality.")
          (if (seq cs)
            (table ["Concern" "Equipment" "Severity" "Description"]
                   (for [c cs]
                     (tr (code (:id c)) (code (:equipment-id c)) (fmt (:severity c))
                         (fmt (:description c)))))
            "<p class=\"muted\">none flagged in this run</p>"))))

(defn- ledger-section [db]
  (card "Audit ledger (append-only)"
        (str "The full ledger, in append order, exactly as "
             (code "magopticalmedia.store/ledger") " returns it.")
        (table ["#" "Fact" "Op" "Subject" "Actor" "Disposition" "Basis"]
               (map-indexed
                (fn [i f]
                  (tr (esc (inc i))
                      (let [cls (case (:t f)
                                  :committed "ok"
                                  :governor-hold "critical"
                                  :approval-rejected "critical"
                                  "muted")]
                        (str "<span class=\"" cls "\">" (esc (:t f)) "</span>"))
                      (code (:op f)) (code (:subject f)) (fmt (:actor f))
                      (fmt (:disposition f)) (codes (:basis f))))
                (ledger-of db)))))

;; ----------------------------- page -----------------------------

(def ^:private app-css
  "The little that jp-go-dds does not carry: the stat tile and a scroll box
  for the wide tables. Everything else is DADS + skin."
  (str ".stats{display:flex;flex-wrap:wrap;gap:.75rem;margin:.5rem 0 1rem}"
       ".stat{border:1px solid var(--color-neutral-solid-gray-200);border-radius:.5rem;"
       "padding:.6rem .9rem;min-width:9rem}"
       ".stat .n{display:block;font-size:1.5rem;font-weight:700;line-height:1.1}"
       ".stat .l{display:block;font-size:.8125rem;color:var(--color-neutral-solid-gray-600)}"
       ".tw{overflow-x:auto}"
       ".card h2{font-size:1.0625rem}"))

(defn render
  "The whole page, from the post-run store and the run log."
  [{:keys [db runs]}]
  (str "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<meta name=\"color-scheme\" content=\"light\">"
       "<title>Operator console — cloud-itonami-isic-2680 (magopticalmedia)</title>"
       "<style>" (jp-go-dds.skin/dds+skin) "\n" app-css "</style></head>\n<body>\n"
       "<div class=\"container\">\n"
       "<header class=\"bar\">"
       "<span class=\"badge\">ISIC 2680</span>"
       "<span class=\"badge\">magopticalmedia</span>"
       "<strong>Magnetic &amp; optical media plant operations — operator console</strong>"
       "</header>\n"
       "<p class=\"subtitle\">governor "
       (code "magnetic-optical-media-plant-operations-governor") " · actor "
       (esc (:actor-id coordinator)) " · role " (esc (:actor-role coordinator))
       " · phase " (esc (:phase coordinator))
       " · CD / DVD / Blu-ray optical-disc molding &amp; stamping, magnetic-tape coating, "
       "magnetic-strip cards</p>\n"
       "<main>\n"
       (str/join "\n"
                 (remove nil?
                         [(summary-section db runs)
                          (timeline-section runs)
                          (holds-section db)
                          (rule-coverage-section db)
                          (rejections-section db)
                          (attribution-section (approver-attribution db runs))
                          (phase-section)
                          (governor-section)
                          (bounds-section)
                          (batches-section db)
                          (equipment-section db)
                          (maintenance-section db)
                          (shipments-section db)
                          (certificates-section db)
                          (concerns-section db)
                          (ledger-section db)]))
       "\n</main>\n<footer>"
       "Generated at build time by <code>magopticalmedia.render-html</code> "
       "(<code>clojure -M:render-html</code>) by driving the real "
       "<code>magopticalmedia.operation</code> actor graph over the real "
       "<code>magopticalmedia.store</code> seed. Deterministic — no clock, no randomness, no "
       "network. No usage, revenue or performance metric is claimed anywhere on this page."
       "</footer>\n</div>\n</body>\n</html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (holds db)
        rules (into #{} (mapcat :basis) hs)]
    ;; Build-time invariant, not a comment: a console that shows no real HARD
    ;; hold is not evidence of a governor. Refuse to write the file.
    (when (empty? hs)
      (throw (ex-info (str "no :governor-hold fact on the ledger — refusing to write a console "
                           "that shows no real hold")
                      {:ledger-facts (count (store/ledger db))
                       :requests (count runs)})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds over " (count rules) " distinct rules, "
                  (count runs) " requests)"))))
