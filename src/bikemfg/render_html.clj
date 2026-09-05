(ns bikemfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: `cloud-itonami-isic-3092`
  previously had NO demo page and no generator at all. This namespace
  drives the REAL actor stack (`bikemfg.operation` -> `bikemfg.advisor`
  -> `bikemfg.governor` -> `bikemfg.phase` -> `bikemfg.store`) through a
  scenario adapted from this repo's own `bikemfg.sim` demo driver
  (`clojure -M:dev:run`, run and read BEFORE writing this file so that
  every entity id used below -- `batch-001`/`batch-002`/`batch-003`,
  `welder-001`/`testbench-002` -- is a real id actually present in
  `bikemfg.store/sample-data!`, not an invented one that would render an
  empty table).

  What is REAL RUNTIME OUTPUT on the generated page:
    - the production-batch and equipment tables (`store/all-batches`,
      `store/all-equipment`), including every `:shipped-units` value,
      which the actor itself moved during the run;
    - the maintenance-schedule and shipment-coordination DRAFT records
      and their `MNT-`/`SHP-` sequence numbers (`bikemfg.registry`, via
      `store/all-maintenance` / `store/shipment-history`);
    - the flagged safety concerns (`store/safety-concerns`);
    - the governor-hold table, including every `:rule` keyword and every
      Japanese `:detail` string -- these are emitted verbatim by
      `bikemfg.governor`, never retyped here;
    - the whole append-only audit ledger (`store/ledger`).

  What is STATIC DESCRIPTION of this actor's fixed contract (honestly
  labelled as such on the page, and derived from `bikemfg.phase`'s own
  `phases` map rather than retyped):
    - the `action-gate-rows` table -- which ops may auto-commit at which
      phase. That is a property of the compiled contract, not of any one
      run, so describing it is legitimate; it is NOT presented as
      telemetry.
    - `permanent-rules` -- which two HARD rules are the PERMANENT,
      unconditional blocks (see `bikemfg.governor` docstring items 4 and
      5). The holds themselves are runtime output; only the
      'this one can never be approved by anybody' annotation is contract.

  Deterministic by construction: no timestamps, no randomness, no
  wall-clock anywhere in the page content, a freshly seeded store per
  run, and every collection rendered from an ordered source
  (`sort-by :id` inside the store, or an append-only vector). Two
  consecutive runs are byte-identical -- verify with a diff.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [bikemfg.store :as store]
            [bikemfg.phase :as phase]
            [bikemfg.operation :as op]
            [langgraph.graph :as g]))

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- exec!
  "One coordination request = one supervised actor run on its own thread-id."
  [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- approve!
  "Resume a run paused at `:request-approval` with a human plant
  supervisor's / shipping approver's approval. Only ever reachable for
  an ESCALATE disposition -- a HARD hold never gets here."
  [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario that reaches every
  disposition this actor can produce.

  Clean / approved paths:
    - `batch-001` logs a clean production-batch patch -> governor-clean,
      and `:log-production-batch` is the ONE op in phase 3's `:auto` set,
      so it AUTO-COMMITS with no human in the loop;
    - `mnt-1` schedules torch-inspection maintenance on `welder-001`
      (independently verified AND registered) -> `bikemfg.phase` never
      puts `:schedule-maintenance` in any phase's `:auto` set, so it
      ESCALATES even though the governor is clean -> approved;
    - `concern-1` flags a frame-weld / brake-mount safety concern on
      `welder-001` -> `:coordination/safety-concern` is permanently
      high-stakes, so it ESCALATES regardless of confidence -> approved;
    - `ship-1` coordinates 50 units out of `batch-001` (500 produced,
      100 already shipped -> real headroom) -> ESCALATES -> approved,
      and the commit moves `batch-001`'s own `:shipped-units` 100 -> 150.

  HARD holds -- none of these ever reaches a human; the graph routes
  `:hold` straight past `:request-approval`:
    - `mnt-3` asks to directly ACTUATE `welder-001`
      (`:actuate-equipment? true`) -> `:equipment-actuate-blocked`.
      PERMANENT: no phase and no approver can ever override it.
    - `batch-002` tries to self-issue an ISO 4210 / ISO 7176 safety
      certification (`:issue-certification? true`) ->
      `:certification-authority-blocked`. PERMANENT for the same reason
      -- certification is the accredited body's exclusive authority.
    - `mnt-2` schedules maintenance on `testbench-002`, which is
      UNVERIFIED and unregistered -> `:equipment-not-verified`.
    - `ship-2` ships out of `batch-003`, which is UNVERIFIED and
      unregistered -> `:batch-not-verified`.
    - `ship-3` ships 10 more units of `batch-002` (80 produced, 75
      already shipped) -> `:shipment-quantity-exceeded`, recomputed by
      the governor from the batch's own fields, never from the proposal.
    - `mnt-1` again -> `:already-scheduled`.
    - `batch-003` patches a fabricated `:product-category :hoverboard`
      -> `:invalid-product-category`.
    - `batch-003` patches an implausible `:weight-capacity-kg 9999.0`
      -> `:invalid-weight-capacity`.
    - `batch-003` patches an implausible `:weld-defect-rate-percent
      999.0` -> `:invalid-defect-rate`.
    - a mis-wired caller sends `:effect :direct-write` instead of
      `:propose` -> `:not-propose-effect`, checked before anything else.
    - an unrecognized `:actuate-welding-line` op -> `:unknown-op`
      together with `:equipment-control-blocked`.

  Returns the resulting store. Every value the page shows is read back
  out of this store -- nothing on the page is hand-typed."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    ;; --- clean / approved paths -------------------------------------
    (exec! actor "b1-log"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-category :road-bicycle :last-assessed "2026-07-14"}})

    (exec! actor "m1"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "welder-001" :maintenance-type :torch-inspection
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})
    (approve! actor "m1")

    (exec! actor "c1"
           {:op :flag-safety-concern :effect :propose :subject "concern-1"
            :value {:equipment-id "welder-001" :severity :moderate
                    :description "フレーム溶接部の異音、ブレーキマウントの位置ずれ兆候"}})
    (approve! actor "c1")

    (exec! actor "s1"
           {:op :coordinate-shipment :effect :propose :subject "ship-1"
            :value {:batch-id "batch-001" :units 50.0
                    :destination "buyer-yard-north"}})
    (approve! actor "s1")

    ;; --- PERMANENT HARD blocks (never approvable by anyone) ----------
    (exec! actor "m3"
           {:op :schedule-maintenance :effect :propose :subject "mnt-3"
            :value {:equipment-id "welder-001" :maintenance-type :force-run
                    :scheduled-date "2026-09-01" :actuate-equipment? true}})

    (exec! actor "b2-cert"
           {:op :log-production-batch :effect :propose :subject "batch-002"
            :patch {:issue-certification? true}})

    ;; --- other HARD holds -------------------------------------------
    (exec! actor "m2"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2"
            :value {:equipment-id "testbench-002" :maintenance-type :load-cell-calibration
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (exec! actor "s2"
           {:op :coordinate-shipment :effect :propose :subject "ship-2"
            :value {:batch-id "batch-003" :units 100.0
                    :destination "buyer-yard-south"}})

    (exec! actor "s3"
           {:op :coordinate-shipment :effect :propose :subject "ship-3"
            :value {:batch-id "batch-002" :units 10.0
                    :destination "buyer-yard-east"}})

    (exec! actor "m1-again"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "welder-001" :maintenance-type :torch-inspection
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (exec! actor "b3-cat"
           {:op :log-production-batch :effect :propose :subject "batch-003"
            :patch {:product-category :hoverboard}})

    (exec! actor "b3-cap"
           {:op :log-production-batch :effect :propose :subject "batch-003"
            :patch {:weight-capacity-kg 9999.0}})

    (exec! actor "b3-defect"
           {:op :log-production-batch :effect :propose :subject "batch-003"
            :patch {:weld-defect-rate-percent 999.0}})

    (exec! actor "b1-bypass"
           {:op :log-production-batch :effect :direct-write :subject "batch-001"
            :patch {:product-category :road-bicycle}})

    (exec! actor "unknown-op"
           {:op :actuate-welding-line :effect :propose :subject "welder-001"})

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-name [v]
  (if (keyword? v) (name v) (str v)))

(defn- num
  "Render a numeric field without a locale- or platform-dependent tail:
  `500.0` -> `500`, `0.8` -> `0.8`, nil -> em dash."
  [v]
  (cond
    (nil? v) "—"
    (and (number? v) (zero? (rem (double v) 1.0))) (str (long v))
    :else (str v)))

(def ^:private permanent-rules
  "The two HARD rules `bikemfg.governor` documents as PERMANENT and
  unconditional (docstring items 4 and 5): no rollout phase and no human
  approver can ever override them. Contract, not telemetry -- the holds
  themselves come from the live run."
  #{:equipment-actuate-blocked :certification-authority-blocked})

(defn- last-fact-for [ledger subject]
  (last (filter #(= (:subject %) subject) ledger)))

(defn- status-cell [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; "
             (esc (kw-name (or rule :unknown))) "</span>"))
      :else "<span class=\"muted\">in progress</span>")))

(defn- ready-cell [{:keys [verified? registered?]}]
  (if (and verified? registered?)
    "<span class=\"ok\">verified &amp; registered</span>"
    (str "<span class=\"critical\">"
         (if verified? "verified" "UNVERIFIED") " / "
         (if registered? "registered" "unregistered")
         "</span>")))

(defn- batch-row [ledger {:keys [id product-category frame-size weight-capacity-kg
                                 quantity-units shipped-units
                                 weld-defect-rate-percent] :as b}]
  (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td>"
               "<td class=\"num\">%s</td><td class=\"num\">%s</td>"
               "<td class=\"num\">%s</td><td class=\"num\">%s</td>"
               "<td>%s</td><td>%s</td></tr>")
          (esc id) (esc (kw-name product-category)) (esc frame-size)
          (esc (num weight-capacity-kg)) (esc (num quantity-units))
          (esc (num shipped-units)) (esc (num weld-defect-rate-percent))
          (ready-cell b) (status-cell ledger id)))

(defn- equipment-row [ledger {:keys [id kind last-maintenance-date
                                     last-scheduled-maintenance-date] :as e}]
  (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td>"
               "<td>%s</td><td>%s</td><td>%s</td></tr>")
          (esc id) (esc (kw-name kind))
          (esc (or last-maintenance-date "—"))
          (esc (or last-scheduled-maintenance-date "—"))
          (ready-cell e)
          (status-cell ledger id)))

(defn- maintenance-row [{:keys [id equipment-id maintenance-type scheduled-date
                               maintenance-number scheduled?]}]
  (format (str "        <tr><td><code>%s</code></td><td><code>%s</code></td>"
               "<td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>")
          (esc (or maintenance-number "—")) (esc id) (esc equipment-id)
          (esc (kw-name maintenance-type)) (esc scheduled-date)
          (if scheduled?
            "<span class=\"ok\">scheduled (draft)</span>"
            "<span class=\"muted\">not scheduled</span>")))

(defn- shipment-row [{:keys [id batch-id units destination shipment-number]}]
  (format (str "        <tr><td><code>%s</code></td><td><code>%s</code></td>"
               "<td><code>%s</code></td><td class=\"num\">%s</td><td>%s</td></tr>")
          (esc (or shipment-number "—")) (esc id) (esc batch-id)
          (esc (num units)) (esc destination)))

(defn- concern-row [{:keys [id equipment-id severity description]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc equipment-id) (esc (kw-name severity)) (esc description)))

(defn- hold-row [{:keys [op subject violations]}]
  (let [{:keys [rule detail]} (first violations)]
    (format (str "        <tr><td><code>%s</code></td><td>%s</td>"
                 "<td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>")
            (esc (kw-name rule))
            (if (contains? permanent-rules rule)
              "<span class=\"critical\">PERMANENT &middot; never approvable</span>"
              "<span class=\"warn\">HARD &middot; no override this run</span>")
            (esc (kw-name (or op :n-a))) (esc subject)
            (esc detail))))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
          (esc (kw-name t)) (esc (kw-name (or op :n-a))) (esc subject)
          (esc (or (some->> basis seq (map kw-name) (str/join ", "))
                   (some-> disposition kw-name) ""))))

