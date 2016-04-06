(ns leiningen.dash.docset.generator
  (:require [clojure.java.io :as io]
            [leiningen.dash.docset.codox.parser :as p :refer :all]
            [net.cgrand.enlive-html :as enlive]
            [yesql.core :refer [defqueries]]
            [clojure.string :as s])
  (:import [java.io File]
           [org.apache.commons.io.filefilter FileFilterUtils
                                             IOFileFilter
                                             NameFileFilter
                                             NotFileFilter
                                             TrueFileFilter
                                             WildcardFileFilter]
           [org.apache.commons.io FilenameUtils
                                  FileUtils
                                  IOUtils]))

(defqueries "docset.sql")

(defn db-opts [db]
  {:connection {:classname "org.sqlite.JDBC"
                :subprotocol "sqlite"
                :subname (.getPath db)}})

(def write [path & chunks]
  (with-open [f (io/writer path)]
    (doseq [chunk chunks] (.write f chunk))))

(defn html-files
  "Get all the non-index HTML files at a given base."
  [base-dir]
  (filter (fn [f]
            (let [name (.getName f)]
              (and (s/ends-with? name "html")
                   (not (s/ends-with? name "index.html")))))
          (file-seq base-dir)))

(defn parse-file [^File html-file]
  (let [nodes (enlive/html-resource html-file)]
    (map (fn [node]
           (update (p/some-info node)
                   :path
                   (fn [id] (str (.getName html-file) id))))
         (enlive/select nodes [[:div :#content] :.anchor]))))

(defn create-docset-structure [{:keys [name version]}]
  (let [docset-dir (-> "%s-%s.docset/Contents/Resources/Documents"
                       (format name version)
                       (io/file))]
    (.mkdirs docset-dir)
    docset-dir))

(defn copy-docs [docset-dir doc-base-dir]
  (FileUtils/copyDirectory doc-base-dir docset-dir)
  docset-dir)

(defn create-plist [docset-dir project]
  (let [project-name (:name project)
        plist-path (io/file (.getPath docset-dir) ".." ".." "Info.plist")
        plist-tpl (slurp (io/resource "Info.plist"))]
    (write plist-path (format plist-tpl project-name project-name "clojure"))
    docset-dir))

(defn create-db [docset-dir]
  (let [db (io/file (.getPath docset-dir) ".." "docSet.dsidx")
        opts (db-opts db)]
    (.delete db)
    (create-table! {} opts)
    (create-index! {} opts)
    db))

(defn process-info [db-path infos]
  (doseq [i infos]
    (insert-info! (select-keys i [:name :type :path]) (db-opts db-path)))
  db-path)

(def ^:private scrub
  (enlive/do-> (enlive/content "") (enlive/remove-attr :id :class)))

(defn transform-docset-html
  "Clean up the Codox documentation so it looks properly in Dash."
  [docset-dir]
  (doseq [file (html-files docset-dir)]
    (as-> (enlive/html-resource file) nodes
      (enlive/at nodes
                 [:#namespaces] scrub
                 [:#header] scrub
                 [:#vars] scrub)
      (write file (enlive/emit* nodes))))
  docset-dir)
