(ns status-im.models.transactions
  (:require [clojure.set :as set]
            [status-im.utils.datetime :as time]
            [status-im.utils.ethereum.core :as ethereum]
            [status-im.utils.ethereum.tokens :as tokens]
            [status-im.utils.semaphores :as semaphores]
            [status-im.utils.ethereum.erc20 :as erc20]
            [status-im.utils.handlers :as handlers]
            [status-im.utils.transactions :as transactions]
            [taoensso.timbre :as log]
            [status-im.utils.fx :as fx]
            [re-frame.core :as re-frame]))

(def sync-interval-ms 15000)
(def confirmations-count-threshold 12)

;; TODO is it a good idea for :confirmations to be a string?
;; Seq[transaction] -> truthy
(defn- have-unconfirmed-transactions?
  "Detects if some of the transactions have less than 12 confirmations"
  [transactions]
  {:pre [(every? string? (map :confirmations transactions))]}
  (->> transactions
       (map :confirmations)
       (map int)
       (some #(< % confirmations-count-threshold))))

;; this could be a util but ensure that its needed elsewhere before moving
(defn- keyed-memoize
  "Takes a key-function that decides the name of the cached entry.
  Takes a value function that will invalidate the cache if it changes.
  And finally the function to memoize.

  Memoize that doesn't grow bigger than the number of keys."
  [key-fn val-fn f]
  (let [val-store (atom {})
        res-store (atom {})]
    (fn [arg]
      (let [k (key-fn arg)
            v (val-fn arg)]
        (if (not= (get @val-store k) v)
          (let [res (f arg)]
            #_(prn "storing!!!!" res)
            (swap! val-store assoc k v)
            (swap! res-store assoc k res)
            res)
          (get @res-store k))))))

;; Map[id, chat] -> Set[transaction-id]
(let [chat-map-entry->transaction-ids
      (keyed-memoize key (comp :messages val)
                     (fn [[_ chat]]
                       (->> (:messages chat)
                            vals
                            (filter #(= "command" (:content-type %)))
                            (keep #(get-in % [:content :params :tx-hash])))))]
  (defn- chat-map->transaction-ids [chat-map]
    {:pre [(every? :messages (vals chat-map))]
     :post [(set? %)]}
    (->> chat-map
         (remove (comp :public? val))
         (mapcat chat-map-entry->transaction-ids)
         set)))

(fx/defn schedule-sync [cofx]
  {:utils/dispatch-later [{:ms       sync-interval-ms
                           :dispatch [:sync-wallet-transactions]}]})

(defn combine-entries [transaction token-transfer]
  (merge transaction (select-keys token-transfer [:symbol :from :to :value :type :token :transfer])))

(defn update-confirmations [tx1 tx2]
  (assoc tx1 :confirmations (max (:confirmations tx1)
                                 (:confirmations tx2))))

(defn- tx-and-transfer?
  "A helper function that checks if first argument is a transaction and the second argument a token transfer object."
  [tx1 tx2]
  (and (not (:transfer tx1)) (:transfer tx2)))

(defn- both-transfer?
  [tx1 tx2]
  (and (:transfer tx1) (:transfer tx2)))

(defn dedupe-transactions [tx1 tx2]
  (cond (tx-and-transfer? tx1 tx2) (combine-entries tx1 tx2)
        (tx-and-transfer? tx2 tx1) (combine-entries tx2 tx1)
        (both-transfer? tx1 tx2)   (update-confirmations tx1 tx2)
        :else tx2))

(defn own-transaction? [address [_ {:keys [type to from]}]]
  (let [normalized (ethereum/normalized-address address)]
    (or (and (= :inbound type) (= normalized (ethereum/normalized-address to)))
        (and (= :outbound type) (= normalized (ethereum/normalized-address from)))
        (and (= :failed type) (= normalized (ethereum/normalized-address from))))))

(handlers/register-handler-fx
 :update-transactions-success
 (fn [{:keys [db]} [_ transactions address]]
   ;; NOTE(goranjovic): we want to only show transactions that belong to the current account
   ;; this filter is to prevent any late transaction updates initated from another account on the same
   ;; device from being applied in the current account.
   (let [own-transactions (into {} (filter #(own-transaction? address %) transactions))]
     {:db (-> db
              (update-in [:wallet :transactions] #(merge-with dedupe-transactions % own-transactions))
              (assoc-in [:wallet :transactions-loading?] false))})))

(handlers/register-handler-fx
 :update-transactions-fail
 (fn [{:keys [db]} [_ err]]
   (log/debug "Unable to get transactions: " err)
   {:db
    (-> db
        (assoc-in [:wallet :errors :transactions-update]
                  :error-unable-to-get-transactions)
        (assoc-in [:wallet :transactions-loading?] false))}))

(re-frame/reg-fx
 :get-transactions
 (fn [{:keys [web3 chain account-id token-addresses success-event error-event]}]
   (transactions/get-transactions chain
                                  account-id
                                  #(re-frame/dispatch [success-event % account-id])
                                  #(re-frame/dispatch [error-event %]))
   (doseq [direction [:inbound :outbound]]
     (erc20/get-token-transactions web3
                                   chain
                                   token-addresses
                                   direction
                                   account-id
                                   #(re-frame/dispatch [success-event % account-id])))))

(fx/defn run-update [{{:keys [network network-status web3] :as db} :db}]
  (when (not= network-status :offline)
    (let [network (get-in db [:account/account :networks network])
          chain (ethereum/network->chain-keyword network)]
      (when-not (= :custom chain)
        (let [all-tokens (tokens/tokens-for chain)
              token-addresses (map :address all-tokens)]
          (log/debug "Syncing transactions data..")
          {:get-transactions {:account-id      (get-in db [:account/account :address])
                              :token-addresses token-addresses
                              :chain           chain
                              :web3            web3
                              :success-event   :update-transactions-success
                              :error-event     :update-transactions-fail}
           :db               (-> db
                                 (update-in [:wallet :errors] dissoc :transactions-update)
                                 (assoc-in [:wallet :transactions-loading?] true)
                                 (assoc-in [:wallet :transactions-last-updated-at] (time/timestamp)))})))))

(defn- time-to-sync? [cofx]
  (let [last-updated-at (get-in cofx [:db :wallet :transactions-last-updated-at])]
    (or (nil? last-updated-at)
        (< sync-interval-ms
           (- (time/timestamp) last-updated-at)))))

(fx/defn sync
  "Fetch updated data for any unconfirmed transactions or incoming chat transactions missing in wallet
  and schedule new recurring sync request"
  [{:keys [db] :as cofx}]
  (if (:account/account db)
    (let [in-progress? (get-in db [:wallet :transactions-loading?])
          {:keys [app-state network-status wallet chats]} db
          chat-transaction-ids (chat-map->transaction-ids chats)
          transaction-map (:transactions wallet)
          transaction-ids (set (keys transaction-map))]
      (assert (set? chat-transaction-ids))
      (if (and (not= network-status :offline)
               (= app-state "active")
               (not in-progress?)
               (time-to-sync? cofx)
               (or (have-unconfirmed-transactions? (vals transaction-map))
                   (not-empty (set/difference chat-transaction-ids transaction-ids))))
        (fx/merge cofx
                  (run-update)
                  (schedule-sync))
        (schedule-sync cofx)))
    (semaphores/free cofx :sync-wallet-transactions?)))

(fx/defn start-sync [cofx]
  (when-not (semaphores/locked? cofx :sync-wallet-transactions?)
    (fx/merge cofx
              (semaphores/lock :sync-wallet-transactions?)
              (sync))))
