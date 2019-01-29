(ns yugabyte.nemesis
  (:require [clojure.tools.logging :refer :all]
            [jepsen [control :as c]
             [generator :as gen]
             [nemesis :as nemesis]
             [util :as util :refer [meh timeout]]]
            [jepsen.nemesis.time :as nt]
            [slingshot.slingshot :refer [try+]]
            [yugabyte.auto :as auto]))

(def nemesis-delay 5) ; Delay between nemesis cycles in seconds.
(def nemesis-duration 60) ; Duration of single nemesis cycle in secods.

(defn kill!
  [node process opts]
  (meh (c/exec :pkill opts process))
  (info (c/exec :echo :pkill opts process))
  (c/exec (c/lit (str "! ps -ce | grep" process)))
  (info node process "killed.")
  :killed)

(defn none
  "No-op nemesis"
  []
  nemesis/noop
)

(defn tserver-killer
  "Kills a random node tserver on start, restarts it on stop."
  [& kill-opts]
  (nemesis/node-start-stopper
    rand-nth
    (fn start [test node] (kill! node "yb-tserver" kill-opts))
    (fn stop  [test node] (auto/start-tserver! (:db test) test))))

(defn master-killer
  "Kills a random node master on start, restarts it on stop."
  [& kill-opts]
  (nemesis/node-start-stopper
    (comp rand-nth auto/running-masters)
    (fn start [test node] (kill! node "yb-master" kill-opts))
    (fn stop  [test node] (auto/start-master! (:db test) test))))

(defn node-killer
  "Kills a random node tserver and master on start, restarts it on stop."
  [& kill-opts]
  (nemesis/node-start-stopper
    rand-nth
    (fn start [test node]
      (kill! node "yb-tserver" kill-opts)
      (kill! node "yb-master" kill-opts)
    )
    (fn stop  [test node]
      (auto/start-master! (:db test) test)
      (auto/start-tserver! (:db test) test)
    )
  )
)

(defn gen-start-stop
  "Generates start/stop generic nemesis events sequences"
  []
  (gen/seq
   (cycle
    [(gen/sleep nemesis-delay)
     {:type :info :f :start}
     (gen/sleep nemesis-duration)
     {:type :info :f :stop}])))

(defn start-stop
  "Return start-stop nemesis map config"
  [nemesis]
  {:nemesis nemesis
   :generator `(gen-start-stop)
   :final-generator `(gen/once {:type :info, :f :stop})
   }
)

(defn bump-gen
  "Randomized clock bump generator. On random subsets of nodes, bumps the clock
  from -max-skew to +max-skew milliseconds, exponentially distributed."
  [max-skew-ms test process]
  (let [gen (nt/bump-gen test process)]
    (assoc gen :value
           (->> (:value gen)
                (map (fn [x] [(key x) (-> x val (* max-skew-ms) (quot 262144))]))
                (into {})))))

(defn clock-gen
  "Emits a random schedule of clock skew operations up to skew-ms milliseconds."
  [max-skew-ms]
  (->>
    (gen/mix (concat [nt/reset-gen] (repeat 3 (partial bump-gen max-skew-ms))))
    (gen/delay nemesis-delay)
   ))

(defn clock-nemesis
  "Return clock skew nemesis map config"
  [max-skew-ms]
  ; TODO: uh, is this redefining globals?
  (def clock-gen-partial (partial clock-gen max-skew-ms))
  {:nemesis `(nt/clock-nemesis)
   :max-clock-skew-ms max-skew-ms
   :generator `(clock-gen-partial)
   :final-generator `(gen/once nt/reset-gen)
   }
)

(def nemeses
  "Supported nemeses"
  {"none"                       {:nemesis `(none)}
   "start-stop-tserver"         (start-stop `(tserver-killer))
   "start-kill-tserver"         (start-stop `(tserver-killer :-9))
   "start-stop-master"          (start-stop `(master-killer))
   "start-kill-master"          (start-stop `(master-killer :-9))
   "start-stop-node"            (start-stop `(node-killer))
   "start-kill-node"            (start-stop `(node-killer :-9))
   "partition-random-halves"    (start-stop `(nemesis/partition-random-halves))
   "partition-random-node"      (start-stop `(nemesis/partition-random-node))
   "partition-majorities-ring"  (start-stop `(nemesis/partition-majorities-ring))
   "small-skew"                 (clock-nemesis 100)
   "medium-skew"                (clock-nemesis 250)
   "large-skew"                 (clock-nemesis 500)
   "xlarge-skew"                (clock-nemesis 1000)
  }
)

(defn gen
  [opts]
  (->> opts
    :nemesis
    (get nemeses)
    :generator
    eval))

(defn final-gen
  [opts]
  (->> opts
       :nemesis
       (get nemeses)
       :final-generator
       eval))

(defn get-nemesis-by-name
  [name]
  (->> name
    (get nemeses)
    :nemesis
    eval))

(defn get-nemesis-max-clock-skew-ms
  [opts]
  (->> opts
       :nemesis
       (get nemeses)
       :max-clock-skew-ms))
