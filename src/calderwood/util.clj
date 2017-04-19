(ns calderwood.util
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [bultitude.core :as bultitude]
            [clojure.string :as string]
            [clojurewerkz.scrypt.core :as scrypt]
            [clj-time.coerce :as time.coerce]
            [clj-time.core :as time.core]
            [clj-time.format :as time.format]
            [datomic.api :as d]
            [lib.cpath-clj.core :as cpath])
  (:import [java.util UUID]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; UUIDs

(defn random-uuid []
  (UUID/randomUUID))

(defn uuid [^String s]
  (UUID/fromString s))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Password Encryption

(defn hash-password [password]
  (scrypt/encrypt password 16384 8 1))

(defn password-hash-valid? [password hash]
  (scrypt/verify password hash))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Loading EDN resources

(defn load-text-resource [file-path]
  (try
    (-> file-path
        io/resource
        slurp)
    (catch Exception e
      (throw (Exception. (str "Could not find resource file at path: " file-path))))))

(defn load-edn-resource [file-path]
  (edn/read-string {:readers *data-readers*} (load-text-resource file-path)))

(defn edn-file-paths-in-resources-dir [dir-name]
  (->> (cpath/resources dir-name)
       keys
       (filter (partial re-find #"\.edn$"))
       sort
       (map #(str dir-name %))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Finding Clojure Files in resources

(defn load-clj-files-in-resources-dir [dir-name]
  (let [clj-file-paths (->> (cpath/resources dir-name)
                            keys
                            (filter (partial re-find #"\.clj$"))
                            sort
                            (mapv #(str dir-name %)))]
    (doseq [cfp clj-file-paths]
      (load-file (.getPath (io/resource cfp))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Finding Namespaces

(defn nses-on-cp [prefix]
  (bultitude/namespaces-on-classpath :prefix prefix))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Loading Migration Transactions

(defn get-migration-txes []
  (->> (edn-file-paths-in-resources-dir "migrations")
      (mapv load-edn-resource)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Loading Seed Transactions

(defn get-seed-txses [db]
  (let [dir-name "seed"]
    (load-clj-files-in-resources-dir dir-name)
    (->> (nses-on-cp dir-name)
         (mapv (fn [ns*]
                 (let [txes* (get (ns-publics ns*) 'txes)]
                   (txes* db)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Reading EDN from an InputStream

(defn input-stream->edn [is]
  (clojure.edn/read
   {:eof nil}
   (java.io.PushbackReader.
    (java.io.InputStreamReader. is "UTF-8"))))
