(ns hf.depstar.uberjar
  (:require [clojure.edn :as edn]
            [clojure.java.io :as jio])
  (:import [java.io InputStream OutputStream PushbackReader]
           [java.nio.file CopyOption LinkOption OpenOption
                          StandardCopyOption StandardOpenOption
                          FileSystem FileSystems Files
                          FileVisitResult FileVisitor
                          Path]
           [java.nio.file.attribute BasicFileAttributes FileAttribute]
           [java.util.jar JarInputStream JarOutputStream JarEntry]))

;; future:
;; other knobs?
;; clj -M options?
;; look into MANIFEST entries

(defonce ^FileSystem FS (FileSystems/getDefault))

(defn path
  ^Path [s]
  (.getPath FS s (make-array String 0)))

(defn clash-strategy
  [filename]
  (cond
    (= "data_readers.clj" filename)
    :merge-edn

    (re-find #"^META-INF/services/" filename)
    :concat-lines

    :else
    :noop))

(defmulti clash (fn [filename in target]
                  (prn {:warning "clashing jar item" :path filename})
                  (clash-strategy filename)))

(defmethod clash
  :merge-edn
  [_ in target]
  (let [er #(with-open [r (PushbackReader. %)] (edn/read r))
        f1 (er (jio/reader in))
        f2 (er (Files/newBufferedReader target))]
    (with-open [w (Files/newBufferedWriter target (make-array OpenOption 0))]
      (binding [*out* w]
        (prn (merge f1 f2))))))

(defmethod clash
  :concat-lines
  [_ in target]
  (let [f1 (line-seq (jio/reader in))
        f2 (Files/readAllLines target)]
    (with-open [w (Files/newBufferedWriter target (make-array OpenOption 0))]
      (binding [*out* w]
        (run! println (-> (vec f1)
                          (conj "\n")
                          (into f2)))))))

(defmethod clash
  :default
  [_ in target])
  ;; do nothing, first file wins

(defn excluded?
  [filename]
  (or (#{"project.clj"
         "LICENSE"
         "COPYRIGHT"} filename)
      (re-matches #"(?i)META-INF/.*\.(?:MF|SF|RSA|DSA)" filename)
      (re-matches #"(?i)META-INF/(?:INDEX\.LIST|DEPENDENCIES|NOTICE|LICENSE)(?:\.txt)?" filename)))

(def clojure-extensions (list ".clj" ".cljc"))

(defn- clojure-source?
  [filename]
  (some #(.endsWith filename %) clojure-extensions))

(defn copy!
  ;; filename drives strategy
  [filename ^InputStream in ^Path target & [last-mod]]
  (when-not (or (excluded? filename) (clojure-source? filename))
    (if (Files/exists target (make-array LinkOption 0))
      (clash filename in target)
      (do
        (Files/copy in target ^"[Ljava.nio.file.CopyOption;" (make-array CopyOption 0))
        (when last-mod
          (Files/setLastModifiedTime target last-mod))))))

(defn consume-jar
  [^Path path f]
  (with-open [is (-> path
                     (Files/newInputStream (make-array OpenOption 0))
                     java.io.BufferedInputStream.
                     JarInputStream.)]
    (loop []
      (when-let [entry (try (.getNextJarEntry is) (catch Exception _))]
        (f is entry)
        (recur)))))

(defn classify
  [entry]
  (let [p (path entry)
        symlink-opts (make-array LinkOption 0)]
    (if (Files/exists p symlink-opts)
      (cond
        (Files/isDirectory p symlink-opts)
        :directory

        (and (Files/isRegularFile p symlink-opts)
             (re-find #"\.jar$" (.toString p)))
        :jar

        :else :unknown)
      :not-found)))

(defmulti copy-source*
  (fn [src dest options]
    (classify src)))

(defmethod copy-source*
  :jar
  [src dest options]
  (when-not (= :thin (:jar options))
    (consume-jar (path src)
      (fn [inputstream ^JarEntry entry]
        (let [name (.getName entry)
              last-mod (.getLastModifiedTime entry)
              target (.resolve ^Path dest name)]
          (if (.isDirectory entry)
            (Files/createDirectories target (make-array FileAttribute 0))
            (do (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
                (copy! name inputstream target last-mod))))))))

(defn copy-directory
  [^Path src ^Path dest]
  (let [copy-dir
        (reify FileVisitor
          (visitFile [_ p attrs]
            (let [f (.relativize src p)]
              (with-open [is (Files/newInputStream p (make-array OpenOption 0))]
                (copy! (.toString f) is (.resolve dest f))))
            FileVisitResult/CONTINUE)
          (preVisitDirectory [_ p attrs]
            (Files/createDirectories (.resolve dest (.relativize src p))
                                     (make-array FileAttribute 0))
            FileVisitResult/CONTINUE)
          (postVisitDirectory [_ p ioexc]
            (if ioexc (throw ioexc) FileVisitResult/CONTINUE))
          (visitFileFailed [_ p ioexc] (throw (ex-info "Visit File Failed" {:p p} ioexc))))]
    (Files/walkFileTree src copy-dir)
    :ok))

(defmethod copy-source*
  :directory
  [src dest options]
  (copy-directory (path src) dest))

(defmethod copy-source*
  :not-found
  [src _dest _options]
  (prn {:warning "could not find classpath entry" :path src}))

(defn copy-source
  [src dest options]
  (copy-source* src dest options))

(defn write-jar
  [^Path src ^Path target]
  (with-open [os (-> target
                     (Files/newOutputStream (make-array OpenOption 0))
                     JarOutputStream.)]
    (let [walker (reify FileVisitor
                   (visitFile [_ p attrs]
                     (let [t (.lastModifiedTime attrs)
                           e (JarEntry. (.toString (.relativize src p)))]
                       (.putNextEntry os (.setLastModifiedTime e t)))
                     (Files/copy p os)
                     FileVisitResult/CONTINUE)
                   (preVisitDirectory [_ p attrs]
                     (when (not= src p) ;; don't insert "/" to zip
                       (.putNextEntry os (JarEntry. (str (.relativize src p) "/")))) ;; directories must end in /
                     FileVisitResult/CONTINUE)
                   (postVisitDirectory [_ p ioexc]
                     (if ioexc (throw ioexc) FileVisitResult/CONTINUE))
                   (visitFileFailed [_ p ioexc] (throw ioexc)))]
      (Files/walkFileTree src walker)))
  :ok)

(defn current-classpath
  []
  (vec (.split ^String
               (System/getProperty "java.class.path")
               (System/getProperty "path.separator"))))

(defn depstar-itself?
  [p]
  (re-find #"depstar" p))

(defn run
  [{:keys [dest jar aot-list] :or {jar :uber} :as options}]
  (let [tmp (Files/createTempDirectory "uberjar" (make-array FileAttribute 0))
        cp (into [] (remove depstar-itself?) (current-classpath))
        target (path dest)]
    (run! #(copy-source % tmp options) cp)
    (binding [*compile-path* (.toString tmp)]
      (run! #(println "AOT Compile:" %) aot-list)
      (run! (comp compile symbol) aot-list)
      (compile 'clojure.tools.reader)
      (compile 'clojure.tools.reader.reader-types)
      (compile 'clojure.tools.reader.impl.utils)
      (compile 'clojure.tools.reader.impl.errors)
      (compile 'clojure.tools.reader.impl.inspect)
      (compile 'clojure.tools.reader.impl.commons)
      (compile 'clojure.tools.reader.default-data-readers)
      (compile 'clojure.tools.reader.edn))
    (println "Writing" (name jar) "jar:" dest)
    (.mkdirs (.toFile (.getParent target)))
    (write-jar tmp target)))

(defn -main
  [destination & aot-list]
  (run {:dest destination
        :aot-list aot-list}))
