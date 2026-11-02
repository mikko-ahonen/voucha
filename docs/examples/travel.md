# The travel booking example

OMG's travel booking scenario, chapter 9 of *BPMN 2.0 by Example*, transcribed as a definition.
It is the third example and the one not designed for voucha: a saga, with the client in the
loop, compensation and two retry limits.

| | |
|---|---|
| Language | Clojure, handlers as data |
| Definition | [`src/voucha/example/travel.clj`](../../src/voucha/example/travel.clj), transitions with their plans, and the app |
| Source | *BPMN 2.0 by Example*, OMG, June 2010, section 9; the mapping task by task is in the namespace's docstring |
| Tests | [`tests/voucha/travel_example_test.clj`](../../tests/voucha/travel_example_test.clj) |
| Run | `bin/voucha dev voucha.example.travel/app` |

## What it does

A travel agency receives a request for a flight and a hotel room, searches and packages
alternatives, and offers them to the client, who has 24 hours to select one or cancel. On a
selection the client is asked for credit card information, again within 24 hours. The flight and
the hotel room are then booked; while they are, the client may send a new card, which is saved.
A rejected booking reverses the other, updates the client record and retries the booking, up to
a limit. Once both are booked the card is charged; a declined card reverses both bookings and asks
the client for the card again, up to a limit. Past either limit the client is notified and the
process stops. A cancellation or a silence updates the record and notifies the client to start
again.

```
Trip:        TRIP_NEW → TRIP_SEARCHING → TRIP_OFFERED → TRIP_AWAITING_CARD → TRIP_BOOKING
                      → TRIP_CHARGING → TRIP_CONFIRMING → TRIP_CONFIRMED
             TRIP_BOOKING → TRIP_BOOKING_FAILED → TRIP_REVERSING → TRIP_RECORDING_FAILURE
                      → TRIP_RETRY_WAIT → TRIP_BOOKING | TRIP_AWAITING_CARD
                                        | TRIP_CLOSING_FAILED → TRIP_NOTIFYING_FAILURE → TRIP_FAILED
             TRIP_CHARGING → TRIP_REVERSING (declined)
             → TRIP_CANCELLING → TRIP_CLOSING_CANCELLED → TRIP_NOTIFYING_CANCELLATION → TRIP_CANCELLED
Reservation: RES_NEW → RES_BOOKING → RES_BOOKED → RES_CANCELLING → RES_CANCELLED → RES_BOOKING ...
                     → RES_FAILED → RES_CANCELLED;  RES_BOOKED → RES_CONFIRMED;  → RES_CLOSED
```

The two reservations live in `TRIP_BOOKING`; the trip decides from its counters when every
booking has settled, when every reversal is through, and when every reservation is confirmed or
closed.

## What it shows

- **The client is an external system.** The offer and the card request are effects whose
  outcomes arrive as callbacks, the selection, the card, the cancellation, with the 24 hours as
  the effect's completion timeout. Nothing waits in handler code.
- **Compensation through the children.** Reversal is a command to every reservation: a booked
  one cancels with its provider, a rejected one has nothing to reverse and moves along, and the
  trip waits until every one has released before it records the failure and decides whether to
  retry.
- **Cycles.** A retry re-commands the same two reservations under a new booking key. Every
  reservation starts a cycle from a state the trip's decisions do not count, so a failure left
  from the last cycle cannot pass for this one's. The simulator found the version that did not
  do this: a trip deciding a cycle over before its sibling's command had been processed, and the
  command then arriving at a closing trip.
- **Uncertain bookings and charges** use the query policy: asked about, never repeated.
- **Five business invariants**, all provable: one charge the provider may have acted on per
  trip, a confirmed trip has exactly one completed charge and every reservation booked, a
  reversal with the provider only after a booking, and a failed trip has reversed every booking
  it made.

Where the checks stand: the simulator explores 20,000 worlds of the two-reservation trip with no
violation, the P checker 1,000 schedules with no bug, and TLC enumerates the one-reservation trip,
117,205 distinct states, with no violation; the two-reservation trip is beyond TLC's reach in four
hours, as a SEPA file of two transactions is. The induction proves it at the default bounds with no
lemma, its five business invariants included: the initial state in 1 min 33 s, the step in 1 h 9 min, the
implication in 2 min 36 s. On the way the step check found five counterexamples in its first day,
three of them rules the generator lacked, one a defect in the translation of plans that branch, one
an artefact of the abstraction; none was a fact about this process, and the definition did not
change for them.

What the transcription reads into the scenario: an empty request is cancelled at once; a record
update or a notification that fails after its retries is recorded and the process goes on, since
the scenario gives them no failure path; and a booking in flight is awaited, not interrupted.

## Try it

```bash
bin/voucha dev voucha.example.travel/app
curl -XPOST localhost:8710/tree  -d '{"tree-id":"t1","root-node-id":"T1","root-node-type":"Trip"}'
curl -XPOST localhost:8710/event -d '{"tree-id":"t1","node-id":"T1","event-type":"ReservationRequested",
  "payload":{"client":"C1","reservations":[{"reservation-id":"T1-F","kind":"flight"},{"reservation-id":"T1-H","kind":"hotel"}]}}'
curl localhost:8710/tree/t1
```

The client's answers are callbacks: `POST /simulate/callback` with the trip's id as the
correlation key and `client.offer` or `client.card-request` as the effect, and
`POST /simulate/timeouts` is the 24 hours passing.

Everything the tools reach, within ten minutes, from the scenario the app declares:

```bash
bin/voucha verify voucha.example.travel/app
```

Or each alone, with `tmp/travel.edn` holding the request above:

```bash
bin/voucha simulate voucha.example.travel/app --root Trip T1 --event ReservationRequested --payload tmp/travel.edn
bin/voucha model voucha.example.travel/app --root Trip T1 --event ReservationRequested --payload tmp/travel.edn --check
bin/voucha prove voucha.example.travel/app --root Trip T1 --event ReservationRequested --payload tmp/travel.edn --check
```

And the proof for every trip, by induction (see [testing.md](../testing.md)):

```bash
bin/voucha prove voucha.example.travel/app --root Trip T1 --event ReservationRequested --payload '{:client "C1"}' \
    --children Reservation=2 --inductive
```