(defn- action-gate-rows
  "Derived from `bikemfg.phase/phases` at the repo's `default-phase`, so
  the gate table cannot drift away from the contract it describes. This
  is a STATIC property of the compiled actor (which ops may auto-commit),
  not telemetry from the run above -- the page labels it as such."
  []
  (let [ph phase/default-phase
        {:keys [writes auto]} (get phase/phases ph)]
    (for [op (sort-by kw-name phase/write-ops)]
      (format "        <tr><td><code>%s</code></td><td>%s</td></tr>"
              (esc (kw-name op))
              (cond
                (not (contains? writes op))
                (format "<span class=\"critical\">phase %s: writes disabled</span>" ph)

                (contains? auto op)
                (format (str "<span class=\"ok\">phase %s: auto-commit when governor-clean"
                             "</span> <span class=\"muted\">(administrative logging, no "
                             "physical consequence)</span>") ph)

                :else
                (format (str "<span class=\"warn\">ALWAYS human approval</span> "
                             "<span class=\"muted\">&middot; never in any phase's "
                             "<code>:auto</code> set, including phase %s</span>") ph))))))

(defn render
  "Renders the full operator-console.html document from a store `db` that
  has already been driven by `run-demo!` (or any other real scenario).
  Reads only -- performs no inference of its own."
  [db]
  (let [ledger (vec (store/ledger db))
        holds (filterv #(= :governor-hold (:t %)) ledger)
        commits (filterv #(= :committed (:t %)) ledger)
        batches (store/all-batches db)
        equipment (store/all-equipment db)
        maintenances (store/all-maintenance db)
        shipments (keep #(store/shipment db (get % "shipment_id"))
                        (store/shipment-history db))
        concerns (store/safety-concerns db)]
    (str
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-3092 &middot; bicycle &amp; invalid-carriage plant operations</title>"
     "<style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of bicycles &amp; invalid carriages (ISIC 3092) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · equipment actuation &amp; ISO 4210/7176 certification permanently blocked</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Production batches</h2>\n"
     "    <p class=\"muted\">Build-time snapshot generated from <code>bikemfg.store</code> by <code>bikemfg.render-html</code> (<code>clojure -M:dev:render-html</code>). Every row below is read back out of the SSoT <em>after</em> the actor ran — the shipped-unit counts include the movement the actor itself committed during this run.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Product category</th><th>Frame size</th><th>Weight cap. (kg)</th><th>Produced (units)</th><th>Shipped (units)</th><th>Weld defect (%)</th><th>Ground truth</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial batch-row ledger) batches)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Welding / assembly / test-bench equipment</h2>\n"
     "    <p class=\"muted\">The governor re-derives <code>:verified?</code> and <code>:registered?</code> from these records directly — it never trusts the advisor's own report about them.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Unit</th><th>Kind</th><th>Last maintenance</th><th>Last scheduled</th><th>Ground truth</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial equipment-row ledger) equipment)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Bicycle &amp; Mobility Device Plant Operations Governor)</h2>\n"
     "    <p class=\"muted\">Static description of the compiled contract, derived from <code>bikemfg.phase/phases</code> at phase " (esc phase/default-phase) " — not telemetry from the run. HARD governor violations are never routed to a human at all; they short-circuit to <code>:hold</code> without passing through <code>:request-approval</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (action-gate-rows)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Governor holds (this run)</h2>\n"
     "    <p class=\"muted\">" (esc (count holds)) " HARD holds, emitted by <code>bikemfg.governor</code>. The rule keywords and the detail text below are the governor's own output, verbatim. Two of these rules are <strong>PERMANENT</strong>: directly actuating welding/assembly/test-bench equipment, and self-issuing an ISO 4210 (bicycle) / ISO 7176 (wheelchair) safety certification. No rollout phase and no human approver can ever override those two.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Rule</th><th>Class</th><th>Op</th><th>Subject</th><th>Governor detail</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map hold-row holds)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Maintenance-window drafts</h2>\n"
     "    <p class=\"muted\">Drafts only — <code>bikemfg.registry/register-maintenance</code> builds the record a plant coordinator would keep. Nothing here actuates any equipment. Sequence numbers are assigned by the store.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Record</th><th>Maintenance</th><th>Equipment</th><th>Type</th><th>Scheduled date</th><th>State</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map maintenance-row maintenances)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Shipment-coordination drafts</h2>\n"
     "    <p class=\"muted\">Drafts only — <code>bikemfg.registry/register-shipment</code> dispatches no real freight carrier. The governor independently recomputes each batch's remaining headroom from the batch's own record before any of these may commit.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Record</th><th>Shipment</th><th>Batch</th><th>Units</th><th>Destination</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map shipment-row shipments)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Flagged safety concerns</h2>\n"
     "    <p class=\"muted\"><code>:flag-safety-concern</code> always escalates to a human plant supervisor, at every phase and at every confidence level — it is permanently high-stakes and is never in any phase's <code>:auto</code> set.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Concern</th><th>Equipment</th><th>Severity</th><th>Description</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map concern-row concerns)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — " (esc (count commits)) " commits and " (esc (count holds)) " holds, in the order the actor produced them.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "<footer>\n"
     "  <p>Generated at build time by <code>bikemfg.render-html</code> from a fresh <code>bikemfg.store</code> seed. Deterministic: no timestamps, no randomness — two consecutive runs are byte-identical.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)
        ledger (store/ledger db)]
    (io/make-parents out)
    (spit out html)
    (println "wrote" out "("
             (count ledger) "ledger facts,"
             (count (filterv #(= :governor-hold (:t %)) ledger)) "HARD holds,"
             (count (store/all-batches db)) "batches,"
             (count (store/all-equipment db)) "equipment units,"
             (count (store/maintenance-history db)) "maintenance drafts,"
             (count (store/shipment-history db)) "shipment drafts,"
             (count (store/safety-concerns db)) "safety concerns )")))
