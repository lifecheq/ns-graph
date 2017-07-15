(ns leiningen.nomis-ns-graph
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [leiningen.nomis-ns-graph.args :as args]
            [clojure.string :as str]
            [clojure.tools.namespace.dependency :as ctns-dep]
            [clojure.tools.namespace.file :as ctns-file]
            [clojure.tools.namespace.find :as ctns-find]
            ;; [clojure.tools.namespace.parse :as ctns-parse]
            [clojure.tools.namespace.track :as ctns-track]
            [leiningen.core.main :as lcm]
            [rhizome.dot :as dot]
            [rhizome.viz :as viz]
            [slingshot.slingshot :refer [throw+ try+]])
  (:import [java.io PushbackReader]))

;;;; ___________________________________________________________________________

(defn ^:private ns-symbol->pieces [sym]
  (as-> sym __
    (name __)
    (str/split __ #"\.")
    (map symbol __)))

(defn ^:private ns-symbol->last-piece [sym]
  (-> sym
      ns-symbol->pieces
      last))

(defn ^:private pieces->ns-symbol [pieces]
  (->> pieces
       (str/join ".")
       symbol))

(defn ^:private ns-symbol->parent-ns-symbol [sym]
  (as-> sym __
    (name __)
    (str/split __ #"\.")
    (butlast __)
    (str/join "." __)
    (if (= __ "")
      nil
      (symbol __))))

(defn ^:private ns-symbol->all-parent-ns-symbols-incl-self [sym]
  (as-> sym __
    (name __)
    (str/split __ #"\.")
    (iterate butlast __)
    (take-while (comp not nil?)
                __)
    (map pieces->ns-symbol __)))

(defn ^:private ns-symbols->all-ns-symbols [ns-names]
  (apply set/union
         (map (comp set ns-symbol->all-parent-ns-symbols-incl-self)
              ns-names)))

(defn ^:private dups [seq]
  (for [[id freq] (frequencies seq)
        :when (> freq 1)]
    id))

(defn sym->style-map [part-of-project? sym]
  {:style (if (part-of-project? sym)
            :solid
            :dashed)})

(defn ^:private ns-graph-spec->dot-data [{:keys [platform
                                                 source-paths
                                                 exclusions
                                                 show-non-project-deps
                                                 project-group
                                                 project-name]
                                          :as ns-graph-spec}]
  (let [platform-for-ns (case platform
                          :clj ctns-find/clj
                          :cljs ctns-find/cljs)
        source-files (apply set/union
                            (map (comp #(ctns-find/find-sources-in-dir %
                                                                       platform-for-ns)
                                       io/file)
                                 source-paths))
        tracker (ctns-file/add-files {} source-files)
        dep-graph (tracker ::ctns-track/deps)
        ns-names (set (map (comp second ctns-file/read-file-ns-decl)
                           source-files))
        ns-names-with-parents (ns-symbols->all-ns-symbols ns-names)
        part-of-project? (partial contains? ns-names-with-parents)
        include-node? (fn [sym]
                        (let [exclusion-fns (for [s exclusions]
                                              #(str/starts-with? % s))
                              extra-exclusion-fn (comp
                                                  not
                                                  (if show-non-project-deps
                                                    (constantly true)
                                                    part-of-project?))
                              exclusions+ (cons extra-exclusion-fn
                                                exclusion-fns)]
                          (not ((apply some-fn exclusions+)
                                sym))))
        leaf-nodes (filter include-node? (ctns-dep/nodes dep-graph))
        nodes (ns-symbols->all-ns-symbols leaf-nodes)
        symbol->descriptor (fn [sym color]
                             (let [color color]
                               (merge (sym->style-map part-of-project?
                                                      sym)
                                      {:label (ns-symbol->last-piece sym)
                                       :color color
                                       :fontcolor color})))
        dot-data (dot/graph->dot
                  nodes
                  #(filter include-node? (ctns-dep/immediate-dependencies dep-graph %))
                  :node->descriptor #(symbol->descriptor % :black)
                  :edge->descriptor #(sym->style-map part-of-project?
                                                     %2)
                  :options {:dpi 72}
                  :do-not-show-clusters-as-nodes? true
                  :cluster->descriptor #(symbol->descriptor %
                                                            (case 1 ; for easy dev/debug
                                                              1 :blue
                                                              2 :red
                                                              3 :purple))
                  :node->cluster ns-symbol->parent-ns-symbol
                  :cluster->parent ns-symbol->parent-ns-symbol
                  :left-justify-cluster-labels? true
                  :title (str (str (str project-group "/" project-name)
                                   " namespace dependencies")
                              (str "\\l:platform: " (name platform))
                              (str "\\l:source-paths: "
                                   (letfn [(fix-slashes [s]
                                             (str/replace s "\\" "/"))]
                                     (let [root-path (str (-> (clojure.java.io/file ".")
                                                              .getCanonicalPath
                                                              fix-slashes)
                                                          "/")]
                                       (str/join " "
                                                 (->> source-paths
                                                      (map fix-slashes)
                                                      (map #(str/replace-first %
                                                                               root-path
                                                                               "")))))))
                              (when show-non-project-deps 
                                "\\l:show-non-project-deps true")
                              (when-not (empty? exclusions)
                                (str "\\l:exclusions:\\l"
                                     (apply str
                                            (str/join "\\l"
                                                      (map (partial str "    ")
                                                           exclusions)))))
                              "\\l"))]
    dot-data))

;;;; ___________________________________________________________________________

(defn ^:private add-png-extension [name]
  (str name ".png"))

(defn ^:private add-gv-extension [name]
  (str name ".gv"))

(defn ^:private nomis-ns-graph* [project & args]
  (let [options (args/make-options args)
        {:keys [filename
                platform
                source-paths
                show-non-project-deps
                exclusions
                write-gv-file?]} options
        source-paths (or source-paths
                         (case platform
                           :clj (-> project
                                    :source-paths)
                           :cljs (let [assumed-cljs-source-paths ["src/cljs"
                                                                  "cljs/src"]]
                                   (lcm/info "Assuming cljs source paths ="
                                             assumed-cljs-source-paths
                                             "(you can override this with the :source-paths option).")
                                   assumed-cljs-source-paths)))
        ns-graph-spec {:platform              platform
                       :source-paths          source-paths
                       :exclusions            exclusions
                       :show-non-project-deps show-non-project-deps
                       :project-group         (:group project)
                       :project-name          (:name project)}
        dot-data (ns-graph-spec->dot-data ns-graph-spec)]
    (when write-gv-file?
      (let [gv-filename (add-gv-extension filename)]
        (spit gv-filename
              dot-data)
        (lcm/info "Created" gv-filename)))
    (let [png-filename (add-png-extension filename)]
      (-> dot-data
          viz/dot->image
          (viz/save-image png-filename))
      (lcm/info "Created" png-filename))))

(defn nomis-ns-graph
  "Create a namespace dependency graph and save it."
  [project & args]
  (try+ (apply nomis-ns-graph*
               project
               args)
        (catch [:type :nomis-ns-graph/exception] {:keys [message]}
          (lcm/warn "Error:" message)
          (lcm/exit 1))))
