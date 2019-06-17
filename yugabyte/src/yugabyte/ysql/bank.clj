(ns yugabyte.ysql.bank
  "Simulates transfers between bank accounts"
  (:require [clojure.java.jdbc :as j]
            [clojure.string :as str]
            [clojure.tools.logging :refer [debug info warn]]
            [jepsen.client :as client]
            [jepsen.reconnect :as rc]
            [yugabyte.ysql.client :as c]))

(def table-name "accounts")

;
; Single-table bank test
;

(defrecord YSQLBankClientInner [allow-negatives?]
  c/YSQLClientBase

  (setup-cluster! [this test c conn-wrapper]
    (c/execute! c (j/create-table-ddl table-name [[:id :int "PRIMARY KEY"]
                                                  [:balance :bigint]]))
    (c/with-retry
      (info "Creating accounts")
      (c/insert! c table-name {:id      (first (:accounts test))
                               :balance (:total-amount test)})
      (doseq [acct (rest (:accounts test))]
        (c/insert! c table-name {:id      acct,
                                 :balance 0}))))


  (invoke-inner! [this test op c conn-wrapper]
    (case (:f op)
      :read
      (->> (str "SELECT id, balance FROM " table-name)
           (c/query c)
           (map (juxt :id :balance))
           (into (sorted-map))
           (assoc op :type :ok, :value))

      :transfer
      (let [{:keys [from to amount]} (:value op)]
        (c/with-txn
          c
          (let [b-from-before (c/select-single-value c table-name :balance (str "id = " from))
                b-to-before   (c/select-single-value c table-name :balance (str "id = " to))
                b-from-after  (- b-from-before amount)
                b-to-after    (+ b-to-before amount)
                allowed?      (or allow-negatives? (pos? b-from-after))]
            (if (not allowed?)
              (assoc op :type :fail, :error [:negative from b-from-after])
              (do (c/update! c table-name {:balance b-from-after} ["id = ?" from])
                  (c/update! c table-name {:balance b-to-after} ["id = ?" to])
                  (assoc op :type :ok))))))))


  (teardown-cluster! [this test c conn-wrapper]
    (c/drop-table c table-name)))


(c/defclient YSQLBankClient YSQLBankClientInner)


;
; Multi-table bank test
;

(defrecord YSQLMultiBankClientInner [allow-negatives?]
  c/YSQLClientBase

  (setup-cluster! [this test c conn-wrapper]

    (doseq [a (:accounts test)]
      (let [acc-table-name (str table-name a)
            balance        (if (= a (first (:accounts test)))
                             (:total-amount test)
                             0)]
        (info "Creating table" a)
        (c/execute! c (j/create-table-ddl acc-table-name [[:id :int "PRIMARY KEY"]
                                                          [:balance :bigint]]))

        (info "Populating account" a " (balance =" balance ")")
        (c/with-retry
          (c/insert! c acc-table-name {:id      a
                                       :balance balance})))))


  (invoke-inner! [this test op c conn-wrapper]
    (case (:f op)
      :read
      (c/with-txn
        c
        (let [accs (shuffle (:accounts test))]
          (->> accs
               (mapv (fn [a]
                       (c/select-single-value c (str table-name a) :balance (str "id = " a))))
               (zipmap accs)
               (assoc op :type :ok, :value))))

      :transfer
      (let [{:keys [from to amount]} (:value op)]
        (c/with-txn
          c
          (let [b-from-before (c/select-single-value c (str table-name from) :balance (str "id = " from))
                b-to-before   (c/select-single-value c (str table-name to) :balance (str "id = " to))
                b-from-after  (- b-from-before amount)
                b-to-after    (+ b-to-before amount)
                allowed?      (or allow-negatives? (pos? b-from-after))]
            (if (not allowed?)
              (assoc op :type :fail, :error [:negative from b-from-after])
              (do (c/update! c (str table-name from) {:balance b-from-after} ["id = ?" from])
                  (c/update! c (str table-name to) {:balance b-to-after} ["id = ?" to])
                  (assoc op :type :ok))))))))


  (teardown-cluster! [this test c conn-wrapper]
    (doseq [a (:accounts test)]
      (c/drop-table c (str table-name a)))))


(c/defclient YSQLMultiBankClient YSQLMultiBankClientInner)
