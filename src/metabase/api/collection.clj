(ns metabase.api.collection
  "/api/collection endpoints."
  (:require [compojure.core :refer [GET POST DELETE PUT]]
            [schema.core :as s]
            [metabase.api.common :as api]
            [metabase.db :as db]
            (metabase.models [card :refer [Card]]
                             [collection :refer [Collection]]
                             [interface :as models]
                             [permissions :as perms])
            [metabase.util.schema :as su]))


(api/defendpoint GET "/"
  "Fetch a list of all (non-archived) Collections that the current user has read permissions for."
  []
  (filterv models/can-read? (db/select Collection :archived false {:order-by [[:%lower.name :asc]]})))

(api/defendpoint GET "/:id"
  "Fetch a specific (non-archived) Collection, including cards that belong to it."
  [id]
  ;; TODO - hydrate the `:cards` that belong to this Collection
  (assoc (api/read-check Collection id, :archived false)
    :cards (db/select Card, :collection_id id, :archived false)))

(api/defendpoint POST "/"
  "Create a new Collection."
  [:as {{:keys [name color description]} :body}]
  {name su/NonBlankString, color #"^[0-9A-Fa-f]{6}$", description (s/maybe su/NonBlankString)}
  (api/check-superuser)
  (db/insert! Collection
    :name  name
    :color color))

(api/defendpoint PUT "/:id"
  "Modify an existing Collection, including archiving or unarchiving it."
  [id, :as {{:keys [name color description archived]} :body}]
  {name su/NonBlankString, color #"^[0-9A-Fa-f]{6}$", description (s/maybe su/NonBlankString), archived (s/maybe s/Bool)}
  ;; you have to be a superuser to modify a Collection itself, but `/collection/:id/` perms are sufficient for adding/removing Cards
  (api/check-superuser)
  (api/check-exists? Collection id)
  (db/update! Collection id
    :name        name
    :color       color
    :description description
    :archived    (if (nil? archived)
                   false
                   archived)))


;;; ------------------------------------------------------------ GRAPH ENDPOINTS ------------------------------------------------------------

(defn- group-id->perms-set []
  (into {} (for [[group-id perms] (group-by :group_id (db/select 'Permissions))]
             {group-id (set (map :object perms))})))

(defn- perms-type-for-collection [perms-set collection-id]
  (cond
    (perms/set-has-full-permissions? perms-set (perms/collection-readwrite-path collection-id)) :write
    (perms/set-has-full-permissions? perms-set (perms/collection-read-path collection-id))      :read
    :else                                                                                       :none))

(defn- graph []
  (let [group-id->perms (group-id->perms-set)
        collection-ids  (db/select-ids 'Collection)]
    {:revision 1
     :groups   (into {} (for [group-id (db/select-ids 'PermissionsGroup)]
                          {group-id (let [perms-set (group-id->perms group-id)]
                                      (into {} (for [collection-id collection-ids]
                                                 {collection-id (perms-type-for-collection perms-set collection-id)})))}))}))

(defn- update-graph! [new-graph])

(api/defendpoint GET "/graph"
  "Fetch a graph of all Collection Permissions."
  []
  (api/check-superuser)
  (graph))

(api/defendpoint PUT "/graph"
  "Do a batch update of Collections Permissions by passing in a modified graph."
  [:as {body :body}]
  {body su/Map}
  (api/check-superuser)
  (update-graph! body))


(api/define-routes)
