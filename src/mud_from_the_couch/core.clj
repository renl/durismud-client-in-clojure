(ns mud-from-the-couch.core
  (:require [lanterna.terminal :as t]
            [seesaw.core :as s]
            [seesaw.dev :as d]
            [mud-from-the-couch.guirender :refer [render-messagebox
                                            render-statusbox
                                            render-exitsbox
                                            render-objectsbox
                                            render-selected-objectbox]]
            [mud-from-the-couch.ansicode :refer [generate-markup
                                                 parse-markup
                                                 remove-ansi-escape-code]]
            [clojure.core.async :refer [<! >! <!! >!! chan go close!]])
  (:import [org.apache.commons.net.telnet
            TelnetClient
            TerminalTypeOptionHandler
            EchoOptionHandler
            SuppressGAOptionHandler]
           [net.java.games.input ControllerEnvironment])
  (:gen-class))

(declare parse-cmd cmd-chan term-output prev-obj prev-word next-obj next-word)


;; Async channel
;; =============================================================================
(def screen-chan (chan))
(def key-stroke-chan (chan))
(def cmd-chan (chan))
(def trigger-chan (chan))
(def render-chan (chan))
(def gamepad-chan (chan))


;; App states
;; =============================================================================
(def interface-mode (atom :input-key))
(def os-type (atom nil))
(def running (atom true))
(def key-buffer (atom ""))
(def cmd-buffer (atom {:history '()
                       :pointer 1}))
