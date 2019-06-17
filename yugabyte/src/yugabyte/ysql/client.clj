(ns yugabyte.ysql.client
  "Helper functions for working with YSQL clients."
  (:require [clojure.java.jdbc :as j]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clojure.tools.logging :refer [info warn]]
            [jepsen.util :as util]
            [jepsen.control.net :as cn]
            [jepsen.client :as client]
            [jepsen.reconnect :as rc]
            [dom-top.core :as dt]
            [wall.hack :as wh]
            [slingshot.slingshot :refer [try+ throw+]]))

(def default-timeout "Default timeout for operations in ms" 30000)

(def isolation-level "Default isolation level for txns" :serializable)

(def ysql-port 5433)

(defn db-spec
  "Assemble a JDBC connection specification for a given Jepsen node."
  [node]
  {:dbtype         "postgresql"
   :dbname         "postgres"
   :classname      "org.postgresql.Driver"
   :host           (name node)
   :port           ysql-port
   :user           "postgres"
   :password       ""
   :loginTimeout   (/ default-timeout 1000)
   :connectTimeout (/ default-timeout 1000)
   :socketTimeout  (/ default-timeout 1000)})

(defn query
  "Like jdbc query, but includes a default timeout in ms.
  Requires query to be wrapped in a vector."
  [conn sql-params]
  (j/query conn sql-params {:timeout default-timeout}))

(defn insert!
  "Like jdbc insert!, but includes a default timeout."
  [conn table values]
  (j/insert! conn table values {:timeout default-timeout}))

(defn update!
  "Like jdbc update!, but includes a default timeout."
  [conn table values where]
  (j/update! conn table values where {:timeout default-timeout}))

(defn execute!
  "Like jdbc execute!!, but includes a default timeout."
  [conn sql-params]
  (j/execute! conn sql-params {:timeout default-timeout}))

(defn select-single-value
  "Selects a single value from table with a WHERE-clause yielding single row"
  [conn table-name column-kw where-clause]
  (let [query-string (str "SELECT " (name column-kw) " FROM " table-name " WHERE " where-clause)
        query-res    (query conn query-string)
        res          (get (first query-res) column-kw)]
    res))

(defn in
  "Constructs an SQL IN clause string"
  [coll]
  (assert (not-empty coll) "Cannot create IN clause for empty values collection")
  (str "IN (" (str/join ", " coll) ")"))

(defn close-conn
  "Given a JDBC connection, closes it and returns the underlying spec."
  [conn]
  (when-let [c (j/db-find-connection conn)]
    (.close c))
  (dissoc conn :connection))

