(ns yugabyte.ysql.single-key-acid
  "Given a single table of hash column primary key and one value column with 5 rows,
  verify that concurrent reads, writes and read-modify-write (UPDATE IF) operations
  results in linearizable history"
  (:require [clojure.java.jdbc :as j]
            [clojure.string :as str]
            [clojure.tools.logging :refer [debug info warn]]
            [jepsen.client :as client]
            [jepsen.independent :as independent]
            [jepsen.reconnect :as rc]
            [yugabyte.ysql.client :as c]))

(def table-name "single_key_acid")

(defrecord YSQLSingleKeyAcidClientInner []
  c/YSQLClientBase

  (setup-cluster! [this test c conn-wrapper]
    (c/execute! c (j/create-table-ddl table-name [[:id :int "PRIMARY KEY"]
                                                  [:val :int]]))
    (doseq [id (range 5)]
      (c/insert! c table-name {:id id :val 0})))

  (invoke-inner! [this test op c conn-wrapper]
    (let [[id val] (:value op)]
      (case (:f op)
        :write
        (do (c/update! c table-name {:val val} ["id = ?" id])
            (assoc op :type :ok))

        :cas
        (let [[expected-val new-val] val
              res     (c/update! c table-name
                                 {:val new-val}
                                 ["id = ? AND val = ?" id expected-val])
              applied (> (first res) 0)]
          (assoc op :type (if applied :ok :fail)))

        :read
        (let [value (c/select-single-value c table-name :val (str "id = " id))]
          (assoc op :type :ok :value (independent/tuple id value))))))

  (teardown-cluster! [this test c conn-wrapper]
    (c/drop-table c table-name)))


(c/defclient YSQLSingleKeyAcidClient YSQLSingleKeyAcidClientInner)
