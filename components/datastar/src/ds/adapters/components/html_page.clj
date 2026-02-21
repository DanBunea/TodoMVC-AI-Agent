(ns ds.adapters.components.html-page)

(defn page-head []
  [:head
   [:title "db - d*"]
   [:link {:rel "stylesheet" :href "/css/app.css"}]
   [:link {:rel "stylesheet" :href "/css/layout.css"}]
   [:link {:rel "stylesheet" :href "/css/taia.css"}]
   [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=Poppins:wght@100;300;400;500;600;700;800;900&amp;display=swap"}]
   [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=Lato:ital,wght@0,100;0,300;0,400;0,700;0,900;1,100;1,300;1,400;1,700;1,900&display=swap"}]
   [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined:opsz,wght,FILL,GRAD@20..48,100..700,0..1,-50..200"}]
   [:script {:type "module"
             :src "/js/datastar.js"}]])