(defn conn-wrapper
  "Constructs a network client for a node, and opens it"
  [node]
  (rc/open!
    (rc/wrapper
      {:name  node
       :open  (fn open []
                (util/timeout default-timeout
                              (throw (RuntimeException.
                                       (str "Connection to " node " timed out")))
                              (util/retry 0.1
                                          (let [spec  (db-spec node)
                                                conn  (j/get-connection spec)
                                                spec' (j/add-connection spec conn)]
                                            (assert spec')
                                            spec'))))
       :close close-conn
       :log?  true})))

(defprotocol YSQLClientBase
  "Used by defclient macro in conjunction with jepsen.client/Client specifying actual logic"
  (setup-cluster! [client test c conn-wrapper]
    "Called once on a random node to set up database/cluster state for testing.")
  (invoke-inner! [client test operation c conn-wrapper]
    "Apply an operation to the client, returning an operation to be appended to the history.
    This function is wrapped in with-errors, with-conn and with-retry")
  (teardown-cluster! [client test c conn-wrapper]
    "Called once on a random node to tear down the client/database/cluster when work is complete.
    This function is wrapped in with-timeout, with-conn and with-retry"))

(defn exception-to-op
  "Takes an exception and maps it to a partial op, like {:type :info, :error
  ...}. nil if unrecognized."
  [e]
  (when-let [m (.getMessage e)]
    (condp instance? e
      java.sql.SQLTransactionRollbackException

      {:type :fail, :error [:rollback m]}

      ; So far it looks like all SQL exception are wrapped in BatchUpdateException
      java.sql.BatchUpdateException
      (if (re-find #"getNextExc" m)
        ; Wrap underlying exception error with [:batch ...]
        (when-let [op (exception-to-op (.getNextException e))]
          (update op :error (partial vector :batch)))
        {:type :info, :error [:batch-update m]})

      org.postgresql.util.PSQLException
      (condp re-find (.getMessage e)
        #"(?i)Conflicts with [- a-z]+ transaction"
        {:type :fail, :error [:conflicting-transaction m]}

        #"(?i)Catalog Version Mismatch"
        {:type :fail, :error [:catalog-version-mismatch m]}

        ; Happens upon concurrent updates even without explicit transactions
        #"(?i)Operation expired"
        {:type :fail, :error [:operation-expired m]}

        {:type :info, :error [:psql-exception m]})

      ; Happens when with-conn macro detects a closed connection
      clojure.lang.ExceptionInfo
      (condp = (:type (ex-data e))
        :conn-not-ready {:type :fail, :error :conn-not-ready}
        nil)

      (condp re-find m
        #"^timeout$"
        {:type :info, :error :timeout}

        nil))))

(defn retryable?
  "Whether given exception indicates that an operation can be retried"
  [ex]
  (let [op     (exception-to-op ex)                         ; either {:type ... :error ...} or nil
        op-str (str op)]
    (re-find #"(?i)try again" op-str)))

(defmacro once-per-cluster
  "Runs the given code once per cluster. Requires an atomic boolean (set to false)
  shared across clients.
  This is needed mainly because concurrent DDL is not supported and results in an error."
  [atomic-bool & body]
  `(locking ~atomic-bool
     (when (compare-and-set! ~atomic-bool false true) ~@body)))

(defn drop-table
  ([c table-name]
   (drop-table c table-name false))
  ([c table-name if-exists?]
   (execute! c (str "DROP TABLE " (if if-exists? "IF EXISTS " "") table-name))))

(defn drop-index
  [c index-name]
  (execute! c (str "DROP INDEX " index-name)))

(defmacro with-conn
  "Like jepsen.reconnect/with-conn, but also asserts that the connection has
  not been closed. If it has, throws an ex-info with :type :conn-not-ready.
  Delays by 1 second to allow time for the DB to recover."
  [[c conn-wrapper] & body]
  `(rc/with-conn [~c ~conn-wrapper]
                 (when (.isClosed (j/db-find-connection ~c))
                   (Thread/sleep 1000)
                   (throw (ex-info "Connection not yet ready."
                                   {:type :conn-not-ready})))
                 ~@body))

(defn with-idempotent
  "Takes a predicate on operation functions, and an op, presumably resulting
  from a client call. If (idempotent? (:f op)) is truthy, remaps :info types to
  :fail."
  [idempotent? op]
  (if (and (idempotent? (:f op)) (= :info (:type op)))
    (assoc op :type :fail)
    op))

(defmacro with-timeout
  "Like util/timeout, but throws (RuntimeException. \"timeout\") for timeouts.
  Throwing means that when we time out inside a with-conn, the connection state
  gets reset, so we don't accidentally hand off the connection to a later
  invocation with some incomplete transaction."
  [& body]
  `(util/timeout default-timeout
                 (throw (RuntimeException. "timeout"))
                 ~@body))

(defmacro with-retry
  "Catches YSQL \"try again\"-style errors and retries body a bunch of times,
  with exponential backoffs."
  [& body]
  `(util/with-retry [attempts# 30
                     backoff# 20]
                    ~@body

                    (catch java.sql.SQLException e#
                      (if (and (pos? attempts#)
                               (retryable? e#))
                        (do (Thread/sleep backoff#)
                            (~'retry (dec attempts#)
                              (* backoff# (+ 4 (* 0.5 (- (rand) 0.5))))))
                        (throw e#)))))

(defmacro with-txn
  "Wrap evaluation within an SQL transaction using the default isolation level."
  [c & body]
  `(j/with-db-transaction [~c ~c {:isolation isolation-level}]
                          ~@body))


(defmacro with-errors
  "Takes an operation and a body. Evaluates body, catches exceptions, and maps
  them to ops with :type :info and a descriptive :error."
  [op & body]
  `(try ~@body
        (catch Exception e#
          (if-let [ex-op# (exception-to-op e#)]
            (merge ~op ex-op#)
            (throw e#)))))

(defn assert-involves-index
  "Verifies that executing given query uses index with the given name"
  [c query-str index-name]
  (let [explanation     (query c (str "EXPLAIN " query-str))
        explanation-str (pr-str explanation)]
    (assert
      (.contains explanation-str index-name)
      (str "Query '" query-str "' does not involve index '" index-name "'!"
           " Query explanation: \n" explanation-str))))

; Should probably be the last definition in order to have access to everything else
(defmacro defclient
  "Helper for defining YSQL clients.
  Takes a new class name symbol and a definition of YSQLClientBase record
  whose methods will be wrapped and called.
  This approach is (arguably) cleaner than the one used for YCQL defclient.

  Example:

    (defrecord YSQLMyClientInner [arg1 arg2 arg3]
      c/YSQLClientBase

      (setup-cluster! [this test c conn-wrapper]
        (do-stuff-once-with c))

      (invoke-inner! [this test op c conn-wrapper]
        (case (:f op)
          ...))

      (teardown-cluster! [this test c conn-wrapper]
        (c/drop-table c \"my-table\"))


    (c/defclient YSQLMyClient YSQLMyClientInner)


    ; To create a client instance:
    (->YSQLMyClient arg1 arg2 arg3)
    "
  [class-name inner-client-record]

  ; Since this macro is insanely complex, I'll try to thoroughly comment what's happening here.
  ; Good luck.

  ; First, before we start with the actual macro output, we analyze inner-client-record constructor
  ; in order to get a grip on its arguments list.
  ; For that we're doing a bunch of string manipulations with the inner-client-record's symbol name.

  (let [inner-ctor-ns-prefix (if (qualified-symbol? inner-client-record)
                               (str (namespace inner-client-record) "/")
                               "")
        inner-ctor-sym       (symbol (str inner-ctor-ns-prefix "->" (name inner-client-record)))
        inner-ctor-meta      (meta (resolve inner-ctor-sym))
        inner-ctor-args-vec  (first (:arglists inner-ctor-meta))]

    ; Now we're getting to the output.
    ; We define a record with a given name extending jepsen.client/Client, which takes an
    ; instance of inner-client (among other things) and delegates logic to it, wrapping it into
    ; helper methods.

    `(do (defrecord ~class-name [~'conn-wrapper ~'inner-client ~'setup? ~'teardown?]
           client/Client

           (open! [~'this ~'test ~'node]
             (assoc ~'this :conn-wrapper (conn-wrapper ~'node)))

           (setup! [~'this ~'test]
             (once-per-cluster
               ~'setup?
               (info "Running setup")
               (with-conn
                 [~'c ~'conn-wrapper]
                 (setup-cluster! ~'inner-client ~'test ~'c ~'conn-wrapper))))

           (invoke! [~'this ~'test ~'op]
             (with-errors
               ~'op
               (with-conn
                 [~'c ~'conn-wrapper]
                 (with-retry
                   (invoke-inner! ~'inner-client ~'test ~'op ~'c ~'conn-wrapper)))))

           (teardown! [~'this ~'test]
             (once-per-cluster
               ~'teardown?
               (info "Running teardown")
               (with-timeout
                 (with-conn
                   [~'c ~'conn-wrapper]
                   (with-retry
                     (teardown-cluster! ~'inner-client ~'test ~'c ~'conn-wrapper))))))

           (close! [~'this ~'test]
             (rc/close! ~'conn-wrapper)))

         ; Lastly, we redefine a ->Constructor helper for the newfound record, forcing it to take
         ; the same arguments as inner-client-record.
         ; We use those to construct inner-client-record and pass it to the
         ; newfound record constructor.

         (defn ~(symbol (str "->" class-name))
           ~inner-ctor-args-vec
           (let [~'inner-client (new ~inner-client-record ~@inner-ctor-args-vec)]
             (new ~class-name nil ~'inner-client (atom false) (atom false)))))
    ))