(def in-buf (byte-array 1024))
(def screen-buffer (atom []))
(def tab-complete-list (atom #{}))
(def key-stroke-buffer (atom '()))
(def log (atom []))
(def session-info (atom {:hp "unknown"
                         :max-hp "unknown"
                         :mv "unknown"
                         :max-mv "unknown"
                         :pos "unknown"
                         :exits []
                         :objects []
                         :select-index 0
                         :curr-object []
                         :word-index 0
                         :target-prefix 1
                         :selected-target ""
                         :binded-cmd {:1 ["dragon"]
                                      :2 ["kneel" "spring"]
                                      :3 ["chant quiver"]
                                      :4 ["chant regen"]
                                      :5 ["chant hero"]} 
                         :binded-gamepad-cmds {
                                               :left-bumper "#dec-prefix"
                                               :right-bumper "#inc-prefix"
                                               :X "#cmd-1"
                                               :Y "#cmd-2"
                                               :A "#cmd-3"
                                               :B "#cmd-4"
                                               :rxX "#cmd-1-target"
                                               :rxY "#cmd-2-target"
                                               :rxA "#cmd-3-target"
                                               :rxB "#cmd-5"
                                               }}))
(def client-mods (atom (eval (read-string (slurp "resources/slurp.edn")))))


;; Seesaw
;; =============================================================================

(s/native!)

(def colors {:black "#303030"
             :red :red
             :green :green
             :yellow :yellow
             :blue :blue
             :magenta :magenta
             :cyan :cyan
             :white :white})
(def background-colors {:black :black
                        :red :red
                        :green :green
                        :yellow :yellow
                        :blue :blue
                        :magenta :magenta
                        :cyan :cyan
                        :white :white})

(def styles
  (doall
   (for [color (keys colors)
         background (keys background-colors)
         bold [true false]
         italic [true false]
         underline [true false]]
     [(str color background bold italic underline)
      :color (colors color)
      :background (background-colors background)
      :bold bold
      :italic italic
      :underline underline])))

(def server-output-pane
  (s/styled-text :text "Hello world"
                 :wrap-lines? true
                 :preferred-size [640 :by 640]
                 :editable? false
                 :background :black
                 :foreground :white
                 :styles styles))

(def info-pane
  (s/styled-text :text "" 
                 :wrap-lines? true
                 :preferred-size [640 :by 640]
                 :editable? false
                 :background :black
                 :foreground :white
                 :styles styles))

(def display-area
  (s/left-right-split (s/scrollable server-output-pane)
                      (s/scrollable info-pane)
                      :divider-location 1/2))

(def input-textbox
  (s/text :editable? true
          :multi-line? false
          :listen [:key-typed (fn [e]
                                (condp = (.getKeyChar e)
                                  \newline (do (>!! cmd-chan (s/config input-textbox :text))
                                               (s/config! input-textbox :text ""))
                                  e))]))

(def main-panel (s/border-panel :center display-area
                                :south input-textbox))

(def main-window (s/frame :title "mud on the couch"
                          :content main-panel))

(-> main-window s/pack! s/show!)

(d/show-options server-output-pane)





;; Client config
;; =============================================================================
(def tc
  (doto (TelnetClient.)
    (.addOptionHandler (TerminalTypeOptionHandler. "VT100"
                                                   false false true false))
    (.addOptionHandler (EchoOptionHandler. true false true false))
    (.addOptionHandler (SuppressGAOptionHandler. true true true true))
    ))

(.connect tc "mud.durismud.com" 7777)




;; Streams
;; =============================================================================
(def ips (.getInputStream tc))
(def ops (.getOutputStream tc))




;; Terminals
;; =============================================================================
(def term-input (t/get-terminal
                 :swing
                 {:cols 80
                  :rows 1
                  :palette :gnome}))

(def term-output (t/get-terminal
                  ;; :unix
                  :swing
                  {
                   :cols 160
                   :rows 50
                   :palette :mac-os-x}))



;; Controller
;; =============================================================================

(defn find-controller []
  (let [controllers (.getControllers (ControllerEnvironment/getDefaultEnvironment))] 
    (let [found-controller (first (filter #(or (= "Controller (XBOX 360 For Windows)" (.getName %))
                                               (= "Microsoft X-Box 360 pad" (.getName %))) controllers))
          controller-name (.getName found-controller)]
      (case controller-name
        "Controller (XBOX 360 For Windows)" (reset! os-type :windows-64bits)
        "Microsoft X-Box 360 pad" (reset! os-type :linux-64bits))
      found-controller)))


(def windows-64bits-button-translate
  {"X Axis" :left-x
   "Y Axis" :left-y
   "Z Axis" :trigger                    ;Left trigger +ve 1, right trigger -ve 1
   "X Rotation" :right-x
   "Y Rotation" :right-y
   "Hat Switch" :dir-pad
   "Button 0" :A
   "Button 1" :B
   "Button 2" :X
   "Button 3" :Y
   "Button 4" :left-bumper
   "Button 5" :right-bumper
   "Button 6" :back
   "Button 7" :start
   ;; "Mode" :xbox-guide
   "Button 8" :left-thumb
   "Button 9" :right-thumb})


(def linux-64bits-button-translate
  {"x" :left-x
   "y" :left-y
   "z" :left-trigger
   "rx" :right-x
   "ry" :right-y
   "rz" :right-trigger
   "pov" :dir-pad
   "A" :A
   "B" :B
   "X" :X
   "Y" :Y
   "Left Thumb" :left-bumper
   "Right Thumb" :right-bumper
   "Select" :back
   "Unknown" :start
   "Mode" :xbox-guide
   "Left Thumb 3" :left-thumb
   "Right Thumb 3" :right-thumb})

(def bin-comp-name-linux-64bits
  [:A :B :X :Y :left-bumper :right-bumper :back
   :start :xbox-guide :left-thumb :right-thumb])

(def bin-comp-name-windows-64bits
  [:A :B :X :Y :left-bumper :right-bumper :back
   :start :left-thumb :right-thumb])

(def bin-comp-right-extra-map
  {:A :rxA
   :B :rxB
   :X :rxX
   :Y :rxY
   :left-bumper :rxleft-bumper
   :right-bumper :rxright-bumper
   :back :rxback
   :start :rxstart
   :xbox-guide :rxxbox-guide
   :left-thumb :rxleft-thumb
   :right-thumb :rxright-thumb
   })

(defn get-poll-data [comp-name components]
  (let [comp-map (zipmap comp-name (map #(.getPollData %) components))]
    (if (= @os-type :windows-64bits)
      (as-> comp-map m
          (assoc m :left-trigger (m :trigger))
          (assoc m :right-trigger (- (m :trigger))))
      comp-map)))

(defn gamepad-jinput [controller]
  (let [components (.getComponents controller)
        comp-name (case @os-type
                    :linux-64bits (replace linux-64bits-button-translate (map #(.getName %) components))
                    :windows-64bits (replace windows-64bits-button-translate (map #(.getName %) components)))]
    (.poll controller)
    (loop [prev-comp-map (get-poll-data comp-name components)]
      (.poll controller)
      (let [curr-comp-map (get-poll-data comp-name components)]
        (doseq [button (case @os-type
                         :linux-64bits bin-comp-name-linux-64bits
                         :windows-64bits bin-comp-name-linux-64bits)]
          (if (and (= (prev-comp-map button) 0.0)
                   (= (curr-comp-map button) 1.0))
            (if (< (curr-comp-map :right-trigger) 0.5)
              (>!! gamepad-chan button)
              (>!! gamepad-chan (bin-comp-right-extra-map button)))))
        (if (= (prev-comp-map :dir-pad) 0.0)
          (case (int (* 1000 (curr-comp-map :dir-pad)))
            250 (>!! gamepad-chan :U)
            750 (>!! gamepad-chan :D)
            nil))
        (if (< (Math/hypot (prev-comp-map :left-y)
                           (prev-comp-map :left-x)) 0.5) 
          (let [curr-x (curr-comp-map :left-x)
                curr-y (curr-comp-map :left-y)
                r (Math/hypot curr-x curr-y)
                angle (+ 180 (Math/toDegrees (Math/atan2 curr-y curr-x)))]
            (if (>= r 0.5)
              (cond
                (< angle 22.5) (>!! gamepad-chan :W)
                (< angle 67.5) (>!! gamepad-chan :NW)
                (< angle 112.5) (>!! gamepad-chan :N)
                (< angle 157.5) (>!! gamepad-chan :NE)
                (< angle 202.5) (>!! gamepad-chan :E)
                (< angle 247.5) (>!! gamepad-chan :SE)
                (< angle 292.5) (>!! gamepad-chan :S)
                (< angle 337.5) (>!! gamepad-chan :SW)
                :else (>!! gamepad-chan :W)
                ))))
        (if (and (< (Math/abs (prev-comp-map :right-y)) 0.5)
                 (> (Math/abs (curr-comp-map :right-y)) 0.5))
          (if (pos? (curr-comp-map :right-y))
            (>!! gamepad-chan :right-y-down)
            (>!! gamepad-chan :right-y-up)))
        (if (and (< (Math/abs (prev-comp-map :right-x)) 0.5)
                 (> (Math/abs (curr-comp-map :right-x)) 0.5))
          (if (pos? (curr-comp-map :right-x))
            (>!! gamepad-chan :right-x-right)
            (>!! gamepad-chan :right-x-left)))
        (Thread/sleep 100)
        (if @running
          (recur curr-comp-map)
          (prn "Stopping gamepad-jinput"))))))

(defn handle-gamepad-cmd []
  (while @running
    (let [cmd (<!! gamepad-chan)]
      (case cmd
        :right-y-down (next-obj)
        :right-y-up (prev-obj)
        :right-x-right (next-word)
        :right-x-left (prev-word)
        :A (parse-cmd (get-in @session-info [:binded-gamepad-cmds :A]))
        :B (parse-cmd (get-in @session-info [:binded-gamepad-cmds :B]))
        :X (parse-cmd (get-in @session-info [:binded-gamepad-cmds :X]))
        :Y (parse-cmd (get-in @session-info [:binded-gamepad-cmds :Y]))
        :rxA (parse-cmd (get-in @session-info [:binded-gamepad-cmds :rxA]))
        :rxB (parse-cmd (get-in @session-info [:binded-gamepad-cmds :rxB]))
        :rxX (parse-cmd (get-in @session-info [:binded-gamepad-cmds :rxX]))
        :rxY (parse-cmd (get-in @session-info [:binded-gamepad-cmds :rxY]))
        :left-bumper (parse-cmd (get-in @session-info [:binded-gamepad-cmds :left-bumper]))
        :right-bumper (parse-cmd (get-in @session-info [:binded-gamepad-cmds :right-bumper]))
        :back nil
        :start nil
        :xbox-guide nil
        :left-thumb (parse-cmd "l")
        :right-thumb (parse-cmd "flee")
        :NW (parse-cmd "nw")
        :N (parse-cmd "n")
        :NE (parse-cmd "ne")
        :E (parse-cmd "e")
        :SE (parse-cmd "se")
        :S (parse-cmd "s")
        :SW (parse-cmd "sw")
        :W (parse-cmd "w")
        :U (parse-cmd "u")
        :D (parse-cmd "d")
        nil)))
  (prn "Stopping handle-gamepad-cmd"))

;; Functions
;; =============================================================================


(defn check-alias [cmd]
  ((@client-mods :alias) cmd))


(defn next-cmd []
  (swap! cmd-buffer update :pointer #(if (< % 0) 0 (dec %)))
  (reset! key-buffer (last (take (@cmd-buffer :pointer)
                                 (@cmd-buffer :history))))
  (t/clear term-input)
  (t/put-string term-input @key-buffer 0 0))


(defn prev-cmd []
  (swap! cmd-buffer update :pointer inc)  
  (reset! key-buffer (last (take (@cmd-buffer :pointer)
                                 (@cmd-buffer :history))))
  (t/clear term-input)
  (t/put-string term-input @key-buffer 0 0))



(defn print-key-buffer []
  (t/clear term-input)
  (t/put-string term-input @key-buffer 0 0))

(defn handle-key-stroked [c]
  (swap! key-buffer str c)
  (print-key-buffer))


(defn key-buffer-replace-last-word [w]
  (as-> @key-buffer kb
       (clojure.string/split kb #" ")
       (pop kb)
       (conj kb w)
       (clojure.string/join " " kb)
       (reset! key-buffer kb)))



(defn tab-complete []
  (let [prev-key (second @key-stroke-buffer)
        search-regex (re-pattern (str "\\b"
                                      (last (clojure.string/split
                                             @key-buffer #" "))
                                      "\\w+"))]
    (if (= prev-key :tab)
      (swap! tab-complete-list (comp set rest))
      (->> (.getText server-output-pane)
           (re-seq search-regex)
           (set)
           (reset! tab-complete-list)))
    (key-buffer-replace-last-word (first @tab-complete-list))
    (print-key-buffer)))

(defn update-word [func]
  (let [curr-object (@session-info :curr-object)
        curr-ind (func (@session-info :word-index))]
    (when (and (< curr-ind (count curr-object))
               (>= curr-ind 0))
      (let [curr-target (curr-object curr-ind)]
        (swap! session-info assoc :word-index curr-ind)
        (swap! session-info assoc :selected-target curr-target)
        (>!! render-chan
             #(render-selected-objectbox term-output
                                         5 5 145 5
                                         :magenta :yellow :black
                                         curr-object
                                         curr-ind))
        (>!! render-chan
             #(render-messagebox term-output
                                 102 0 50 5
                                 :red :black :white
                                 (str "Target: "
                                      (@session-info :target-prefix)
                                      "."
                                      curr-target)))))))

(defn prev-word []
  (update-word dec))

(defn next-word []
  (update-word inc))


(defn update-obj [func]
  (if (= func inc)
    (swap! session-info update
           :select-index
           #(if (< % (dec (count (@session-info :objects)))) (func %) %))
    (swap! session-info update
           :select-index
           #(if (> % 0) (func %) %)))
  (swap! session-info assoc
         :curr-object
         (clojure.string/split
          (get-in @session-info
                  [:objects (@session-info :select-index)]) #" "))
  (>!! render-chan
       #(render-objectsbox term-output
                           0 37 102 12
                           :magenta :black :white
                           (@session-info :objects)
                           (@session-info :select-index))))

(defn prev-obj []
  (update-obj dec))

(defn next-obj []
  (update-obj inc))

(defn key-stroke-capturer []
  (t/in-terminal
   term-input
   (while @running
     (let [c (t/get-key-blocking term-input)]
       (swap! key-stroke-buffer conj c)
       (case c
         :escape (reset! interface-mode :input-key)
         :backspace (do (swap! key-buffer
                               (comp (partial apply str) butlast))
                        (t/clear term-input)
                        (t/put-string term-input @key-buffer 0 0))
         :left (case @interface-mode
                 :input-key nil
                 :selection (prev-word))
         :right (case @interface-mode
                  :input-key nil
                  :selection (next-word))
         :up (case @interface-mode
               :input-key (prev-cmd)
               :selection (prev-obj))
         :down (case @interface-mode
                 :input-key (next-cmd)
                 :selection (next-obj))
         :insert nil
         :delete nil
         :home nil
         :end nil
         :page-up nil
         :page-down nil
         :tab (tab-complete)
         :reverse-tab nil
         :enter (do (t/clear term-input)
                    (parse-cmd))
         \space (if-let [alias-cmd (check-alias @key-buffer)]
                  (do (reset! key-buffer (str alias-cmd \space))
                      (t/clear term-input)
                      (t/put-string term-input
                                    (str alias-cmd \space) 0 0))
                  (handle-key-stroked \space))
         (handle-key-stroked c))))
   (prn "Stopping key-stroke-capturer")))



(defn parse-cmd 
  ([] (parse-cmd @key-buffer))
  ([cmd]
   (println "Parsing cmd: " cmd)
   (if-let [alias-cmd (check-alias cmd)]
     (>!! cmd-chan alias-cmd)
     (case cmd
       "#quit" (do
                 (spit "resources/log.txt" (str @log))
                 (reset! running false)
                 (close! screen-chan)
                 (shutdown-agents))
       "#clear" (t/clear term-output)
       "#select" (reset! interface-mode :selection)
       "#cmd-1" (doseq [cmd (get-in @session-info [:binded-cmd :1])] (>!! cmd-chan cmd))
       "#cmd-2" (doseq [cmd (get-in @session-info [:binded-cmd :2])] (>!! cmd-chan cmd))
       "#cmd-3" (doseq [cmd (get-in @session-info [:binded-cmd :3])] (>!! cmd-chan cmd))
       "#cmd-4" (doseq [cmd (get-in @session-info [:binded-cmd :4])] (>!! cmd-chan cmd))
       "#cmd-5" (doseq [cmd (get-in @session-info [:binded-cmd :5])] (>!! cmd-chan cmd))
       "#cmd-1-target" (let [target (str (@session-info :target-prefix)
                                          "."
                                          (@session-info :selected-target))
                             cmds (get-in @session-info [:binded-cmd :1])]
                         (doseq [cmd (butlast cmds)]
                           (>!! cmd-chan cmd))
                         (>!! cmd-chan (str (last cmds) " " target)))
       "#cmd-2-target" (let [target (str (@session-info :target-prefix)
                                          "."
                                          (@session-info :selected-target))
                             cmds (get-in @session-info [:binded-cmd :2])]
                         (doseq [cmd (butlast cmds)]
                           (>!! cmd-chan cmd))
                         (>!! cmd-chan (str (last cmds) " " target)))
       "#cmd-3-target" (let [target (str (@session-info :target-prefix)
                                          "."
                                          (@session-info :selected-target))
                             cmds (get-in @session-info [:binded-cmd :3])]
                         (doseq [cmd (butlast cmds)]
                           (>!! cmd-chan cmd))
                         (>!! cmd-chan (str (last cmds) " " target)))
       "#cmd-4-target" (let [target (str (@session-info :target-prefix)
                                          "."
                                          (@session-info :selected-target))
                             cmds (get-in @session-info [:binded-cmd :4])]
                         (doseq [cmd (butlast cmds)]
                           (>!! cmd-chan cmd))
                         (>!! cmd-chan (str (last cmds) " " target)))
       "#inc-prefix" (do (swap! session-info update :target-prefix inc)
                         (let [curr-object (@session-info :curr-object)
                               curr-ind (@session-info :word-index)
                               curr-target (curr-object curr-ind)]
                           (>!! render-chan
                                #(render-messagebox term-output
                                                    102 0 50 5
                                                    :red :black :white
                                                    (str "Target: "
                                                         (@session-info :target-prefix)
                                                         "."
                                                         curr-target)))))
       "#dec-prefix" (do (swap! session-info update :target-prefix dec)
                         (let [curr-object (@session-info :curr-object)
                               curr-ind (@session-info :word-index)
                               curr-target (curr-object curr-ind)]
                           (>!! render-chan
                                #(render-messagebox term-output
                                                    102 0 50 5
                                                    :red :black :white
                                                    (str "Target: "
                                                         (@session-info :target-prefix)
                                                         "."
                                                         curr-target)))))
       "#load" (reset! client-mods (eval
                                    (read-string
                                     (slurp "resources/slurp.edn"))))
       (>!! cmd-chan cmd)))))



(defn server-output-receiver []
  (while @running
    (Thread/sleep 100)
    (loop [recv-str ""]
      (let [j (.available ips)]
        (if (> j 0)
          (let [i (.read ips in-buf 0 (if (> j 1024) 1024 j))] 
            (recur (str recv-str (String. in-buf 0 i))))
          (if (not-empty recv-str)
            (>!! screen-chan recv-str))))))
  (prn "Stopping print-server-output"))


(defn print-to-server-output-pane []
  (while @running 
    (let [recv-str (<!! screen-chan)
          decolored-str (remove-ansi-escape-code recv-str)]
      (-> recv-str
          (generate-markup)
          (parse-markup (.getDocument server-output-pane)))
      (s/scroll! server-output-pane :to :bottom)
      (>!! trigger-chan decolored-str))))


(defn handle-screen-buffer [lines]
  (swap! screen-buffer
         #(->> %2
               (apply conj %1)
               (take-last 5000)
               (vec))
         lines))


(defn re-to-act [buf]
  (fn [[re act]]
    (dorun (map act (re-seq re buf)))))


(defn handle-triggers []
  (while @running
    (let [triggers (@client-mods :trigger)
          buf (<!! trigger-chan)]
      (dorun
       (map (re-to-act buf) triggers))))
  (prn "Stopping trigger handlers"))


(defn handle-cmd-history [cmd]
  (swap! cmd-buffer (fn [m]
                      (-> m
                          (update :history conj cmd)
                          (assoc :pointer 0))))
  (reset! key-buffer ""))


(defn cmd-sender []
  (while @running
    (let [cmd (<!! cmd-chan)
          cmd-vec (vec (str cmd \newline))
          out-buf (byte-array (map (comp byte int) cmd-vec))
          i (count cmd-vec)]
      (.write ops out-buf 0 i)
      (.flush ops)
      (handle-cmd-history cmd)))
  (prn "Stopping cmd-sender"))


  


;; Start threads
;; =============================================================================

(defn -main
  [& args]
  (prn "Mud starting..............")
  (future (key-stroke-capturer))
  (future (server-output-receiver))
  (future (cmd-sender))
  (future (handle-triggers))
  (future (gamepad-jinput (find-controller)))
  (future (handle-gamepad-cmd))
  (future (print-to-server-output-pane)))
