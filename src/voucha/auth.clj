(ns voucha.auth
  "Level-1 edge authentication — the contract and the mechanism, no backends.

  An *authenticator* resolves the caller's identity from an event's transient
  `:credentials` (a token, request headers) into a durable `:actor` and `:claims`
  frozen onto the event — or it rejects the event. It runs at the
  `:event/before-submit` seam, in the I/O zone, so it may validate a JWT against a
  JWKS endpoint or bind a directory. That I/O is exactly why edge auth cannot live
  in a transition (design: testing-api.md): the transition must stay deterministic
  and replayable, and it reads the frozen claims, never the live credential.

  Core owns this contract and nothing more. Concrete backends — JWT, OAuth2,
  LDAP, mTLS — are contrib libraries that supply the authenticator fn. Core ships
  no crypto and no directory client.

  A Level-2 policy (the Decide tier, Phase C) then reads `:claims` off the event,
  deterministically. Because Level 1 froze the claims and Level 2 is pure over
  them, an authorization decision replays exactly — the whole point of the split."
  (:require [voucha.intercept :as intercept]))

(defn reject!
  "Abort submission: the caller is not authorized. `status` is the HTTP status an
  ingress should return (401 by default); the admin API reads it."
  ([reason] (reject! reason 401 {}))
  ([reason status] (reject! reason status {}))
  ([reason status data]
   (throw (ex-info reason (merge {:type ::unauthorized :http-status status} data)))))

(defn install-authenticator!
  "Install `authenticate` at `:event/before-submit`.

  `authenticate` is `(fn [event] -> {:actor .. :claims ..})`: it reads the event's
  transient `:credentials`, and returns the resolved identity, or calls `reject!`.
  Its result is frozen onto the event as `:actor`/`:claims`; the credential is
  stripped by `submit-event!` and never persisted. Returns an interceptor id."
  [authenticate]
  (intercept/install!
   {:point :event/before-submit
    :tier :auth
    :action (fn [event]
              (let [{:keys [actor claims]} (authenticate event)]
                (assoc event :actor actor :claims claims)))}))
