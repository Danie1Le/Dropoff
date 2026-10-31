# Foreign keys across modules until the service split

While Dropoff is one app, foreign keys exist everywhere, including across module boundaries (e.g. `orders.merchant_id → merchants.id`), so Postgres rejects orphaned or dangling rows. At the M5 service split each service gets its own database and the cross-module foreign keys are dropped; referential checks then move into the services and events.

## Consequences

The split migration must drop these constraints deliberately, and anything relying on them for integrity needs an application-level check first.
