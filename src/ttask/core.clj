(ns ttask.core
  (:require [clojure.core.async :as a]
            [clojure.tools.cli :as cli]
            [clojure.string :as cs]
            [clojure.data.zip.xml :as zx]
            [clojure.xml :as x]
            [clojure.zip :as zip]
            [taoensso.timbre :as logger]
            [net.cgrand.enlive-html :as html]
            [clojure.java.io :as io]
            [clj-http.client :as http])
  (:import java.io.ByteArrayInputStream
           java.nio.charset.StandardCharsets))

(def ^:dynamic *user* nil)
(def ^:dynamic *api-key* nil)

(def cli-options-spec
  [["-u" "--username USERNAME" "yandex.xml user name"
    :default "leeqleeq"
    :validate [not-empty "api user name shouldn't be empty"]]
   ["-k" "--api-key API-KEY" "yandex.xml api key"
    :default "03.326917787:57988d4de9fde13937d0f15b2d4f50fb"
    :validate [not-empty "api key shouldn't be empty"]]
   ["-r" "--requests REQUESTS" "max simultaneous requests count"
    :default 4
    :parse-fn #(Byte/parseByte %)
    :validate [pos? "number of requests should be positive"]]
   ["-o" "--output OUTPUT" "output folder"
    :default "./search_results"]
   ["-w" "--words WORDS" "search terms [comma separated]"
    :default ["clojure", "scala"]
    :parse-fn #(filter (comp pos? count) (cs/split % #"\s*,\s*"))
    :validate [not-empty "search terms shouldn't be empty"]]])

(defn string-to-input-stream [s]
  (ByteArrayInputStream. (.getBytes s (StandardCharsets/UTF_8))))

(defn get-meta-tags [url]
  (let [tags (-> url
                 java.net.URL.
                 html/html-resource
                 (html/select [[:meta (html/attr? :name :content)]]))]
    (into {} (map (comp (juxt :name :content) :attrs) tags))))

(defn handle-search-result [out-channel {:keys [word data]}]
  (doseq [[url description] data]
    (logger/info "retrieving metadata from" url)
    (a/go (a/>! out-channel {:url url
                             :word word
                             :description description
                             :meta-tags (get-meta-tags url)}))))

(defn parse-response [resp-body]
  (let [resp-xml (-> resp-body
                     string-to-input-stream
                     x/parse
                     zip/xml-zip
                     (zx/xml1-> :response))]
    (if-let [error (zx/xml1-> resp-xml :error zx/text)]
      (throw (Exception. error))
      (map (juxt #(zx/xml1-> % :url zx/text)
                 #(zx/xml1-> % :passages :passage zx/text))
           (zx/xml-> resp-xml :results :grouping :group :doc)))))

(defn search [_ word parse-fn]
  (let [result (http/get
                "https://yandex.ru/search/xml"
                {:query-params {:query word
                                :user *user*
                                :key *api-key*
                                :i10n "ru"
                                :sortby "tm.order=ascending"
                                :filter "none"
                                :groupby "attr=\"\".mode=flat.groups-on-page=60.docs-in-group=1"}})]
    {:word word
     :data (parse-fn (:body result))}))

(defn search-all [words agent-pool-size output-channel]
  (let [agents (cycle (repeatedly agent-pool-size
                                  #(agent nil
                                          :error-mode :continue
                                          :error-handler (fn [_ e] (logger/warn e)))))]
    (dorun (map (fn [ag word]
                  (-> ag
                      (add-watch :result
                                 (fn [_ _ _ data] (handle-search-result
                                                    output-channel
                                                    data)))
                      (send-off search word parse-response)))
                agents
                words))))

(defn save-result-to-file [output-dir word url description meta-tags]
  (let [f (io/file output-dir (str word ".edn"))]
    (io/make-parents f)
    (spit f (str {:url url
                  :description description
                  :meta-tags meta-tags}
                 \newline)
          :append true)))

(defn -main [& args]
  (let [cli-parse-result (cli/parse-opts args cli-options-spec)]
    (if-let [error (:errors cli-parse-result)]
      (logger/warn (Exception. (str error)))
      (let [search-result-channel (a/chan)
            options (:options cli-parse-result)]
        (logger/debug "running search task with params:" options)
        (a/go-loop []
          (let [{:keys [url word description meta-tags]} (a/<! search-result-channel)]
            ;(logger/debug "found meta tags for" word url meta-tags)
            (save-result-to-file (:output options)
                                 word url description meta-tags))
          (recur))
        (binding [*user* (:username options)
                  *api-key* (:api-key options)]
          (search-all (:words options) (:requests options) search-result-channel))))))